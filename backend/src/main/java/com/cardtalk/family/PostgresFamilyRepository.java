package com.cardtalk.family;

import com.cardtalk.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Normalized PostgreSQL persistence. Writers serialize before reading, so separate instances cannot overwrite stale snapshots. */
@Repository
@ConditionalOnProperty(name="cardtalk.family-repository-mode",havingValue="postgres")
public class PostgresFamilyRepository implements FamilyRepository {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PostgresFamilyRepository.class);
    private static final long WRITE_LOCK=0x4341524454414c4bL;
    private final String url;
    private final Properties properties=new Properties();
    public PostgresFamilyRepository(@Value("${cardtalk.family-jdbc-url:}") String url,
                                    @Value("${cardtalk.family-jdbc-user:}") String user,
                                    @Value("${cardtalk.family-jdbc-password:}") String password) {
        if(!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("PostgreSQL family repository requires a PostgreSQL JDBC URL");
        this.url=url;properties.setProperty("user",user);properties.setProperty("password",password);
        properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");
        properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");
        try { migrate(); } catch(SQLException|IOException ex) { logFailure(ex);throw new IllegalStateException("PostgreSQL family schema initialization failed; no JSON fallback was attempted",ex); }
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url,properties); }
    private static void lock(Connection connection) throws SQLException { try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) { statement.setLong(1,WRITE_LOCK);statement.execute(); } }
    private static String checksum(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    private void migrate() throws SQLException,IOException {
        byte[] migration;
        try(var stream=PostgresFamilyRepository.class.getResourceAsStream("/db/migrations/V001__families.sql")) { if(stream==null) throw new IOException("Missing family migration");migration=stream.readAllBytes(); }
        try(var connection=connection()) {
            connection.setAutoCommit(false);lock(connection);
            try(var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS ct_family_schema_version (version integer PRIMARY KEY, checksum char(64) NOT NULL, applied_at timestamptz NOT NULL DEFAULT current_timestamp)");
                try(var rows=statement.executeQuery("SELECT version,checksum FROM ct_family_schema_version ORDER BY version")) {
                    if(rows.next()) { if(rows.getInt(1)!=1||!rows.getString(2).equals(checksum(migration))||rows.next()) throw new SQLException("Unsupported family schema version");connection.commit();return; }
                }
                for(String sql:new String(migration,StandardCharsets.UTF_8).split(";")) if(!sql.isBlank()) statement.execute(sql);
            }
            try(var statement=connection.prepareStatement("INSERT INTO ct_family_schema_version(version,checksum) VALUES(1,?)")) { statement.setString(1,checksum(migration));statement.executeUpdate(); }
            connection.commit();
        }
    }
    public <T> T read(Function<Data,T> action) {
        try(var connection=connection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setReadOnly(true);
            T result=action.apply(load(connection));connection.commit();return result;
        } catch(SQLException ex) { throw unavailable(ex); }
    }
    public <T> T transact(Function<Data,T> action) {
        try(var connection=connection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);lock(connection);
            Data next=load(connection);List<Table> before=tables(next);T result=action.apply(next);
            if(next.version()!=1) throw new IllegalArgumentException("Unsupported family data version");
            List<Table> after=tables(next);
            // Children disappear first, then parents are inserted/updated before children.
            for(int i=before.size()-1;i>=0;i--) prune(connection,before.get(i),after.get(i));
            for(int i=0;i<after.size();i++) upsert(connection,before.get(i),after.get(i));
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id FROM ct_families f WHERE NOT EXISTS (SELECT 1 FROM ct_family_members m WHERE m.family_id=f.id AND m.role='owner') LIMIT 1")) {
                if(rows.next()) throw new SQLException("Family needs an owner","23514");
            }
            connection.commit();return result;
        } catch(SQLException ex) { throw unavailable(ex); }
    }
    private static void logFailure(Exception ex) { LOG.error("PostgreSQL family storage failed (SQL state: {})",ex instanceof SQLException sql?sql.getSQLState():"resource"); }
    private static ApiException unavailable(SQLException ex) { logFailure(ex);return new ApiException(503,"FAMILY_STORAGE_UNAVAILABLE","家庭数据暂时无法访问或保存，请重试",ex); }
    @FunctionalInterface private interface RowReader { void accept(ResultSet row) throws SQLException; }
    private static void query(Connection connection,String sql,RowReader reader) throws SQLException { try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)) { while(rows.next()) reader.accept(rows); } }
    private Data load(Connection connection) throws SQLException {
        Data data=Data.empty();
        Map<List<String>,List<String>> scopes=new HashMap<>();Map<String,List<String>> inviteScopes=new HashMap<>();
        query(connection,"SELECT family_id,account_id,profile_id FROM ct_family_profile_access ORDER BY profile_id",r->scopes.computeIfAbsent(List.of(r.getString(1),r.getString(2)),x->new ArrayList<>()).add(r.getString(3)));
        query(connection,"SELECT token_hash,profile_id FROM ct_family_invitation_access ORDER BY profile_id",r->inviteScopes.computeIfAbsent(r.getString(1),x->new ArrayList<>()).add(r.getString(2)));
        query(connection,"SELECT id,username,salt,password_hash FROM ct_family_accounts ORDER BY id",r->data.accounts().add(new Account(r.getString(1),r.getString(2),r.getString(3),r.getString(4))));
        query(connection,"SELECT account_id,token_hash,expires_at FROM ct_family_sessions ORDER BY expires_at,token_hash",r->data.sessions().add(new Session(r.getString(1),r.getString(2),r.getLong(3))));
        query(connection,"SELECT id,name FROM ct_families ORDER BY id",r->data.families().add(new Family(r.getString(1),r.getString(2))));
        query(connection,"SELECT family_id,account_id,role FROM ct_family_members ORDER BY family_id,account_id",r->data.members().add(new Member(r.getString(1),r.getString(2),r.getString(3),scopes.getOrDefault(List.of(r.getString(1),r.getString(2)),List.of()))));
        query(connection,"SELECT family_id,issuer_id,token_hash,role,expires_at FROM ct_family_invitations ORDER BY token_hash",r->data.invitations().add(new Invitation(r.getString(1),r.getString(2),r.getString(3),r.getString(4),inviteScopes.getOrDefault(r.getString(3),List.of()),r.getLong(5))));
        return data;
    }
    private record Table(String name,List<String> columns,int keyColumns,Map<List<Object>,List<Object>> rows) {}
    private static Table table(String name,String columns,int keys) { return new Table(name,List.of(columns.split(",")),keys,new LinkedHashMap<>()); }
    private static void row(Table table,Object... values) { var row=List.of(values);if(table.rows().put(List.copyOf(row.subList(0,table.keyColumns())),row)!=null) throw new IllegalArgumentException("Duplicate family record identifier"); }
    private static List<Table> tables(Data data) {
        Table accounts=table("ct_family_accounts","id,username,salt,password_hash",1),families=table("ct_families","id,name",1),members=table("ct_family_members","family_id,account_id,role",2),sessions=table("ct_family_sessions","token_hash,account_id,expires_at",1),invitations=table("ct_family_invitations","token_hash,family_id,issuer_id,role,expires_at",1),access=table("ct_family_profile_access","family_id,account_id,profile_id",3),invitationAccess=table("ct_family_invitation_access","token_hash,profile_id",2);
        data.accounts().forEach(a->row(accounts,a.id(),a.username(),a.salt(),a.passwordHash()));data.families().forEach(f->row(families,f.id(),f.name()));
        data.members().forEach(m->{row(members,m.familyId(),m.accountId(),m.role());m.profileAccess().forEach(p->row(access,m.familyId(),m.accountId(),p));});
        data.sessions().forEach(s->row(sessions,s.tokenHash(),s.accountId(),s.expiresAt()));
        data.invitations().forEach(i->{row(invitations,i.tokenHash(),i.familyId(),i.issuerId(),i.role(),i.expiresAt());i.profileAccess().forEach(p->row(invitationAccess,i.tokenHash(),p));});
        return List.of(accounts,families,members,sessions,invitations,access,invitationAccess);
    }
    private static void bind(PreparedStatement statement,List<Object> values) throws SQLException { for(int i=0;i<values.size();i++) statement.setObject(i+1,values.get(i)); }
    private static void prune(Connection connection,Table before,Table after) throws SQLException {
        String where=String.join(" AND ",before.columns().subList(0,before.keyColumns()).stream().map(c->c+"=?").toList());
        try(var statement=connection.prepareStatement("DELETE FROM "+before.name()+" WHERE "+where)) {
            for(var key:before.rows().keySet()) if(!after.rows().containsKey(key)) { bind(statement,key);statement.addBatch(); }statement.executeBatch();
        }
    }
    private static void upsert(Connection connection,Table before,Table after) throws SQLException {
        List<String> updated=after.columns().subList(after.keyColumns(),after.columns().size());
        String conflict=updated.isEmpty()?"DO NOTHING":"DO UPDATE SET "+String.join(",",updated.stream().map(c->c+"=EXCLUDED."+c).toList());
        String sql="INSERT INTO "+after.name()+" ("+String.join(",",after.columns())+") VALUES ("+String.join(",",Collections.nCopies(after.columns().size(),"?"))+") ON CONFLICT ("+String.join(",",after.columns().subList(0,after.keyColumns()))+") "+conflict;
        try(var statement=connection.prepareStatement(sql)) { for(var entry:after.rows().entrySet()) if(!entry.getValue().equals(before.rows().get(entry.getKey()))) { bind(statement,entry.getValue());statement.addBatch(); }statement.executeBatch(); }
    }
}
