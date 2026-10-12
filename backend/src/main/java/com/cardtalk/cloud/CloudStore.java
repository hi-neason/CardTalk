package com.cardtalk.cloud;

import com.cardtalk.api.ApiException;
import com.cardtalk.assets.AssetService;
import com.cardtalk.billing.BillingService;
import com.cardtalk.billing.ProductsConfig.Feature;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import static com.cardtalk.cloud.CloudDtos.*;

@Service
public class CloudStore {
    private static final long WRITE_LOCK=0x4341524454414c4bL;
    private final boolean configured,enabled,billingEnforced;
    private final BillingService billing;
    private final String url;
    private final Properties properties=new Properties();
    private final ObjectMapper mapper;
    private final AssetService assets;
    public CloudStore(FamilyRepository families,ObjectMapper mapper,AssetService assets,BillingService billing,
        @Value("${cardtalk.billing-enforced:${CARDTALK_BILLING_ENFORCED:false}}") boolean billingEnforced,
        @Value("${cardtalk.cloud-enabled:false}") boolean enabled,@Value("${cardtalk.family-repository-mode:json}") String mode,
        @Value("${cardtalk.family-jdbc-url:}") String url,@Value("${cardtalk.family-jdbc-user:}") String user,@Value("${cardtalk.family-jdbc-password:}") String password) {
        this.mapper=mapper;this.assets=assets;this.billing=billing;this.billingEnforced=billingEnforced;this.enabled=enabled;this.configured=mode.equals("postgres")&&url.startsWith("jdbc:postgresql:");this.url=url;
        if(billingEnforced&&(!configured||billing==null))throw new IllegalStateException("Enforced family entitlements require PostgreSQL billing storage");
        properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");
        if(enabled&&configured) migrate();
    }
    public boolean configured(){return configured;}
    public boolean enabled(){return enabled&&configured;}
    public boolean billingEnforced(){return billingEnforced;}
    public boolean assetReferencesSupported(){return assets!=null;}
    private void requireEnabled(){if(!enabled())throw new ApiException(503,"CLOUD_NOT_CONFIGURED","云端同步尚未配置或开启，本机资料仍然可用");}
    private Connection connection() throws SQLException {return DriverManager.getConnection(url,properties);}
    private static void lock(Connection c)throws SQLException {try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(?)")){s.setLong(1,WRITE_LOCK);s.execute();}}
    private static ApiException denied(){return new ApiException(403,"FAMILY_ACCESS_DENIED","没有当前家庭或使用人的同步权限");}
    private static ApiException unavailable(Exception cause){org.slf4j.LoggerFactory.getLogger(CloudStore.class).error("Cloud storage failure: type={}, SQLState={}",cause.getClass().getSimpleName(),cause instanceof SQLException sql?sql.getSQLState():"not-sql");return new ApiException(503,"CLOUD_STORAGE_UNAVAILABLE","云端资料暂时无法访问，请保留本机操作后重试",cause);}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void migrate(){
        try(var stream=CloudStore.class.getResourceAsStream("/db/migrations/V002__cloud.sql");var c=connection()){
            if(stream==null)throw new IllegalStateException("Missing cloud migration");String sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);c.setAutoCommit(false);lock(c);
            try(var s=c.createStatement()){
                s.execute("CREATE TABLE IF NOT EXISTS ct_cloud_schema_version(version integer PRIMARY KEY,checksum char(64) NOT NULL)");
                try(var r=s.executeQuery("SELECT version,checksum FROM ct_cloud_schema_version")){if(r.next()){if(r.getInt(1)!=2||!r.getString(2).equals(sha(sql))||r.next())throw new IllegalStateException("Unsupported cloud schema");c.commit();return;}}
                for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);
            }
            try(var s=c.prepareStatement("INSERT INTO ct_cloud_schema_version VALUES(2,?)")){s.setString(1,sha(sql));s.executeUpdate();}c.commit();
        }catch(Exception e){throw new IllegalStateException("Cloud schema initialization failed; no local fallback",e);}
    }
    private record Access(String role,Set<String> profiles){boolean reads(String scope){return !role.equals("helper")||scope!=null&&profiles.contains(scope);}}
    private Access access(Connection c,String family,String actor,boolean write)throws SQLException{
        String role;try(var s=c.prepareStatement("SELECT role FROM ct_family_members WHERE family_id=? AND account_id=?")){s.setString(1,family);s.setString(2,actor);try(var r=s.executeQuery()){if(!r.next())throw denied();role=r.getString(1);}}
        if(write&&role.equals("helper"))throw denied();Set<String> profiles=new HashSet<>();
        if(role.equals("helper"))try(var s=c.prepareStatement("SELECT profile_id FROM ct_family_profile_access WHERE family_id=? AND account_id=?")){s.setString(1,family);s.setString(2,actor);try(var r=s.executeQuery()){while(r.next())profiles.add(r.getString(1));}}
        return new Access(role,profiles);
    }
    private Entity read(ResultSet r)throws SQLException{try{return new Entity(r.getString("entity_type"),r.getString("entity_id"),r.getLong("revision"),r.getString("profile_id"),r.getBoolean("tombstone"),r.getString("payload")==null?null:mapper.readTree(r.getString("payload")));}catch(Exception e){throw unavailable(e);}}
    private Entity entity(Connection c,String family,Operation op)throws SQLException{
        try(var s=c.prepareStatement("SELECT * FROM ct_cloud_entities WHERE family_id=? AND entity_type=? AND entity_id=?")){s.setString(1,family);s.setString(2,op.entityType());s.setString(3,op.entityId());try(var r=s.executeQuery()){return r.next()?read(r):null;}}
    }
    private JsonNode canonical(JsonNode value){if(value.isObject()){var n=mapper.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)n.set(key,canonical(value.get(key)));return n;}if(value.isArray()){var n=mapper.createArrayNode();value.forEach(v->n.add(canonical(v)));return n;}return value;}
    private void references(JsonNode node,FamilyService.Identity actor,String family,String scope){
        if(node.isObject())node.fields().forEachRemaining(e->{if(e.getKey().equals("assetRef")){if(assets==null)throw new ApiException(503,"ASSET_SYNC_UNAVAILABLE","当前同步尚未开放图片资源绑定");var meta=assets.metadata(actor,family,CloudPayload.id(e.getValue().get("id")));if(!Objects.equals(family,meta.familyId())||!Objects.equals(scope,meta.profileId()))throw denied();}else references(e.getValue(),actor,family,scope);});else if(node.isArray())node.forEach(n->references(n,actor,family,scope));
    }
    public PushResult push(FamilyService.Identity actor,String family,Push input){
        requireEnabled();CloudPayload.require(family!=null&&family.matches("[A-Za-z0-9_-]{1,100}")&&input!=null&&input.consent()!=null&&input.consent().photos()!=null&&input.consent().observations()!=null&&input.operations()!=null&&!input.operations().isEmpty()&&input.operations().size()<=50);
        try(var c=connection()){
            c.setAutoCommit(false);lock(c);Access access=access(c,family,actor.id(),true);var results=new ArrayList<Acknowledgement>();long cursor=0;Set<String> ids=new HashSet<>();
            for(Operation op:input.operations()){
                CloudPayload.require(op!=null&&op.operationId()!=null&&op.operationId().matches("[A-Za-z0-9_-]{1,100}")&&ids.add(op.operationId())&&op.entityId()!=null&&op.entityId().matches("[A-Za-z0-9_-]{1,100}")&&Set.of("profile","card","scene","room","report").contains(op.entityType()==null?"":op.entityType())&&op.baseRevision()!=null&&op.baseRevision()>=0&&op.deleted()!=null);
                String digest=sha(canonical(mapper.valueToTree(Map.of("operation",op,"consent",input.consent()))).toString());
                try(var s=c.prepareStatement("SELECT actor_id,request_sha256,revision FROM ct_cloud_operations WHERE family_id=? AND operation_id=?")){
                    s.setString(1,family);s.setString(2,op.operationId());try(var r=s.executeQuery()){if(r.next()){
                        if(!r.getString(1).equals(actor.id())||!r.getString(2).equals(digest))throw new ApiException(409,"OPERATION_REUSED","操作编号已使用，请重新检查操作来源");long rev=r.getLong(3);results.add(new Acknowledgement(op.operationId(),op.entityType(),op.entityId(),rev));cursor=Math.max(cursor,rev);continue;
                    }}
                }
                if(billingEnforced){Feature feature=switch(op.entityType()){case "profile"->Feature.profiles;case "scene"->Feature.scenarios;case "report"->Feature.analytics;default->null;};if(feature!=null)billing.requireAdvancedWrite(c,actor,family,feature);}
                Entity old=entity(c,family,op);if(old!=null&&!access.reads(old.profileId()))throw denied();
                if(op.baseRevision()!=(old==null?0:old.revision()))throw new CloudConflict(op,old);
                String scope;
                if(op.deleted()){CloudPayload.require(op.payload()==null||op.payload().isNull());CloudPayload.require(old!=null);scope=old.profileId();}
                else{scope=CloudPayload.validate(op.entityType(),op.entityId(),op.payload(),input.consent());references(op.payload(),actor,family,scope==null?op.entityId():scope);}
                long revision;try(var s=c.createStatement();var r=s.executeQuery("SELECT nextval('ct_cloud_revision_seq')")){r.next();revision=r.getLong(1);}
                try(var s=c.prepareStatement("INSERT INTO ct_cloud_entities(family_id,entity_type,entity_id,revision,profile_id,tombstone,payload) VALUES(?,?,?,?,?,?,?::jsonb) ON CONFLICT(family_id,entity_type,entity_id) DO UPDATE SET revision=EXCLUDED.revision,profile_id=EXCLUDED.profile_id,tombstone=EXCLUDED.tombstone,payload=EXCLUDED.payload")){
                    s.setString(1,family);s.setString(2,op.entityType());s.setString(3,op.entityId());s.setLong(4,revision);s.setString(5,scope);s.setBoolean(6,op.deleted());s.setString(7,op.deleted()?null:op.payload().toString());s.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO ct_cloud_operations VALUES(?,?,?,?,?)")){s.setString(1,family);s.setString(2,op.operationId());s.setString(3,actor.id());s.setString(4,digest);s.setLong(5,revision);s.executeUpdate();}
                results.add(new Acknowledgement(op.operationId(),op.entityType(),op.entityId(),revision));cursor=Math.max(cursor,revision);
            }
            c.commit();return new PushResult(List.copyOf(results),Long.toString(cursor));
        }catch(SQLException e){throw unavailable(e);}
    }
    public PullResult pull(FamilyService.Identity actor,String family,String cursor,int limit){
        requireEnabled();long after;try{if(cursor==null||!cursor.matches("[0-9]{1,19}"))throw new IllegalArgumentException();after=Long.parseLong(cursor);}catch(Exception e){throw CloudPayload.invalid();}CloudPayload.require(limit>=1&&limit<=100);
        try(var c=connection()){
            c.setAutoCommit(false);lock(c);Access access=access(c,family,actor.id(),false);var results=new ArrayList<Entity>();long next=after;boolean more=false;
            // Filter permissions in SQL and page before materializing payloads.
            try(var s=c.prepareStatement("SELECT * FROM ct_cloud_entities WHERE family_id=? AND revision>? AND (?=false OR profile_id=ANY(?)) ORDER BY revision LIMIT ?")){
                s.setString(1,family);s.setLong(2,after);s.setBoolean(3,access.role().equals("helper"));s.setArray(4,c.createArrayOf("varchar",access.profiles().toArray(String[]::new)));s.setInt(5,limit+1);
                try(var r=s.executeQuery()){while(r.next()){if(results.size()==limit){more=true;break;}results.add(read(r));next=r.getLong("revision");}}
            }
            if(!more)try(var s=c.prepareStatement("SELECT coalesce(max(revision),0) FROM ct_cloud_entities WHERE family_id=?")){s.setString(1,family);try(var r=s.executeQuery()){r.next();next=Math.max(next,r.getLong(1));}}
            c.commit();return new PullResult(List.copyOf(results),Long.toString(next),more);
        }catch(SQLException e){throw unavailable(e);}
    }
}
