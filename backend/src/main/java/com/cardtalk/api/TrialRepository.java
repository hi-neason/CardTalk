package com.cardtalk.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** PostgreSQL trial identities and content-free reservation ledger. JSON development mode stays in TrialService. */
public final class TrialRepository {
    public record Identity(String clientId,String tokenHash,int remaining) {}
    public record Reservation(String clientId,String nonce,String day,String attempt,boolean completed,int remaining) {}
    private final String url;
    private final Properties properties=new Properties();
    private final Clock clock;
    private final int limit;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TrialRepository.class);
    public TrialRepository(String url,String user,String password,int limit,Clock clock) {
        if(!url.startsWith("jdbc:postgresql:")||limit<1) throw new IllegalArgumentException("PostgreSQL trial quota requires valid JDBC configuration");
        this.url=url;this.limit=limit;this.clock=clock;
        properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");
        try { migrate(); } catch(SQLException|IOException ex) { throw new IllegalStateException("Trial schema initialization failed; no JSON fallback",ex); }
    }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    private String day() { return LocalDate.now(clock).toString(); }
    private Connection connection()throws SQLException { return DriverManager.getConnection(url,properties); }
    private static void lock(Connection c,long key)throws SQLException { try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {s.setLong(1,key);s.execute();} }
    private static void lock(Connection c,String client)throws SQLException {lock(c,Long.parseUnsignedLong(hash("trial:"+client).substring(0,16),16));}
    private ApiException unavailable(SQLException ex) { LOG.error("Trial quota storage failed (SQL state: {})",ex.getSQLState());return new ApiException(503,"STORAGE_UNAVAILABLE","体验额度暂时无法访问，请稍后重试",ex); }
    private static ApiException unauthorized() {return new ApiException(401,"UNAUTHORIZED","体验身份已失效，请重新开始体验");}
    public Identity create(String client,String tokenHash) {
        try(var c=connection()) {
            c.setAutoCommit(false);lock(c,client);
            try(var s=c.prepareStatement("INSERT INTO ct_trial_sessions(client_id,token_hash) VALUES(?,?) ON CONFLICT(client_id) DO UPDATE SET token_hash=excluded.token_hash")){s.setString(1,client);s.setString(2,tokenHash);s.executeUpdate();}
            var result=new Identity(client,tokenHash,remaining(c,client,day()));c.commit();return result;
        }catch(SQLException ex){throw unavailable(ex);}
    }
    private String authenticate(Connection c,String tokenHash)throws SQLException {
        try(var s=c.prepareStatement("SELECT client_id FROM ct_trial_sessions WHERE token_hash=?")){s.setString(1,tokenHash);try(var r=s.executeQuery()){if(!r.next())throw unauthorized();return r.getString(1);}}
    }
    private String authenticateLocked(Connection c,String tokenHash)throws SQLException {String client=authenticate(c,tokenHash);lock(c,client);if(!client.equals(authenticate(c,tokenHash)))throw unauthorized();return client;}
    public Identity session(String tokenHash) {
        try(var c=connection()){c.setAutoCommit(false);String client=authenticateLocked(c,tokenHash);var result=new Identity(client,tokenHash,remaining(c,client,day()));c.commit();return result;}catch(SQLException ex){throw unavailable(ex);}
    }
    private int remaining(Connection c,String client,String day)throws SQLException {
        int used=0;
        try(var s=c.prepareStatement("SELECT count FROM ct_trial_daily WHERE client_id=? AND quota_day=?")){s.setString(1,client);s.setDate(2,java.sql.Date.valueOf(day));try(var r=s.executeQuery()){if(r.next())used=r.getInt(1);}}
        try(var s=c.prepareStatement("SELECT count(*) FROM ct_trial_requests WHERE client_id=? AND quota_day=? AND state='pending' AND expires_at>?")){s.setString(1,client);s.setDate(2,java.sql.Date.valueOf(day));s.setLong(3,clock.millis());try(var r=s.executeQuery()){r.next();used+=r.getInt(1);}}
        return Math.max(0,limit-used);
    }
    public Reservation reserve(String tokenHash,String nonce,String digest) {
        try(var c=connection()) {
            c.setAutoCommit(false);String client=authenticateLocked(c,tokenHash),today=day();
            try(var s=c.prepareStatement("SELECT facts_hash,state,attempt,quota_day,expires_at FROM ct_trial_requests WHERE client_id=? AND nonce_hash=?")) {
                s.setString(1,client);s.setString(2,nonce);try(var r=s.executeQuery()) {if(r.next()) {
                    if(!r.getString(1).equals(digest))throw new ApiException(409,"REQUEST_ID_CONFLICT","请求编号已用于另一份表达");
                    if(r.getString(2).equals("completed")){var result=new Reservation(client,nonce,r.getString(4),r.getString(3),true,remaining(c,client,today));c.commit();return result;}
                    if(r.getString(2).equals("pending")&&r.getLong(5)>clock.millis())throw new ApiException(409,"REQUEST_IN_PROGRESS","此表达正在生成，请稍后重试");
                }}
            }
            int remaining=remaining(c,client,today);if(remaining==0)throw new ApiException(429,"QUOTA_EXHAUSTED","今日体验次数已用完，明天可继续体验");
            String attempt=UUID.randomUUID().toString();
            try(var s=c.prepareStatement("INSERT INTO ct_trial_requests(client_id,nonce_hash,facts_hash,quota_day,state,attempt,expires_at) VALUES(?,?,?,?,'pending',?,?) ON CONFLICT(client_id,nonce_hash) DO UPDATE SET quota_day=excluded.quota_day,state='pending',attempt=excluded.attempt,expires_at=excluded.expires_at")) {
                s.setString(1,client);s.setString(2,nonce);s.setString(3,digest);s.setDate(4,java.sql.Date.valueOf(today));s.setString(5,attempt);s.setLong(6,clock.millis()+300_000);s.executeUpdate();
            }
            c.commit();return new Reservation(client,nonce,today,attempt,false,remaining-1);
        }catch(SQLException ex){throw unavailable(ex);}
    }
    public int complete(Reservation reservation) {
        try(var c=connection()) {
            c.setAutoCommit(false);lock(c,reservation.clientId());
            boolean completed;
            try(var s=c.prepareStatement("SELECT state,attempt,expires_at FROM ct_trial_requests WHERE client_id=? AND nonce_hash=?")){
                s.setString(1,reservation.clientId());s.setString(2,reservation.nonce());try(var r=s.executeQuery()) {
                    if(!r.next()||!r.getString(2).equals(reservation.attempt())||r.getString(1).equals("failed")||r.getString(1).equals("pending")&&r.getLong(3)<=clock.millis())throw new ApiException(409,"REQUEST_RESERVATION_EXPIRED","表达生成预约已过期，请重试");completed=r.getString(1).equals("completed");
                }
            }
            if(!completed) {
                try(var s=c.prepareStatement("UPDATE ct_trial_requests SET state='completed',expires_at=0 WHERE client_id=? AND nonce_hash=?")){s.setString(1,reservation.clientId());s.setString(2,reservation.nonce());s.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO ct_trial_daily(client_id,quota_day,count) VALUES(?,?,1) ON CONFLICT(client_id,quota_day) DO UPDATE SET count=ct_trial_daily.count+1")){s.setString(1,reservation.clientId());s.setDate(2,java.sql.Date.valueOf(reservation.day()));s.executeUpdate();}
            }
            int remaining=remaining(c,reservation.clientId(),day());c.commit();return remaining;
        }catch(SQLException ex){throw unavailable(ex);}
    }
    public void release(Reservation reservation) {
        try(var c=connection()) {
            c.setAutoCommit(false);lock(c,reservation.clientId());
            try(var s=c.prepareStatement("UPDATE ct_trial_requests SET state='failed',expires_at=0 WHERE client_id=? AND nonce_hash=? AND attempt=? AND state='pending'")){s.setString(1,reservation.clientId());s.setString(2,reservation.nonce());s.setString(3,reservation.attempt());s.executeUpdate();}c.commit();
        }catch(SQLException ex){throw unavailable(ex);}
    }
    private void migrate()throws SQLException,IOException {
        String migration;
        try(var stream=TrialRepository.class.getResourceAsStream("/db/migrations/V006__trial_quota.sql")){if(stream==null)throw new IOException("Missing trial migration");migration=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        try(var c=connection()) {
            c.setAutoCommit(false);lock(c,0x4354545249414cL);
            try(var s=c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS ct_trial_schema_version(version integer PRIMARY KEY,checksum char(64) NOT NULL)");
                try(var r=s.executeQuery("SELECT version,checksum FROM ct_trial_schema_version")){if(r.next()){if(r.getInt(1)!=6||!r.getString(2).equals(hash(migration))||r.next())throw new SQLException("Unsupported trial schema");c.commit();return;}}
                for(String sql:migration.split(";"))if(!sql.isBlank())s.execute(sql);
            }
            try(var s=c.prepareStatement("INSERT INTO ct_trial_schema_version VALUES(6,?)")){s.setString(1,hash(migration));s.executeUpdate();}c.commit();
        }
    }
}
