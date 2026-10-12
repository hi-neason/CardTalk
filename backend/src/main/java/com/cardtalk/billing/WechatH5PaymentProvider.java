package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAKey;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

/** API v3, ordinary merchant H5, public-key verification mode only. */
public final class WechatH5PaymentProvider implements PaymentProvider {
    private static final int MAX_BODY=65536;
    private final ObjectMapper mapper;
    private final String mchid,appid,serial,notify;
    private final URI endpoint;
    private final PrivateKey privateKey;
    private final Map<String,PublicKey> publicKeys;
    private final byte[] aesKey;
    private final Duration timeout;
    private final HttpClient client;
    private final Semaphore slots;
    public record Config(String merchantId,String appId,String merchantSerial,Path privateKeyFile,Path publicKeysFile,
                         String apiV3Key,String notifyUrl,String endpoint,boolean allowLoopbackHttp,int timeoutSeconds,int maxConcurrent) {}
    public WechatH5PaymentProvider(ObjectMapper mapper,Config config) {
        this.mapper=mapper;
        try {
            mchid=token(config.merchantId(),32);appid=token(config.appId(),64);serial=token(config.merchantSerial(),128);
            notify=httpsUrl(config.notifyUrl()).toString();endpoint=URI.create(config.endpoint());
            boolean loopback=config.allowLoopbackHttp()&&"http".equals(endpoint.getScheme())&&Set.of("127.0.0.1","[::1]","::1").contains(endpoint.getHost());
            if(!loopback&&!("https".equals(endpoint.getScheme())&&"api.mch.weixin.qq.com".equals(endpoint.getHost())&&(endpoint.getPort()==-1||endpoint.getPort()==443)))throw new IllegalArgumentException();
            if(endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getRawFragment()!=null||!(endpoint.getRawPath().isEmpty()||endpoint.getRawPath().equals("/")))throw new IllegalArgumentException();
            aesKey=config.apiV3Key().getBytes(StandardCharsets.UTF_8);if(aesKey.length!=32)throw new IllegalArgumentException();
            privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pem(config.privateKeyFile(),"PRIVATE KEY")));
            checkRsa(privateKey);
            JsonNode keys=mapper.readTree(Files.readAllBytes(config.publicKeysFile()));
            if(!keys.isObject()||keys.isEmpty()||keys.size()>10)throw new IllegalArgumentException();
            Map<String,PublicKey> parsed=new HashMap<>();var fields=keys.fields();while(fields.hasNext()){
                var f=fields.next();if(!f.getKey().matches("PUB_KEY_ID_[A-Za-z0-9_]{1,120}")||!f.getValue().isTextual())throw new IllegalArgumentException();
                Path file=Path.of(f.getValue().textValue());if(!file.isAbsolute())file=config.publicKeysFile().toAbsolutePath().getParent().resolve(file);
                PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(file,"PUBLIC KEY")));checkRsa(key);parsed.put(f.getKey(),key);
            }publicKeys=Map.copyOf(parsed);
            if(config.timeoutSeconds()<1||config.timeoutSeconds()>120||config.maxConcurrent()<1||config.maxConcurrent()>32)throw new IllegalArgumentException();
            timeout=Duration.ofSeconds(config.timeoutSeconds());slots=new Semaphore(config.maxConcurrent());
            client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        }catch(Exception e){throw new IllegalArgumentException("Invalid WeChat H5 payment configuration; verify required settings and key files");}
    }
    private static void checkRsa(Key key){if(!(key instanceof RSAKey rsa)||rsa.getModulus().bitLength()<2048)throw new IllegalArgumentException();}
    private static byte[] pem(Path file,String label)throws Exception{
        if(Files.size(file)>16384)throw new IllegalArgumentException();
        String text=Files.readString(file).trim();String start="-----BEGIN "+label+"-----",end="-----END "+label+"-----";
        if(!text.startsWith(start)||!text.endsWith(end))throw new IllegalArgumentException();
        return Base64.getDecoder().decode(text.substring(start.length(),text.length()-end.length()).replaceAll("\\s", ""));
    }
    private static String token(String value,int max){if(value==null||!value.matches("[A-Za-z0-9_-]{1,"+max+"}"))throw invalid();return value;}
    private static URI httpsUrl(String url){URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw invalid();return uri;}
    private static ApiException invalid(){return new ApiException(502,"INVALID_PAYMENT_REPLY","微信支付数据校验失败，订单保留待核对");}
    private static ApiException unavailable(){return new ApiException(503,"PAYMENT_UNAVAILABLE","微信支付暂不可用，订单保留待核对");}
    public String name(){return "wechat-h5";}public boolean configured(){return true;}public String merchantId(){return mchid;}public String appId(){return appid;}
    private ObjectNode object(){return mapper.createObjectNode();}
    private static String text(JsonNode node,String field){JsonNode v=node.get(field);if(v==null||!v.isTextual()||v.textValue().isBlank()||v.textValue().length()>4000)throw invalid();return v.textValue();}
    private static long money(JsonNode node,String field){JsonNode v=node.get(field);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw invalid();return v.longValue();}
    private static Instant time(JsonNode node,String field){try{return Instant.parse(text(node,field));}catch(Exception e){throw invalid();}}
    private static void same(String actual,String expected){if(!expected.equals(actual))throw invalid();}
    private static void amount(long total,String currency){if(total<=0||!"CNY".equals(currency))throw invalid();}
    private static String segment(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    public Checkout createCheckout(CheckoutRequest r){
        token(r.outTradeNo(),32);amount(r.amountMinor(),r.currency());same(r.notifyUrl(),notify);
        if(r.description()==null||r.description().isBlank()||r.description().getBytes(StandardCharsets.UTF_8).length>127)throw invalid();
        // Require a literal IP, never resolve user-supplied names or trust arbitrary forwarded headers.
        String ip=r.payerClientIp();if(ip==null||!ip.matches("[0-9a-fA-F:.]{3,45}")||(!ip.contains(":")&&!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))throw invalid();
        try{InetAddress.getByName(ip);}catch(Exception e){throw invalid();}
        var body=object().put("appid",appid).put("mchid",mchid).put("description",r.description()).put("out_trade_no",r.outTradeNo()).put("notify_url",notify);
        body.set("amount",object().put("total",r.amountMinor()).put("currency",r.currency()));
        var scene=object().put("payer_client_ip",ip);scene.set("h5_info",object().put("type","Wap"));body.set("scene_info",scene);
        JsonNode reply=request("POST","/v3/pay/transactions/h5",body,200);
        String url=text(reply,"h5_url");URI uri=httpsUrl(url);
        if(!"wx.tenpay.com".equals(uri.getHost())||(uri.getPort()!=-1&&uri.getPort()!=443))throw invalid();
        return new Checkout(url,r.outTradeNo());
    }
    public ProviderEvent queryPayment(String trade){
        token(trade,32);JsonNode body=request("GET","/v3/pay/transactions/out-trade-no/"+segment(trade)+"?mchid="+segment(mchid),null,200);
        same(text(body,"out_trade_no"),trade);return payment(body,null,null);
    }
    public void close(String trade){token(trade,32);request("POST","/v3/pay/transactions/out-trade-no/"+segment(trade)+"/close",object().put("mchid",mchid),204);}
    public RefundResult refund(RefundRequest r){
        token(r.outTradeNo(),32);token(r.outRefundNo(),64);amount(r.totalMinor(),r.currency());if(r.refundMinor()<=0||r.refundMinor()>r.totalMinor()||r.reason()==null||r.reason().isBlank()||r.reason().getBytes(StandardCharsets.UTF_8).length>80)throw invalid();
        var body=object().put("out_trade_no",r.outTradeNo()).put("out_refund_no",r.outRefundNo()).put("reason",r.reason()).put("notify_url",notify);
        body.set("amount",object().put("total",r.totalMinor()).put("refund",r.refundMinor()).put("currency",r.currency()));
        JsonNode reply=request("POST","/v3/refund/domestic/refunds",body,200);
        if(reply.has("mchid"))same(text(reply,"mchid"),mchid);if(reply.has("appid"))same(text(reply,"appid"),appid);
        same(text(reply,"out_trade_no"),r.outTradeNo());same(text(reply,"out_refund_no"),r.outRefundNo());
        validateRefundAmount(reply);if(money(reply.path("amount"),"total")!=r.totalMinor()||money(reply.path("amount"),"refund")!=r.refundMinor())throw invalid();
        String status=text(reply,"status");if(!Set.of("SUCCESS","PROCESSING","CLOSED","ABNORMAL").contains(status))throw invalid();
        return new RefundResult(r.outRefundNo(),token(text(reply,"refund_id"),64),status);
    }
    public ProviderEvent queryRefund(String refund){
        token(refund,64);JsonNode body=request("GET","/v3/refund/domestic/refunds/"+segment(refund),null,200);
        same(text(body,"out_refund_no"),refund);return refundEvent(body,null,null,false);
    }
    private ProviderEvent payment(JsonNode body,String eventId,Instant notificationTime){
        same(text(body,"mchid"),mchid);same(text(body,"appid"),appid);
        String trade=token(text(body,"out_trade_no"),32),state=text(body,"trade_state");
        long total=money(body.path("amount"),"total");String currency=text(body.path("amount"),"currency");amount(total,currency);
        EventType type=switch(state){case "SUCCESS"->EventType.PAID;case "CLOSED","REVOKED"->EventType.CLOSED;case "PAYERROR"->EventType.FAILED;case "NOTPAY","USERPAYING","REFUND"->null;default->throw invalid();};
        if(type==null)return null;
        String transaction=type==EventType.PAID?token(text(body,"transaction_id"),64):null;
        Instant at=type==EventType.PAID?time(body,"success_time"):notificationTime==null?Instant.EPOCH:notificationTime;
        return new ProviderEvent(eventId==null?"payment:"+(transaction==null?trade:transaction)+":"+state:eventId,type,trade,null,transaction,total,0,currency,mchid,appid,at);
    }
    private void validateRefundAmount(JsonNode body){long total=money(body.path("amount"),"total"),refund=money(body.path("amount"),"refund");amount(total,text(body.path("amount"),"currency"));if(refund<=0||refund>total)throw invalid();}
    private ProviderEvent refundEvent(JsonNode body,String eventId,Instant notificationTime,boolean notification){
        if(notification||body.has("mchid"))same(text(body,"mchid"),mchid);
        if(body.has("appid"))same(text(body,"appid"),appid); // Refund API resources omit appid; signed context binds the configured app.
        // Official refund notifications omit currency/appid; the signed merchant plus stored order binds CNY/appid.
        if(notification&&!body.path("amount").has("currency")&&body.path("amount").isObject())((ObjectNode)body.path("amount")).put("currency","CNY");
        validateRefundAmount(body);String refund=token(text(body,"out_refund_no"),64),trade=token(text(body,"out_trade_no"),32);
        token(text(body,"refund_id"),64);String state=text(body,notification?"refund_status":"status");
        EventType type=switch(state){case "SUCCESS"->EventType.REFUNDED;case "CLOSED","ABNORMAL"->EventType.REFUND_FAILED;case "PROCESSING"->null;default->throw invalid();};
        if(type==null)return null;
        String transaction=token(text(body,"transaction_id"),64);
        Instant at=type==EventType.REFUNDED?time(body,"success_time"):notificationTime==null?time(body,"create_time"):notificationTime;
        return new ProviderEvent(eventId==null?"refund:"+refund+":"+state:eventId,type,trade,refund,transaction,money(body.path("amount"),"total"),money(body.path("amount"),"refund"),text(body.path("amount"),"currency"),mchid,appid,at);
    }
    public ProviderEvent verifyNotification(Map<String,String> headers,byte[] rawBody){
        verify(headers,rawBody);
        try{
            JsonNode notification=mapper.readTree(rawBody);String id=token(text(notification,"id"),128),type=text(notification,"event_type");
            Instant at=time(notification,"create_time");same(text(notification,"resource_type"),"encrypt-resource");
            JsonNode resource=notification.path("resource");same(text(resource,"algorithm"),"AEAD_AES_256_GCM");
            byte[] nonce=text(resource,"nonce").getBytes(StandardCharsets.UTF_8);if(nonce.length!=12)throw invalid();
            JsonNode encoded=resource.get("ciphertext");if(encoded==null||!encoded.isTextual()||encoded.textValue().length()>MAX_BODY)throw invalid();
            byte[] encrypted=Base64.getDecoder().decode(encoded.textValue());if(encrypted.length<16)throw invalid();
            JsonNode aad=resource.get("associated_data");if(aad!=null&&!aad.isTextual())throw invalid();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aesKey,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((aad==null?"":aad.textValue()).getBytes(StandardCharsets.UTF_8));JsonNode body=mapper.readTree(cipher.doFinal(encrypted));
            ProviderEvent event=switch(type){
                case "TRANSACTION.SUCCESS"->{same(text(resource,"original_type"),"transaction");same(text(body,"trade_state"),"SUCCESS");yield payment(body,id,at);}
                case "REFUND.SUCCESS","REFUND.CLOSED","REFUND.ABNORMAL"->{same(text(resource,"original_type"),"refund");same(text(body,"refund_status"),type.substring(7));yield refundEvent(body,id,at,true);}
                default->throw invalid();};
            if(event==null)throw invalid();return event;
        }catch(Exception e){throw invalid();}
    }
    private JsonNode request(String method,String rawPath,JsonNode body,int expectedStatus){
        if(!slots.tryAcquire())throw unavailable();CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            byte[] bytes=body==null?new byte[0]:mapper.writeValueAsBytes(body);String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString().replace("-","");
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(privateKey);signer.update((method+"\n"+rawPath+"\n"+timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));signer.update(bytes);signer.update((byte)'\n');
            String authorization="WECHATPAY2-SHA256-RSA2048 mchid=\""+mchid+"\",nonce_str=\""+nonce+"\",timestamp=\""+timestamp+"\",serial_no=\""+serial+"\",signature=\""+Base64.getEncoder().encodeToString(signer.sign())+"\"";
            URI uri=URI.create(endpoint.getScheme()+"://"+endpoint.getRawAuthority()+rawPath);
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization",authorization).header("Accept","application/json").header("Content-Type","application/json").header("User-Agent","CardTalk/1.0").method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            pending=client.sendAsync(request,info->new LimitedBody());HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            Map<String,String> headers=new HashMap<>();for(var entry:response.headers().map().entrySet()){if(entry.getKey().toLowerCase(Locale.ROOT).startsWith("wechatpay-")&&entry.getValue().size()!=1)throw invalid();headers.put(entry.getKey(),entry.getValue().getFirst());}
            verify(headers,response.body());
            if(response.statusCode()!=expectedStatus)throw unavailable();
            if(expectedStatus==204){if(response.body().length!=0)throw invalid();return mapper.nullNode();}
            JsonNode parsed=mapper.readTree(response.body());if(parsed==null||!parsed.isObject())throw invalid();return parsed;
        }catch(ApiException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);slots.release();}
    }
    private void verify(Map<String,String> headers,byte[] body){
        if(body==null||body.length>MAX_BODY||headers==null)throw invalid();
        try{
            Map<String,String> normalized=new HashMap<>();for(var entry:headers.entrySet()){String key=entry.getKey().toLowerCase(Locale.ROOT);if(normalized.putIfAbsent(key,entry.getValue())!=null)throw invalid();}
            String timestamp=normalized.get("wechatpay-timestamp"),nonce=normalized.get("wechatpay-nonce"),keyId=normalized.get("wechatpay-serial"),signed=normalized.get("wechatpay-signature");
            if(timestamp==null||!timestamp.matches("[0-9]{1,12}")||nonce==null||nonce.isBlank()||nonce.length()>128||nonce.contains("\n")||nonce.contains("\r")||signed==null||signed.startsWith("WECHATPAY/SIGNTEST/"))throw invalid();
            long ts=Long.parseLong(timestamp),now=Instant.now().getEpochSecond();if(ts<now-300||ts>now+300)throw invalid();PublicKey key=publicKeys.get(keyId);if(key==null)throw invalid();
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));verifier.update(body);verifier.update((byte)'\n');if(!verifier.verify(Base64.getDecoder().decode(signed)))throw invalid();
        }catch(Exception e){throw invalid();}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;result.whenComplete((v,e)->{if(result.isCancelled())subscription.cancel();});subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer buffer:buffers){if(buffer.remaining()>MAX_BODY-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Payment response exceeds limit"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}public void onComplete(){result.complete(bytes.toByteArray());}
    }
    @Configuration(proxyBeanMethods=false)
    public static class Wiring {
        @Bean @ConditionalOnProperty(name="cardtalk.billing-provider",havingValue="wechat-h5")
        PaymentProvider wechatPaymentProvider(ObjectMapper mapper,Environment env){return new WechatH5PaymentProvider(mapper,new Config(
            env.getProperty("cardtalk.wechat-mchid",""),env.getProperty("cardtalk.wechat-appid",""),env.getProperty("cardtalk.wechat-merchant-serial",""),
            Path.of(env.getProperty("cardtalk.wechat-private-key-file","")),Path.of(env.getProperty("cardtalk.wechat-public-keys-file","")),env.getProperty("cardtalk.wechat-api-v3-key",""),
            env.getProperty("cardtalk.billing-notify-url",""),env.getProperty("cardtalk.wechat-endpoint","https://api.mch.weixin.qq.com"),env.getProperty("cardtalk.wechat-allow-loopback-http",Boolean.class,false),
            env.getProperty("cardtalk.wechat-timeout-seconds",Integer.class,15),env.getProperty("cardtalk.wechat-max-concurrent",Integer.class,4)));}
    }
}
