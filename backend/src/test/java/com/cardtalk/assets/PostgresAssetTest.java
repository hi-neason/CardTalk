package com.cardtalk.assets;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresAssetTest {
    String base,url,user,password,schema,family;PostgresAssetMetadataRepository metadata;MemoryObjects objects;
    static class MemoryObjects implements AssetStore {
        final Map<String,byte[]> bytes=new ConcurrentHashMap<>();final Set<String> pending=ConcurrentHashMap.newKeySet();int cleanups;
        public void put(String id,byte[] value) throws IOException { if(bytes.putIfAbsent(id,value.clone())!=null) throw new IOException("Immutable object");pending.add(id); }
        public byte[] read(String id) throws IOException { byte[] value=bytes.get(id);if(value==null) throw new IOException("Missing object");return value.clone(); }
        public void markPublished(String id) { pending.remove(id); }
        public void discardUncommitted(String id) throws IOException { if(!pending.remove(id)) throw new IOException("Not owned");bytes.remove(id); }
        public void cleanupUncommitted(Set<String> ids) { cleanups++;throw new IllegalStateException("Shared objects cannot be startup-cleaned"); }
    }
    @BeforeEach void setup() throws Exception {
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL asset tests require CARDTALK_TEST_PG_URL/USER/PASSWORD");
        user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_asset_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()) { s.execute("CREATE SCHEMA "+schema); }
        url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
        var families=new FamilyService(new PostgresFamilyRepository(url,user,password),true,3600);var owner=families.register("asset_owner","test-password-123","fixture");family=families.createFamily(owner.account(),"图片家庭").id();
        metadata=new PostgresAssetMetadataRepository(url,user,password);objects=new MemoryObjects();
    }
    @AfterEach void cleanup() throws Exception { if(schema!=null) try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()) { s.execute("DROP SCHEMA "+schema+" CASCADE"); } }
    AssetCatalog.Metadata record(String selectedFamily,byte[] bytes) { return new AssetCatalog.Metadata(UUID.randomUUID().toString(),selectedFamily,"profile-1","image/png",bytes.length,1,1,AssetCatalog.sha256(bytes),Instant.now().toString()); }
    @Test void sharedCatalogsPublishConcurrentlyWithoutCleanupAndRestartReadsAll() throws Exception {
        var first=new AssetCatalog(objects,metadata);var second=new AssetCatalog(objects,new PostgresAssetMetadataRepository(url,user,password));byte[] bytes={1,2,3};
        String inFlight=UUID.randomUUID().toString();objects.put(inFlight,bytes);new AssetCatalog(objects,new PostgresAssetMetadataRepository(url,user,password));assertTrue(objects.bytes.containsKey(inFlight));assertEquals(0,objects.cleanups);
        List<Future<AssetCatalog.Metadata>> futures=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(4)) {
            for(int i=0;i<16;i++) { var record=record(family,bytes);var catalog=i%2==0?first:second;futures.add(pool.submit(()->catalog.publish(record,bytes))); }
            for(var future:futures) { var published=future.get(10,TimeUnit.SECONDS);assertEquals(published,second.get(published.id()));assertArrayEquals(bytes,second.read(published)); }
        }
        assertEquals(16,new PostgresAssetMetadataRepository(url,user,password).list().size());
    }
    @Test void metadataIsImmutableAndFKFailureRollsBackOnlyOwnObject() throws Exception {
        var catalog=new AssetCatalog(objects,metadata);byte[] bytes={1,2};var record=record(family,bytes);catalog.publish(record,bytes);
        assertThrows(IOException.class,()->metadata.publish(record));assertThrows(IOException.class,()->objects.discardUncommitted(record.id()));assertArrayEquals(bytes,catalog.read(record));
        var invalid=record("missing-family",bytes);assertThrows(ApiException.class,()->catalog.publish(invalid,bytes));assertNull(metadata.find(invalid.id()));assertFalse(objects.bytes.containsKey(invalid.id()));
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()) {
            assertEquals("23514",assertThrows(SQLException.class,()->s.execute("UPDATE ct_asset_metadata SET profile_id='other' WHERE id='"+record.id()+"'")).getSQLState());
            assertEquals("23514",assertThrows(SQLException.class,()->s.execute("DELETE FROM ct_asset_metadata WHERE id='"+record.id()+"'")).getSQLState());
        }
    }
    @Test void springWiresExplicitPostgresMetadataAndRejectsMixedConfiguration() throws Exception {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixture",Map.of("cardtalk.family-repository-mode","postgres","cardtalk.asset-metadata-mode","postgres","cardtalk.family-jdbc-url",url,"cardtalk.family-jdbc-user",user,"cardtalk.family-jdbc-password",password)));
            context.registerBean(AssetStore.class,()->objects);
            context.register(AssetStorageConfiguration.class,PostgresFamilyRepository.class,PostgresAssetMetadataRepository.class,AssetCatalog.class);context.refresh();
            byte[] bytes={1};var record=record(family,bytes);context.getBean(AssetCatalog.class).publish(record,bytes);assertEquals(record,metadata.find(record.id()));assertEquals(0,objects.cleanups);
        }
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("invalid",Map.of("cardtalk.asset-metadata-mode","postgres","cardtalk.family-repository-mode","json")));
            context.register(AssetStorageConfiguration.class);assertThrows(IllegalStateException.class,context::refresh);
        }
    }
    @Test void incompleteObjectsNeverPublishAndUncertainCommitsNeverDeleteObjects() throws Exception {
        byte[] bytes={1,2,3};var record=record(family,bytes);
        var broken=new MemoryObjects() { public byte[] read(String id) { return new byte[]{1}; } };var catalog=new AssetCatalog(broken,metadata);
        assertThrows(ApiException.class,()->catalog.publish(record,bytes));assertNull(metadata.find(record.id()));assertFalse(broken.bytes.containsKey(record.id()));
        var uncertain=new AssetMetadataRepository() {
            public AssetCatalog.Metadata find(String id) { return null; }public List<AssetCatalog.Metadata> list() { return List.of(); }
            public void publish(AssetCatalog.Metadata value) throws IOException { throw new PublicationUncertain(new IOException("lost commit response")); }
        };
        assertThrows(ApiException.class,()->new AssetCatalog(objects,uncertain).publish(record,bytes));assertTrue(objects.bytes.containsKey(record.id()));
        new AssetCatalog(objects,metadata);assertTrue(objects.bytes.containsKey(record.id()));
    }
}
