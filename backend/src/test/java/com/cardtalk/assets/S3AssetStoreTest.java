package com.cardtalk.assets;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class S3AssetStoreTest {
    static final String ACCESS="fixtureAccess",SECRET="fixtureSecret",REGION="us-east-1",BUCKET="fixture-bucket";
    HttpServer server;ExecutorService executor;String endpoint;S3AssetStore store;
    Map<String,byte[]> objects=new ConcurrentHashMap<>();Map<String,String> etags=new ConcurrentHashMap<>();
    List<String> methods=new CopyOnWriteArrayList<>();AtomicReference<String> signatureError=new AtomicReference<>();
    AtomicInteger overrideStatus=new AtomicInteger();AtomicReference<byte[]> overrideBody=new AtomicReference<>();AtomicBoolean slowBody=new AtomicBoolean();
    Clock clock=Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.createContext("/",this::handle);server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();store=new S3AssetStore(endpoint,REGION,BUCKET,ACCESS,SECRET,true,true,1024,1,clock);
    }
    @AfterEach void cleanup() { server.stop(0);executor.shutdownNow(); }
    static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static byte[] mac(byte[] key,String text) throws Exception { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8)); }
    /** Independent verifier from the documented SigV4 canonical request and signing-key derivation. */
    boolean signature(HttpExchange exchange,byte[] bytes) throws Exception {
        var headers=exchange.getRequestHeaders();String method=exchange.getRequestMethod();String auth=headers.getFirst("Authorization");
        if(auth==null||!auth.startsWith("AWS4-HMAC-SHA256 Credential="+ACCESS+"/20261012/"+REGION+"/s3/aws4_request, SignedHeaders=")) return false;
        String signed=auth.split("SignedHeaders=",2)[1].split(",",2)[0];
        List<String> expected=new ArrayList<>(List.of("host","x-amz-content-sha256","x-amz-date"));if(method.equals("PUT")) expected.addAll(List.of("content-type","if-none-match"));if(method.equals("DELETE")) expected.add("if-match");Collections.sort(expected);if(!signed.equals(String.join(";",expected))) return false;
        if(!Objects.equals(headers.getFirst("x-amz-content-sha256"),digest(bytes))||!Objects.equals(headers.getFirst("x-amz-date"),"20261012T000000Z")) return false;
        if(exchange.getRequestURI().getRawQuery()!=null||!exchange.getRequestURI().getRawPath().matches("/fixture-bucket/cardtalk-assets/[a-f0-9-]{36}\\.blob")) return false;
        StringBuilder canonicalHeaders=new StringBuilder();for(String header:expected) canonicalHeaders.append(header).append(':').append(headers.getFirst(header).trim().replaceAll("\\s+"," ")).append('\n');
        String canonical=method+"\n"+exchange.getRequestURI().getRawPath()+"\n\n"+canonicalHeaders+"\n"+signed+"\n"+digest(bytes);
        String toSign="AWS4-HMAC-SHA256\n20261012T000000Z\n20261012/"+REGION+"/s3/aws4_request\n"+digest(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] signing=mac(mac(mac(mac(("AWS4"+SECRET).getBytes(StandardCharsets.UTF_8),"20261012"),REGION),"s3"),"aws4_request");
        return MessageDigest.isEqual(HexFormat.of().parseHex(auth.split("Signature=",2)[1]),mac(signing,toSign));
    }
    void handle(HttpExchange exchange) throws IOException {
        byte[] body=exchange.getRequestBody().readAllBytes();String path=exchange.getRequestURI().getRawPath(),method=exchange.getRequestMethod();methods.add(method);
        try {
            if(!signature(exchange,body)) { signatureError.set("canonical signature verification failed");exchange.sendResponseHeaders(403,-1);return; }
            if(overrideStatus.get()!=0) { exchange.sendResponseHeaders(overrideStatus.get(),-1);return; }
            switch(method) {
                case "PUT" -> {
                    if(!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) { exchange.sendResponseHeaders(400,-1);return; }
                    if(objects.putIfAbsent(path,body)!=null) { exchange.sendResponseHeaders(412,-1);return; }
                    String etag="\""+digest(body)+"\"";etags.put(path,etag);exchange.getResponseHeaders().set("ETag",etag);exchange.sendResponseHeaders(200,-1);
                }
                case "GET" -> {
                    byte[] bytes=overrideBody.get()!=null?overrideBody.get():objects.get(path);if(bytes==null) { exchange.sendResponseHeaders(404,-1);return; }
                    exchange.sendResponseHeaders(200,bytes.length);if(slowBody.get()) Thread.sleep(1500);exchange.getResponseBody().write(bytes);
                }
                case "DELETE" -> {
                    if(!Objects.equals(etags.get(path),exchange.getRequestHeaders().getFirst("If-Match"))) { exchange.sendResponseHeaders(412,-1);return; }
                    objects.remove(path);etags.remove(path);exchange.sendResponseHeaders(204,-1);
                }
                default -> exchange.sendResponseHeaders(405,-1);
            }
        } catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
        catch(Exception failure) { if(!slowBody.get()) signatureError.set("fixture request failed"); }
        finally { exchange.close(); }
    }
    @Test void signedPutGetDeleteAreVerifiedAndOnlyUnpublishedObjectsCanBeDeleted() throws Exception {
        String id=UUID.randomUUID().toString();byte[] bytes={1,2,3,4};store.put(id,bytes);assertArrayEquals(bytes,store.read(id));
        assertThrows(IOException.class,()->store.put(id,new byte[]{5}));assertArrayEquals(bytes,store.read(id));
        store.markPublished(id);assertThrows(IOException.class,()->store.discardUncommitted(id));store.cleanupUncommitted(Set.of());assertArrayEquals(bytes,store.read(id));assertFalse(methods.contains("DELETE"));
        String orphan=UUID.randomUUID().toString();store.put(orphan,bytes);store.discardUncommitted(orphan);assertThrows(IOException.class,()->store.read(orphan));assertTrue(methods.contains("DELETE"));assertNull(signatureError.get());
    }
    @Test void invalidConfigPathInjectionAndUnsuccessfulResponsesDoNotFakeSuccess() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new S3AssetStore(endpoint,REGION,BUCKET,ACCESS,SECRET,true,false,1024,1));
        assertThrows(IllegalArgumentException.class,()->new S3AssetStore("http://example.com",REGION,BUCKET,ACCESS,SECRET,true,true,1024,1));
        assertThrows(IllegalArgumentException.class,()->new S3AssetStore("https://example.com/path?query",REGION,BUCKET,ACCESS,SECRET,true,false,1024,1));
        assertThrows(IOException.class,()->store.put("../foreign",new byte[]{1}));assertEquals(0,methods.size());
        overrideStatus.set(500);String id=UUID.randomUUID().toString();IOException failure=assertThrows(IOException.class,()->store.put(id,new byte[]{1}));assertFalse(failure.getMessage().contains(SECRET));assertThrows(IOException.class,()->store.read(id));assertThrows(IOException.class,()->store.discardUncommitted(id));assertFalse(methods.contains("DELETE"));
        overrideStatus.set(403);assertThrows(IOException.class,()->store.read(id));assertNull(signatureError.get());
    }
    @Test void getBodySizeAndFullRequestDeadlineAreBounded() throws Exception {
        String id=UUID.randomUUID().toString();store.put(id,new byte[]{1});overrideBody.set(new byte[1025]);assertThrows(IOException.class,()->store.read(id));
        overrideBody.set(new byte[]{1});slowBody.set(true);long start=System.nanoTime();assertThrows(IOException.class,()->store.read(id));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2500);
        slowBody.set(false);overrideBody.set(null);assertArrayEquals(new byte[]{1},store.read(id));assertNull(signatureError.get());
    }
    @Test void catalogDetectsCorruptionBeforePublishingAndSharedRestartNeverCleans() throws Exception {
        var entries=new ConcurrentHashMap<String,AssetCatalog.Metadata>();
        var metadata=new AssetMetadataRepository() {
            public AssetCatalog.Metadata find(String id) { return entries.get(id); }
            public void publish(AssetCatalog.Metadata value) throws IOException { if(entries.putIfAbsent(value.id(),value)!=null) throw new IOException("immutable"); }
            public List<AssetCatalog.Metadata> list() { return List.copyOf(entries.values()); }
        };
        var catalog=new AssetCatalog(store,metadata);byte[] bytes={1,2,3};var record=new AssetCatalog.Metadata(UUID.randomUUID().toString(),"family","profile","image/png",3,1,1,AssetCatalog.sha256(bytes),clock.instant().toString());
        overrideBody.set(new byte[]{9});assertThrows(com.cardtalk.api.ApiException.class,()->catalog.publish(record,bytes));assertTrue(entries.isEmpty());assertTrue(objects.isEmpty());
        overrideBody.set(null);catalog.publish(record,bytes);assertArrayEquals(bytes,new AssetCatalog(store,metadata).read(record));assertThrows(IOException.class,()->store.discardUncommitted(record.id()));assertNull(signatureError.get());
    }
}
