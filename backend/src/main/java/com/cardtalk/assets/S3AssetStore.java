package com.cardtalk.assets;

import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** AWS SigV4 S3 REST, signed payloads and conditional immutable creation; no public URLs. */
@Component
@ConditionalOnProperty(name="cardtalk.asset-store-mode",havingValue="s3")
public class S3AssetStore implements AssetStore {
    private final URI endpoint;
    private final String region,bucket,accessKey,secretKey;
    private final boolean pathStyle;
    private final int maxBytes;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient client;
    private final ConcurrentMap<String,String> pending=new ConcurrentHashMap<>();
    @Autowired
    public S3AssetStore(@Value("${cardtalk.s3-endpoint:}") String endpoint,@Value("${cardtalk.s3-region:us-east-1}") String region,
                        @Value("${cardtalk.s3-bucket:}") String bucket,@Value("${cardtalk.s3-access-key:}") String accessKey,
                        @Value("${cardtalk.s3-secret-key:}") String secretKey,@Value("${cardtalk.s3-path-style:true}") boolean pathStyle,
                        @Value("${cardtalk.s3-allow-loopback-http:false}") boolean allowLoopbackHttp,@Value("${cardtalk.asset-max-bytes:16777216}") int maxBytes,
                        @Value("${cardtalk.s3-timeout-seconds:30}") int timeoutSeconds) {
        this(endpoint,region,bucket,accessKey,secretKey,pathStyle,allowLoopbackHttp,maxBytes,timeoutSeconds,Clock.systemUTC());
    }
    S3AssetStore(String endpoint,String region,String bucket,String accessKey,String secretKey,boolean pathStyle,boolean allowLoopbackHttp,int maxBytes,int timeoutSeconds,Clock clock) {
        try {
            URI address=URI.create(endpoint);
            if(address.getHost()==null||address.getRawUserInfo()!=null||address.getRawQuery()!=null||address.getRawFragment()!=null||!(address.getRawPath().isEmpty()||address.getRawPath().equals("/"))) throw new IllegalArgumentException();
            boolean loopback=false;
            if(allowLoopbackHttp&&Set.of("127.0.0.1","localhost","[::1]","::1").contains(address.getHost())) loopback=Arrays.stream(InetAddress.getAllByName(address.getHost())).allMatch(InetAddress::isLoopbackAddress);
            if(!"https".equals(address.getScheme())&&!("http".equals(address.getScheme())&&loopback)) throw new IllegalArgumentException();
            if(!pathStyle&&address.getHost().contains(":")) throw new IllegalArgumentException();
            if(!region.matches("[a-z0-9-]{1,40}")||!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")||bucket.contains("..")||!accessKey.matches("[A-Za-z0-9_-]{1,128}")||secretKey.isBlank()||secretKey.length()>512||maxBytes<1||maxBytes>134217728||timeoutSeconds<1||timeoutSeconds>120) throw new IllegalArgumentException();
            this.endpoint=address;this.region=region;this.bucket=bucket;this.accessKey=accessKey;this.secretKey=secretKey;this.pathStyle=pathStyle;this.maxBytes=maxBytes;this.clock=clock;timeout=Duration.ofSeconds(timeoutSeconds);client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch(IllegalArgumentException|java.net.UnknownHostException failure) { throw new IllegalArgumentException("Invalid S3 configuration; HTTPS or explicitly enabled literal loopback HTTP is required"); }
    }
    private URI objectUri(String id) throws IOException {
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IOException("Invalid object identifier");
        String host=(pathStyle?"":bucket+".")+endpoint.getHost();String authority=host+(endpoint.getPort()==-1?"":":"+endpoint.getPort());
        return URI.create(endpoint.getScheme()+"://"+authority+"/"+(pathStyle?bucket+"/":"")+"cardtalk-assets/"+id+".blob");
    }
    private static byte[] hmac(byte[] key,String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch(GeneralSecurityException failure) { throw new IllegalStateException("S3 signing unavailable",failure); }
    }
    private HttpRequest request(String method,String id,byte[] bytes,Map<String,String> extra) throws IOException {
        URI uri=objectUri(id);String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant()),date=timestamp.substring(0,8),digest=AssetCatalog.sha256(bytes);
        var headers=new TreeMap<String,String>(extra);headers.put("host",uri.getRawAuthority());headers.put("x-amz-date",timestamp);headers.put("x-amz-content-sha256",digest);
        String names=String.join(";",headers.keySet());StringBuilder canonicalHeaders=new StringBuilder();headers.forEach((name,value)->canonicalHeaders.append(name).append(':').append(value.strip().replaceAll("\\s+"," ")).append('\n'));
        // Generated keys/bucket contain only URI-unreserved characters and slash; no normalization or query is needed.
        String canonical=method+"\n"+uri.getRawPath()+"\n\n"+canonicalHeaders+"\n"+names+"\n"+digest;
        String scope=date+"/"+region+"/s3/aws4_request";
        String sign="AWS4-HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+AssetCatalog.sha256(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key=hmac(hmac(hmac(hmac(("AWS4"+secretKey).getBytes(StandardCharsets.UTF_8),date),region),"s3"),"aws4_request");
        String authorization="AWS4-HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+names+", Signature="+HexFormat.of().formatHex(hmac(key,sign));
        var builder=HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization",authorization);
        headers.forEach((name,value)->{if(!name.equals("host")) builder.header(name,value);});
        return builder.method(method,bytes.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
    }
    private HttpResponse<byte[]> send(HttpRequest request,int limit) throws IOException {
        var future=client.sendAsync(request,info->new BoundedBytes(limit));
        try { return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(TimeoutException failure) { future.cancel(true);throw new IOException("S3 request deadline exceeded"); }
        catch(InterruptedException failure) { future.cancel(true);Thread.currentThread().interrupt();throw new IOException("S3 request interrupted"); }
        catch(ExecutionException failure) { throw new IOException("S3 request failed",failure.getCause()); }
    }
    public void put(String id,byte[] bytes) throws IOException {
        if(bytes.length==0||bytes.length>maxBytes) throw new IOException("Invalid object size");
        var response=send(request("PUT",id,bytes,Map.of("content-type","application/octet-stream","if-none-match","*")),65536);
        if(response.statusCode()!=200&&response.statusCode()!=201) throw new IOException("S3 immutable PUT rejected (HTTP "+response.statusCode()+")");
        String etag=response.headers().firstValue("ETag").orElse("");
        if(!etag.matches("\"[\\x21\\x23-\\x7E]{1,256}\"")) throw new IOException("S3 PUT did not return a usable ETag; object retained");
        pending.put(id,etag);
    }
    public byte[] read(String id) throws IOException {
        var response=send(request("GET",id,new byte[0],Map.of()),maxBytes);
        if(response.statusCode()!=200) throw new IOException("S3 GET rejected (HTTP "+response.statusCode()+")");return response.body();
    }
    public void markPublished(String id) { pending.remove(id); }
    public void discardUncommitted(String id) throws IOException {
        String etag=pending.get(id);if(etag==null) throw new IOException("Only this uploader can discard an uncommitted object");
        var response=send(request("DELETE",id,new byte[0],Map.of("if-match",etag)),65536);
        if(response.statusCode()!=204&&response.statusCode()!=200) throw new IOException("S3 uncommitted cleanup rejected (HTTP "+response.statusCode()+")");pending.remove(id,etag);
    }
    public void cleanupUncommitted(Set<String> publishedIds) { /* Shared S3 objects cannot be safely reclaimed at startup. */ }
    private static class BoundedBytes implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;private int received;private boolean done;private Flow.Subscription subscription;private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        BoundedBytes(int maximum) { this.maximum=maximum; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value;delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) { if(done) return;for(var buffer:buffers) { if(buffer.remaining()>maximum-received) { done=true;subscription.cancel();delegate.onError(new IOException("S3 response exceeds size limit"));return; }received+=buffer.remaining(); }delegate.onNext(buffers); }
        public void onError(Throwable failure) { if(!done) { done=true;delegate.onError(failure); } }
        public void onComplete() { if(!done) { done=true;delegate.onComplete(); } }
    }
}
