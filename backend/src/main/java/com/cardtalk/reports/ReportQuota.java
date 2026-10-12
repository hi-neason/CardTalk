package com.cardtalk.reports;

import com.cardtalk.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Persists only SHA-256 digests and quota metadata; never facts or report text. */
@Component
public class ReportQuota {
    public record Reservation(String identity,String day,String nonce,String attempt,boolean completed) {}
    private record Entry(String digest,String state,String attempt,long expires) {}
    private static final long LEASE_MILLIS=300_000;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReportQuota.class);
    private final String url;
    private final Properties properties=new Properties();
    private final int limit;
    private final Clock clock;
    private final Map<String,Map<String,Entry>> memory=new HashMap<>();
    private String memoryDay="";

    @Autowired
    public ReportQuota(@Value("${cardtalk.report-quota-mode:memory}") String mode,
                       @Value("${cardtalk.family-jdbc-url:}") String url,
                       @Value("${cardtalk.family-jdbc-user:}") String user,
                       @Value("${cardtalk.family-jdbc-password:}") String password,
                       @Value("${cardtalk.report-daily-limit:5}") int limit) {
        this(mode,url,user,password,limit,Clock.system(ZoneId.of("Asia/Shanghai")));
    }
    ReportQuota(String mode,String url,String user,String password,int limit,Clock clock) {
        if(!Set.of("memory","postgres").contains(mode)||limit<1||limit>100) throw new IllegalArgumentException("Invalid report quota configuration");
        this.limit=limit;this.clock=clock;this.url=mode.equals("postgres")?url:null;
        if(this.url!=null) {
            if(!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Report quota requires PostgreSQL JDBC URL");
            properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");
            try { migrate(); } catch(SQLException|IOException ex) { throw new IllegalStateException("Report quota migration failed; no memory fallback",ex); }
        }
    }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    public Reservation reserve(String identity,String nonce,String digest) {
        String day=LocalDate.now(clock).toString();
        return transact(identity,day,entries->{
            Entry previous=entries.get(nonce);
            if(previous!=null&&!previous.digest.equals(digest)) throw error(409,"REPORT_REQUEST_CONFLICT","同一请求编号对应不同统计，请使用新的请求编号");
            if(previous!=null&&previous.state.equals("completed")) return new Reservation(identity,day,nonce,previous.attempt,true);
            if(previous!=null&&previous.state.equals("pending")&&previous.expires>clock.millis()) throw error(409,"REPORT_IN_PROGRESS","此说明正在生成，请稍后重试");
            if(used(entries)>=limit) throw error(429,"REPORT_QUOTA_EXCEEDED","今日 AI 统计说明体验次数已用完");
            if(previous==null&&entries.size()>=100) throw error(429,"REPORT_REQUEST_LIMIT","今日报告请求次数过多，请稍后重试");
            String attempt=UUID.randomUUID().toString();entries.put(nonce,new Entry(digest,"pending",attempt,clock.millis()+LEASE_MILLIS));return new Reservation(identity,day,nonce,attempt,false);
        });
    }
    public int complete(Reservation reservation) {
        return transact(reservation.identity,reservation.day,entries->{
            Entry entry=entries.get(reservation.nonce);
            if(entry==null||!entry.attempt.equals(reservation.attempt)||entry.state.equals("failed")||entry.state.equals("pending")&&entry.expires<=clock.millis()) throw error(409,"REPORT_RESERVATION_EXPIRED","报告生成预约已过期，请重试");
            entries.put(reservation.nonce,new Entry(entry.digest,"completed",entry.attempt,0));return Math.max(0,limit-used(entries));
        });
    }
    public void release(Reservation reservation) {
        transact(reservation.identity,reservation.day,entries->{
            Entry entry=entries.get(reservation.nonce);
            if(entry!=null&&entry.state.equals("pending")&&entry.attempt.equals(reservation.attempt)) entries.put(reservation.nonce,new Entry(entry.digest,"failed",entry.attempt,0));return null;
        });
    }
    private int used(Map<String,Entry> entries) { return (int)entries.values().stream().filter(e->e.state.equals("completed")||e.state.equals("pending")&&e.expires>clock.millis()).count(); }
    private static ApiException error(int status,String code,String message) { return new ApiException(status,code,message); }
    private Connection connection()throws SQLException { return DriverManager.getConnection(url,properties); }
    private static void lock(Connection c,long key)throws SQLException { try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) { s.setLong(1,key);s.execute(); } }
    private <T> T transact(String identity,String day,Function<Map<String,Entry>,T> action) {
        if(url==null) synchronized(memory) {
            String today=LocalDate.now(clock).toString();if(!today.equals(memoryDay)) { memory.clear();memoryDay=today; }
            String key=identity+":"+day;
            if(!memory.containsKey(key)&&memory.size()>=10000) throw error(429,"REPORT_BUSY","报告体验服务繁忙，请稍后重试");
            return action.apply(memory.computeIfAbsent(key,k->new HashMap<>()));
        }
        try(var c=connection()) {
            c.setAutoCommit(false);lock(c,Long.parseUnsignedLong(hash("report:"+identity+":"+day).substring(0,16),16));
            Map<String,Entry> entries=new HashMap<>();
            try(var s=c.prepareStatement("SELECT nonce_hash,facts_hash,state,attempt,expires_at FROM ct_report_quota WHERE identity_hash=? AND quota_day=?")) {
                s.setString(1,identity);s.setDate(2,java.sql.Date.valueOf(day));try(var rows=s.executeQuery()) { while(rows.next()) entries.put(rows.getString(1),new Entry(rows.getString(2),rows.getString(3),rows.getString(4),rows.getLong(5))); }
            }
            Map<String,Entry> before=new HashMap<>(entries);T result=action.apply(entries);
            try(var s=c.prepareStatement("INSERT INTO ct_report_quota(identity_hash,quota_day,nonce_hash,facts_hash,state,attempt,expires_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(identity_hash,quota_day,nonce_hash) DO UPDATE SET state=excluded.state,attempt=excluded.attempt,expires_at=excluded.expires_at")) {
                for(var pair:entries.entrySet()) if(!pair.getValue().equals(before.get(pair.getKey()))) { Entry e=pair.getValue();s.setString(1,identity);s.setDate(2,java.sql.Date.valueOf(day));s.setString(3,pair.getKey());s.setString(4,e.digest);s.setString(5,e.state);s.setString(6,e.attempt);s.setLong(7,e.expires);s.addBatch(); }s.executeBatch();
            }
            c.commit();return result;
        } catch(SQLException ex) { LOG.error("Report quota storage failed (SQL state: {})",ex.getSQLState());throw new ApiException(503,"REPORT_STORAGE_UNAVAILABLE","报告额度暂时无法访问，请重试",ex); }
    }
    private void migrate()throws SQLException,IOException {
        String migration;
        try(var stream=ReportQuota.class.getResourceAsStream("/db/migrations/V005__report_quota.sql")) { if(stream==null) throw new IOException("Missing report quota migration");migration=new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
        try(var c=connection()) {
            c.setAutoCommit(false);lock(c,0x43545245504f5254L);
            try(var s=c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS ct_report_schema_version(version integer PRIMARY KEY,checksum char(64) NOT NULL)");
                try(var rows=s.executeQuery("SELECT version,checksum FROM ct_report_schema_version")) { if(rows.next()) { if(rows.getInt(1)!=5||!rows.getString(2).equals(hash(migration))||rows.next()) throw new SQLException("Unsupported report quota schema");c.commit();return; } }
                for(String sql:migration.split(";")) if(!sql.isBlank()) s.execute(sql);
            }
            try(var s=c.prepareStatement("INSERT INTO ct_report_schema_version VALUES(5,?)")) { s.setString(1,hash(migration));s.executeUpdate(); }c.commit();
        }
    }
}
