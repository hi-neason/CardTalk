package com.cardtalk.operations;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.cardtalk.operations.SupportContracts.*;

@Service
public class SupportService {
    private final boolean enabled;
    private final String url;
    private final Properties properties=new Properties();
    private final Set<String> operators;
    private final int dailyLimit,retentionDays;
    private final ObjectMapper mapper;
    private static final long LOCK=0x435453555050L;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SupportService.class);
    public SupportService(ObjectMapper mapper,@Value("${cardtalk.support-enabled:false}") boolean enabled,
        @Value("${cardtalk.family-repository-mode:json}") String mode,@Value("${cardtalk.family-jdbc-url:}") String url,
        @Value("${cardtalk.family-jdbc-user:}") String user,@Value("${cardtalk.family-jdbc-password:}") String password,
        @Value("${cardtalk.support-operator-account-ids:}") String operatorIds,@Value("${cardtalk.support-daily-limit:5}") int dailyLimit,
        @Value("${cardtalk.support-retention-days:90}") int retentionDays) {
        this.mapper=mapper;this.enabled=enabled;this.url=url;this.dailyLimit=dailyLimit;this.retentionDays=retentionDays;
        if(dailyLimit<1||dailyLimit>100||retentionDays<1||retentionDays>3650)throw new IllegalArgumentException("Invalid support limits");
        operators=new HashSet<>();for(String id:operatorIds.split(","))if(!id.isBlank())operators.add(id.trim());
        properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");
        if(enabled){if(!mode.equals("postgres")||!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("Support requires PostgreSQL; no local fallback");try{migrate();}catch(SQLException|IOException ex){throw new IllegalStateException("Support migration failed",ex);}}
    }
    public void requireEnabled(){if(!enabled)throw new ApiException(503,"SUPPORT_DISABLED","支持服务尚未启用，当前没有提交任何内容");}
    public Map<String,Object> status(){requireEnabled();return Map.of("enabled",true,"dailyLimit",dailyLimit,"retentionDays",retentionDays,"privacyDeletionAutomatic",false);}
    public boolean canOperate(Actor actor){return actor.accountId()!=null&&operators.contains(actor.accountId());}
    private void operator(Actor actor){if(actor.accountId()==null||!operators.contains(actor.accountId()))throw new ApiException(403,"SUPPORT_OPERATOR_REQUIRED","没有运营处理权限");}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private String digest(Object value){try{return hash(mapper.writeValueAsString(value));}catch(IOException ex){throw new ApiException(400,"INVALID_SUPPORT_REQUEST","申请无法读取",ex);}}
    private static String clean(String value){return value==null?"":value.strip();}
    private Connection connection()throws SQLException{return DriverManager.getConnection(url,properties);}
    private void lock(Connection c)throws SQLException{try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")){s.setLong(1,LOCK);s.execute();}}
    private ApiException unavailable(SQLException ex){LOG.error("Support storage failed (SQL state: {})",ex.getSQLState());return new ApiException(503,"SUPPORT_STORAGE_UNAVAILABLE","支持服务暂时不可用，请重试",ex);}
    private static ApiException conflict(){return new ApiException(409,"SUPPORT_REQUEST_CONFLICT","请求编号已用于其他内容");}
    private static ApiException notFound(){return new ApiException(404,"SUPPORT_NOT_FOUND","申请不存在或不可访问");}
    private static Ticket ticket(ResultSet r)throws SQLException{return new Ticket(r.getString("id"),r.getString("kind"),r.getString("message"),r.getString("contact"),r.getString("family_id"),r.getString("status"),r.getString("reply"),r.getString("deletion_evidence"),r.getTimestamp("created_at").toInstant().toString(),r.getTimestamp("updated_at").toInstant().toString(),r.getTimestamp("retain_until")==null?null:r.getTimestamp("retain_until").toInstant().toString());}
    private Ticket get(Connection c,String id,String owner)throws SQLException{
        try(var s=c.prepareStatement("SELECT * FROM ct_support_tickets WHERE id=?"+(owner==null?"":" AND owner_hash=?"))){s.setString(1,id);if(owner!=null)s.setString(2,owner);try(var r=s.executeQuery()){if(!r.next())throw notFound();return ticket(r);}}
    }
    public Ticket create(Actor actor,Create input){
        requireEnabled();String body=digest(input),request=hash(input.requestId());
        try(var c=connection()){
            c.setAutoCommit(false);lock(c);
            try(var s=c.prepareStatement("SELECT * FROM ct_support_tickets WHERE owner_hash=? AND request_hash=?")){s.setString(1,actor.key());s.setString(2,request);try(var r=s.executeQuery()){if(r.next()){if(!body.equals(r.getString("payload_hash")))throw conflict();var result=ticket(r);c.commit();return result;}}}
            Instant now=Instant.now(),midnight=LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            try(var s=c.prepareStatement("SELECT count(*) FROM ct_support_tickets WHERE owner_hash=? AND created_at>=?")){s.setString(1,actor.key());s.setTimestamp(2,Timestamp.from(midnight));try(var r=s.executeQuery()){r.next();if(r.getInt(1)>=dailyLimit)throw new ApiException(429,"SUPPORT_DAILY_LIMIT","今日提交次数已达上限，请稍后再试");}}
            String id=UUID.randomUUID().toString();
            try(var s=c.prepareStatement("INSERT INTO ct_support_tickets(id,owner_hash,request_hash,payload_hash,kind,message,contact,family_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'submitted',?,?)")){
                s.setString(1,id);s.setString(2,actor.key());s.setString(3,request);s.setString(4,body);s.setString(5,input.kind());s.setString(6,input.message().strip());s.setString(7,clean(input.contact()));s.setString(8,input.familyId());s.setTimestamp(9,Timestamp.from(now));s.setTimestamp(10,Timestamp.from(now));s.executeUpdate();}
            audit(c,id,actor.key(),"submitted",null,null,"","",now);var result=get(c,id,actor.key());c.commit();return result;
        }catch(SQLException ex){throw unavailable(ex);}
    }
    public Page list(Actor actor,boolean operations,long cursor,int limit){
        requireEnabled();if(operations)operator(actor);if(cursor<0||limit<1||limit>50)throw new ApiException(400,"INVALID_SUPPORT_CURSOR","分页参数不正确");
        try(var c=connection();var s=c.prepareStatement("SELECT * FROM ct_support_tickets WHERE seq<?"+(operations?"":" AND owner_hash=?")+" ORDER BY seq DESC LIMIT ?")){
            s.setLong(1,cursor==0?Long.MAX_VALUE:cursor);int index=2;if(!operations)s.setString(index++,actor.key());s.setInt(index,limit+1);var items=new ArrayList<Ticket>();long next=0;boolean more=false;
            try(var r=s.executeQuery()){while(r.next()){if(items.size()==limit){more=true;break;}items.add(ticket(r));next=r.getLong("seq");}}
            return new Page(items,Long.toString(next),more);
        }catch(SQLException ex){throw unavailable(ex);}
    }
    public Ticket withdraw(Actor actor,String id){
        requireEnabled();try(var c=connection()){
            c.setAutoCommit(false);lock(c);var before=get(c,id,actor.key());if(before.status().equals("withdrawn")){c.commit();return before;}if(!before.status().equals("submitted"))throw new ApiException(409,"SUPPORT_ALREADY_PROCESSING","申请已开始处理，不能撤回");
            Instant now=Instant.now();change(c,id,"withdrawn","","",now);audit(c,id,actor.key(),"withdrawn",null,null,"","",now);var result=get(c,id,actor.key());c.commit();return result;
        }catch(SQLException ex){throw unavailable(ex);}
    }
    public Ticket update(Actor actor,String id,Update input){
        requireEnabled();operator(actor);String body=digest(input),nonce=hash(input.requestId());
        try(var c=connection()){
            c.setAutoCommit(false);lock(c);var before=get(c,id,null);
            try(var s=c.prepareStatement("SELECT payload_hash FROM ct_support_audit WHERE ticket_id=? AND actor_hash=? AND request_hash=?")){s.setString(1,id);s.setString(2,actor.key());s.setString(3,nonce);try(var r=s.executeQuery()){if(r.next()){if(!body.equals(r.getString(1)))throw conflict();c.commit();return before;}}}
            if(!Set.of("submitted","in_progress").contains(before.status()))throw new ApiException(409,"SUPPORT_CLOSED","申请已关闭，不能继续变更");
            if(before.kind().equals("privacy_deletion")&&input.status().equals("resolved")&&clean(input.deletionEvidence()).isEmpty())throw new ApiException(400,"DELETION_EVIDENCE_REQUIRED","隐私删除申请需记录独立核验证据才能关闭，状态本身不表示已删除数据");
            Instant now=Instant.now();change(c,id,input.status(),input.reply().strip(),clean(input.deletionEvidence()),now);audit(c,id,actor.key(),input.status(),nonce,body,input.reply().strip(),clean(input.deletionEvidence()),now);var result=get(c,id,null);c.commit();return result;
        }catch(SQLException ex){throw unavailable(ex);}
    }
    private void change(Connection c,String id,String status,String reply,String evidence,Instant now)throws SQLException{
        try(var s=c.prepareStatement("UPDATE ct_support_tickets SET status=?,reply=?,deletion_evidence=?,updated_at=?,retain_until=? WHERE id=?")){s.setString(1,status);s.setString(2,reply);s.setString(3,evidence);s.setTimestamp(4,Timestamp.from(now));s.setTimestamp(5,Set.of("resolved","rejected","withdrawn").contains(status)?Timestamp.from(now.plus(Duration.ofDays(retentionDays))):null);s.setString(6,id);s.executeUpdate();}
    }
    private void audit(Connection c,String id,String actor,String action,String nonce,String digest,String reply,String evidence,Instant now)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO ct_support_audit(ticket_id,actor_hash,action,request_hash,payload_hash,reply,deletion_evidence,created_at) VALUES(?,?,?,?,?,?,?,?)")){s.setString(1,id);s.setString(2,actor);s.setString(3,action);s.setString(4,nonce);s.setString(5,digest);s.setString(6,reply);s.setString(7,evidence);s.setTimestamp(8,Timestamp.from(now));s.executeUpdate();}
    }
    public List<Audit> audits(Actor actor,String id){
        requireEnabled();operator(actor);try(var c=connection()){get(c,id,null);try(var s=c.prepareStatement("SELECT * FROM ct_support_audit WHERE ticket_id=? ORDER BY seq")){s.setString(1,id);var result=new ArrayList<Audit>();try(var r=s.executeQuery()){while(r.next())result.add(new Audit(r.getString("seq"),r.getString("actor_hash"),r.getString("action"),r.getString("reply"),r.getString("deletion_evidence"),r.getTimestamp("created_at").toInstant().toString()));}return result;}}catch(SQLException ex){throw unavailable(ex);}
    }
    public int purge(Actor actor){
        requireEnabled();operator(actor);try(var c=connection()){c.setAutoCommit(false);lock(c);int count;try(var s=c.prepareStatement("DELETE FROM ct_support_tickets WHERE retain_until IS NOT NULL AND retain_until<clock_timestamp() AND status IN ('resolved','rejected','withdrawn')")){count=s.executeUpdate();}try(var s=c.prepareStatement("INSERT INTO ct_support_maintenance_audit(actor_hash,removed_count,created_at) VALUES(?,?,clock_timestamp())")){s.setString(1,actor.key());s.setInt(2,count);s.executeUpdate();}c.commit();return count;}catch(SQLException ex){throw unavailable(ex);}
    }
    private void migrate()throws SQLException,IOException{
        String migration;try(var in=SupportService.class.getResourceAsStream("/db/migrations/V007__support.sql")){if(in==null)throw new IOException("Missing support migration");migration=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        try(var c=connection()){c.setAutoCommit(false);lock(c);try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS ct_support_schema_version(version integer PRIMARY KEY,checksum char(64) NOT NULL)");try(var r=s.executeQuery("SELECT version,checksum FROM ct_support_schema_version")){if(r.next()){if(r.getInt(1)!=7||!r.getString(2).equals(hash(migration))||r.next())throw new SQLException("Unsupported support schema");c.commit();return;}}
            for(String sql:migration.split(";"))if(!sql.isBlank())s.execute(sql);
        }try(var s=c.prepareStatement("INSERT INTO ct_support_schema_version VALUES(7,?)")){s.setString(1,hash(migration));s.executeUpdate();}c.commit();}
    }
}
