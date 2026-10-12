package com.cardtalk.assets;

import com.cardtalk.family.PostgresFamilyRepository;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
@ConditionalOnProperty(name="cardtalk.asset-metadata-mode",havingValue="postgres")
public class PostgresAssetMetadataRepository implements AssetMetadataRepository {
    private final String url;
    private final Properties properties=new Properties();
    @Autowired
    public PostgresAssetMetadataRepository(PostgresFamilyRepository initializedFamilyRepository,@Value("${cardtalk.family-jdbc-url}") String url,@Value("${cardtalk.family-jdbc-user}") String user,@Value("${cardtalk.family-jdbc-password}") String password) throws IOException { this(url,user,password); }
    public PostgresAssetMetadataRepository(String url,String user,String password) throws IOException {
        if(!url.startsWith("jdbc:postgresql:")) throw new IOException("Postgres asset metadata needs PostgreSQL configuration");
        this.url=url;properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","20");properties.setProperty("options","-c statement_timeout=10000 -c lock_timeout=5000");migrate();
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url,properties); }
    private void migrate() throws IOException {
        byte[] bytes;
        try(var stream=getClass().getResourceAsStream("/db/migrations/V003__asset_metadata.sql")) { if(stream==null) throw new IOException("Missing asset migration");bytes=stream.readAllBytes(); }
        try(var connection=connect()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(4846257241143790668)");
                statement.execute("CREATE TABLE IF NOT EXISTS ct_asset_schema_version (version integer PRIMARY KEY, checksum char(64) NOT NULL)");
                try(var rows=statement.executeQuery("SELECT version,checksum FROM ct_asset_schema_version ORDER BY version")) {
                    if(rows.next()) { if(rows.getInt(1)!=3||!rows.getString(2).equals(AssetCatalog.sha256(bytes))||rows.next()) throw new SQLException("Unsupported asset migration");connection.commit();return; }
                }
                // PostgreSQL executes this controlled resource as a complete script, including the trigger body.
                statement.execute(new String(bytes,StandardCharsets.UTF_8));
            }
            try(var statement=connection.prepareStatement("INSERT INTO ct_asset_schema_version VALUES (3,?)")) { statement.setString(1,AssetCatalog.sha256(bytes));statement.executeUpdate(); }
            connection.commit();
        } catch(SQLException failure) { throw new IOException("PostgreSQL asset migration failed; no local fallback",failure); }
    }
    private static AssetCatalog.Metadata metadata(ResultSet row) throws SQLException { return new AssetCatalog.Metadata(row.getString("id"),row.getString("family_id"),row.getString("profile_id"),row.getString("media_type"),row.getLong("bytes"),row.getInt("width"),row.getInt("height"),row.getString("sha256"),row.getObject("created_at",OffsetDateTime.class).toInstant().toString()); }
    public AssetCatalog.Metadata find(String id) throws IOException {
        UUID parsed;try { parsed=UUID.fromString(id); } catch(IllegalArgumentException failure) { return null; }
        try(var connection=connect();var statement=connection.prepareStatement("SELECT * FROM ct_asset_metadata WHERE id=?")) { statement.setObject(1,parsed);try(var rows=statement.executeQuery()) { return rows.next()?metadata(rows):null; } }
        catch(SQLException failure) { throw new IOException("Asset metadata lookup failed",failure); }
    }
    public List<AssetCatalog.Metadata> list() throws IOException {
        try(var connection=connect();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM ct_asset_metadata ORDER BY id")) { List<AssetCatalog.Metadata> entries=new ArrayList<>();while(rows.next()) entries.add(metadata(rows));return List.copyOf(entries); }
        catch(SQLException failure) { throw new IOException("Asset metadata listing failed",failure); }
    }
    public void publish(AssetCatalog.Metadata metadata) throws IOException {
        AssetCatalog.validate(metadata);boolean committing=false;
        try(var connection=connect()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO ct_asset_metadata(id,family_id,profile_id,media_type,bytes,width,height,sha256,created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                statement.setObject(1,UUID.fromString(metadata.id()));statement.setString(2,metadata.familyId());statement.setString(3,metadata.profileId());statement.setString(4,metadata.mediaType());statement.setLong(5,metadata.bytes());statement.setInt(6,metadata.width());statement.setInt(7,metadata.height());statement.setString(8,metadata.sha256());statement.setObject(9,OffsetDateTime.ofInstant(Instant.parse(metadata.createdAt()),ZoneOffset.UTC));statement.executeUpdate();
            }
            committing=true;connection.commit();
        } catch(SQLException failure) { if(committing) throw new PublicationUncertain(failure);throw new IOException("Asset metadata publication failed",failure); }
    }
}
