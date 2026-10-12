package com.cardtalk.content;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service
public class ContentService {
    private final ObjectMapper mapper; private final boolean enabled; private final String url; private final Properties properties=new Properties(); private final Set<String> operators;
    public ContentService(ObjectMapper mapper,@Value("${cardtalk.content-enabled:false}") boolean enabled,@Value("${cardtalk.family-repository-mode:json}") String mode,@Value("${cardtalk.family-jdbc-url:}") String url,@Value("${cardtalk.family-jdbc-user:}") String user,@Value("${cardtalk.family-jdbc-password:}") String password,@Value("${cardtalk.content-operator-account-ids:}") String ids){
        this.mapper=mapper;this.enabled=enabled;this.url=url;operators=new HashSet<>();for(String id:ids.split(","))if(!id.isBlank())operators.add(id.trim());properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");
        if(enabled){if(!mode.equals("postgres")||!url.startsWith("jdbc:postgresql:"))throw new IllegalArgumentException("Public content requires PostgreSQL");try(var c=connection();var in=getClass().getResourceAsStream("/db/migrations/V008__content.sql")){c.setAutoCommit(false);lock(c);String migration=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);String checksum=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(migration.getBytes(StandardCharsets.UTF_8)));try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS ct_content_schema_version(version integer PRIMARY KEY,checksum char(64) NOT NULL)");boolean installed=false;try(var r=s.executeQuery("SELECT version,checksum FROM ct_content_schema_version")){if(r.next()){if(r.getInt(1)!=8||!checksum.equals(r.getString(2))||r.next())throw new SQLException("Unsupported public content schema");installed=true;}}if(!installed){for(String sql:migration.split(";"))if(!sql.isBlank())s.execute(sql);try(var insert=c.prepareStatement("INSERT INTO ct_content_schema_version VALUES(8,?)")){insert.setString(1,checksum);insert.executeUpdate();}}}c.commit();}catch(Exception e){throw new IllegalStateException("Public content migration failed",e);}}
    }
    private Connection connection()throws SQLException{return DriverManager.getConnection(url,properties);}
    private void lock(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("SELECT pg_advisory_xact_lock(740008)");}}
    private void enabled(){if(!enabled)throw new ApiException(503,"CONTENT_DISABLED","公共资源尚未启用");}
    private void operator(String actor){enabled();if(!operators.contains(actor))throw new ApiException(403,"CONTENT_OPERATOR_REQUIRED","没有公共内容发布权限");}
    private static ApiException invalid(){return new ApiException(400,"INVALID_PUBLIC_CONTENT","公共资源格式不正确，禁止图片、私人资料或未知字段");}
    private static ApiException storage(SQLException e){return new ApiException(503,"CONTENT_STORAGE_UNAVAILABLE","公共资源服务暂时不可用",e);}
    private static void keys(JsonNode n,String... names){if(!n.isObject())throw invalid();Set<String> allowed=Set.of(names);n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid();});}
    private static String text(JsonNode n,String key,int max){JsonNode v=n.get(key);if(v==null||!v.isTextual()||v.asText().isBlank()||v.asText().length()>max)throw invalid();return v.asText();}
    private static void list(JsonNode n,int max){if(n==null||!n.isArray()||n.size()>max)throw invalid();}
    private static String id(JsonNode n){String id=text(n,"id",40);if(!id.matches("[a-z0-9][a-z0-9-]{0,39}"))throw invalid();return id;}
    static final Set<String> CATEGORIES=Set.of("people","actions","fruit","food","feelings","transport","daily","words");
    static final Set<String> ICONS=Set.of("afraid","again","angry","apple","ball","banana","bed","bike","book","bread","bus","car","clothes","cold","dad","drink","eat","egg","friend","go","grandma","grandpa","grape","happy","help","home","hot","hungry","i","later","less","listen","look","milk","mom","more","no","noodles","not","now","orange","outside","pain","phone","play","rest","rice","sad","stop","strawberry","teacher","that","thirsty","this","tired","toilet","train","walk","want","water","watermelon","yes","you");
    public static void validate(JsonNode p){
        keys(p,"id","title","source","license","cards","scenes");id(p);text(p,"title",100);text(p,"source",500);text(p,"license",500);list(p.get("cards"),70);list(p.get("scenes"),50);
        Set<String> cards=new HashSet<>(),scenes=new HashSet<>(),cardNames=new HashSet<>(),sceneNames=new HashSet<>();
        for(JsonNode c:p.get("cards")){keys(c,"id","label","category","icon","kind","description","sceneIds");if(!cards.add(id(c))||!cardNames.add(text(c,"label",50).strip().toLowerCase(Locale.ROOT))||!CATEGORIES.contains(text(c,"category",30))||!ICONS.contains(text(c,"icon",100))||!Set.of("word","phrase").contains(text(c,"kind",10)))throw invalid();text(c,"description",500);list(c.get("sceneIds"),50);}
        for(JsonNode s:p.get("scenes")){keys(s,"id","name","description","recommendedCardIds");if(!scenes.add(id(s))||!sceneNames.add(text(s,"name",100).strip().toLowerCase(Locale.ROOT)))throw invalid();text(s,"description",1000);list(s.get("recommendedCardIds"),70);}
        for(JsonNode c:p.get("cards"))references(c.get("sceneIds"),scenes);for(JsonNode s:p.get("scenes"))references(s.get("recommendedCardIds"),cards);
    }
    private static void references(JsonNode values,Set<String> ids){Set<String> seen=new HashSet<>();for(JsonNode value:values)if(!value.isTextual()||!ids.contains(value.asText())||!seen.add(value.asText()))throw invalid();}
    public Object publish(String actor,JsonNode payload){operator(actor);validate(payload);try(var c=connection();var s=c.prepareStatement("INSERT INTO ct_content_versions(id,payload,created_by) VALUES(?,?::jsonb,?) ON CONFLICT DO NOTHING")){s.setString(1,payload.path("id").asText());s.setString(2,payload.toString());s.setString(3,actor);if(s.executeUpdate()!=1)throw new ApiException(409,"CONTENT_VERSION_EXISTS","版本不可修改，请使用新的版本编号");return Map.of("id",payload.path("id").asText());}catch(SQLException e){throw storage(e);}}
    private Object current(Connection c)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery("SELECT r.revision,v.payload::text FROM ct_content_release r LEFT JOIN ct_content_versions v ON v.id=r.version_id WHERE singleton=1")){r.next();var result=mapper.createObjectNode().put("revision",r.getLong(1));try{result.set("package",r.getString(2)==null?mapper.nullNode():mapper.readTree(r.getString(2)));}catch(Exception e){throw new SQLException("Invalid content payload",e);}return result;}}
    public Object current(){enabled();try(var c=connection()){return current(c);}catch(SQLException e){throw storage(e);}}
    public Object release(String actor,String version,long expected){operator(actor);if(version==null||!version.matches("[a-z0-9][a-z0-9-]{0,39}")||expected<0||expected>=9007199254740991L)throw invalid();try(var c=connection()){c.setAutoCommit(false);lock(c);try(var s=c.prepareStatement("SELECT 1 FROM ct_content_versions WHERE id=?")){s.setString(1,version);try(var r=s.executeQuery()){if(!r.next())throw new ApiException(404,"CONTENT_VERSION_NOT_FOUND","资源版本不存在");}}
        try(var s=c.prepareStatement("UPDATE ct_content_release SET version_id=?,revision=revision+1 WHERE singleton=1 AND revision=?")){s.setString(1,version);s.setLong(2,expected);if(s.executeUpdate()!=1)throw new ApiException(409,"CONTENT_RELEASE_CONFLICT","发布状态已变化，请重新读取当前版本");}
        try(var s=c.prepareStatement("INSERT INTO ct_content_audit(revision,version_id,actor_id) VALUES(?,?,?)")){s.setLong(1,expected+1);s.setString(2,version);s.setString(3,actor);s.executeUpdate();}Object result=current(c);c.commit();return result;}catch(SQLException e){throw storage(e);}}
    public Object versions(String actor){operator(actor);try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT id,created_at FROM ct_content_versions ORDER BY created_at DESC LIMIT 100")){var result=new ArrayList<Object>();while(r.next())result.add(Map.of("id",r.getString(1),"createdAt",r.getTimestamp(2).toInstant().toString()));return result;}catch(SQLException e){throw storage(e);}}
    public Object audit(String actor){operator(actor);try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT revision,version_id,actor_id,created_at FROM ct_content_audit ORDER BY revision DESC LIMIT 100")){var result=new ArrayList<Object>();while(r.next())result.add(Map.of("revision",r.getLong(1),"versionId",r.getString(2),"actorId",r.getString(3),"createdAt",r.getTimestamp(4).toInstant().toString()));return result;}catch(SQLException e){throw storage(e);}}
}
