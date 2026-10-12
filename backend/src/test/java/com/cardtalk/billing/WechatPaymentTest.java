package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.cardtalk.billing.PaymentProvider.*;

class WechatPaymentTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper();
    KeyPair merchant,platform,rotated;HttpServer server;ExecutorService executor;
    WechatH5PaymentProvider provider;
    String response="{}",keyId="PUB_KEY_ID_primary",timestamp,signatureOverride;
    byte[] notification;
    int status=200;boolean tamper=false;long delay=0;String lastBody,lastPath,lastMethod;
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger();
    final String notify="https://cardtalk.example/api/payments/notify/wechat-h5",aes="0123456789abcdef0123456789abcdef";
    @BeforeEach void setup()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);merchant=generator.generateKeyPair();platform=generator.generateKeyPair();rotated=generator.generateKeyPair();
        writeKey("merchant.pem","PRIVATE KEY",merchant.getPrivate().getEncoded());writeKey("platform.pem","PUBLIC KEY",platform.getPublic().getEncoded());writeKey("rotated.pem","PUBLIC KEY",rotated.getPublic().getEncoded());
        Files.writeString(temp.resolve("keys.json"),"{\"PUB_KEY_ID_primary\":\"platform.pem\",\"PUB_KEY_ID_rotated\":\"rotated.pem\"}");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.createContext("/",this::serve);server.start();provider=new WechatH5PaymentProvider(mapper,config(1));
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();assertNull(serverFailure.get(),"Independent request signature verifier failed");}
    WechatH5PaymentProvider.Config config(int seconds){return new WechatH5PaymentProvider.Config("1900000001","wxApp1","MERCHANT_SERIAL",temp.resolve("merchant.pem"),temp.resolve("keys.json"),aes,notify,"http://127.0.0.1:"+server.getAddress().getPort(),true,seconds,1);}
    void writeKey(String file,String type,byte[] key)throws Exception{Files.writeString(temp.resolve(file),"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(key)+"\n-----END "+type+"-----\n");}
    String sign(byte[] raw,String ts,String nonce,KeyPair key)throws Exception{var signer=Signature.getInstance("SHA256withRSA");signer.initSign(key.getPrivate());signer.update((ts+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));signer.update(raw);signer.update((byte)'\n');return Base64.getEncoder().encodeToString(signer.sign());}
    Map<String,String> headers(byte[] raw)throws Exception{
        String ts=timestamp==null?Long.toString(Instant.now().getEpochSecond()):timestamp;
        return Map.of("Wechatpay-Timestamp",ts,"Wechatpay-Nonce","fixture-nonce","Wechatpay-Serial",keyId,"Wechatpay-Signature",signatureOverride==null?sign(raw,ts,"fixture-nonce",keyId.equals("PUB_KEY_ID_rotated")?rotated:platform):signatureOverride);
    }
    void serve(HttpExchange exchange){try{
        byte[] request=exchange.getRequestBody().readAllBytes();lastBody=new String(request,StandardCharsets.UTF_8);lastPath=exchange.getRequestURI().toASCIIString();lastMethod=exchange.getRequestMethod();calls.incrementAndGet();
        String auth=exchange.getRequestHeaders().getFirst("Authorization");assertTrue(auth.startsWith("WECHATPAY2-SHA256-RSA2048 "));Map<String,String> values=new HashMap<>();
        for(String pair:auth.substring(auth.indexOf(' ')+1).split(",")){String[] field=pair.split("=",2);values.put(field[0],field[1].substring(1,field[1].length()-1));}
        assertEquals("1900000001",values.get("mchid"));assertEquals("MERCHANT_SERIAL",values.get("serial_no"));
        byte[] canonical=(lastMethod+"\n"+lastPath+"\n"+values.get("timestamp")+"\n"+values.get("nonce_str")+"\n"+lastBody+"\n").getBytes(StandardCharsets.UTF_8);
        Signature check=Signature.getInstance("SHA256withRSA");check.initVerify(merchant.getPublic());check.update(canonical);assertTrue(check.verify(Base64.getDecoder().decode(values.get("signature"))));
        byte[] raw=status==204?new byte[0]:response.getBytes(StandardCharsets.UTF_8);headers(raw).forEach((k,v)->exchange.getResponseHeaders().set(k,v));
        if(tamper&&raw.length>0)raw[0]=(byte)'!';exchange.sendResponseHeaders(status,status==204?-1:raw.length);if(delay>0)Thread.sleep(delay);if(status!=204)exchange.getResponseBody().write(raw);
    }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){}catch(Throwable error){serverFailure.set(error);}finally{exchange.close();}}
    String payment(String state){return "{ \"mchid\":\"1900000001\",\"appid\":\"wxApp1\",\"out_trade_no\":\"trade1\",\"transaction_id\":\"transaction1\",\"trade_state\":\""+state+"\",\"success_time\":\"2026-10-12T00:00:00Z\",\"amount\":{\"total\":123,\"currency\":\"CNY\"}}\n";}
    String refund(String state){return "{\"out_trade_no\":\"trade1\",\"out_refund_no\":\"refund1\",\"transaction_id\":\"transaction1\",\"refund_id\":\"refundProvider1\",\"status\":\""+state+"\",\"success_time\":\"2026-10-12T00:00:00Z\",\"create_time\":\"2026-10-11T00:00:00Z\",\"amount\":{\"total\":123,\"refund\":123,\"currency\":\"CNY\"}}";}
    CheckoutRequest checkout(){return new CheckoutRequest("order1","trade1","家庭沟通",123,"CNY","127.0.0.1",notify);}
    @Test void checkoutUsesExactUtf8SignedBodyAndOnlyTrustedLink()throws Exception{
        response="{\"h5_url\":\"https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=test\"}";
        Checkout result=provider.createCheckout(checkout());assertTrue(result.checkoutUrl().startsWith("https://wx.tenpay.com/"));assertEquals("trade1",result.providerReference());assertEquals("家庭沟通",mapper.readTree(lastBody).path("description").asText());assertEquals("/v3/pay/transactions/h5",lastPath);
        response="{\"h5_url\":\"https://wx.tenpay.com.evil.example/\"}";assertThrows(ApiException.class,()->provider.createCheckout(checkout()));
    }
    @Test void verifiedQueryIsStableAndPendingOrRefundStateNeverGrants(){
        response=payment("SUCCESS");ProviderEvent paid=provider.queryPayment("trade1");assertEquals(EventType.PAID,paid.type());assertEquals(paid,provider.queryPayment("trade1"));assertEquals("/v3/pay/transactions/out-trade-no/trade1?mchid=1900000001",lastPath);
        for(String state:List.of("NOTPAY","USERPAYING","REFUND")){response=payment(state);assertNull(provider.queryPayment("trade1"));}
        response=payment("CLOSED");assertEquals(provider.queryPayment("trade1"),provider.queryPayment("trade1"));
    }
    @Test void rejectsTamperUnknownKeyOldTimestampSignatureProbeAndWrongMerchant(){
        response=payment("SUCCESS");tamper=true;assertThrows(ApiException.class,()->provider.queryPayment("trade1"));tamper=false;
        keyId="PUB_KEY_ID_unknown";assertThrows(ApiException.class,()->provider.queryPayment("trade1"));keyId="PUB_KEY_ID_primary";
        timestamp=Long.toString(Instant.now().minusSeconds(600).getEpochSecond());assertThrows(ApiException.class,()->provider.queryPayment("trade1"));timestamp=null;
        signatureOverride="WECHATPAY/SIGNTEST/fixture";assertThrows(ApiException.class,()->provider.queryPayment("trade1"));signatureOverride=null;
        response=payment("SUCCESS").replace("1900000001","1900000002");assertThrows(ApiException.class,()->provider.queryPayment("trade1"));
        response=payment("SUCCESS").replace("\"total\":123","\"total\":123.0");assertThrows(ApiException.class,()->provider.queryPayment("trade1"));
    }
    @Test void rotatingPinnedKeysAndEmpty204AreVerified(){
        response=payment("SUCCESS");keyId="PUB_KEY_ID_rotated";assertEquals(EventType.PAID,provider.queryPayment("trade1").type());status=204;provider.close("trade1");assertTrue(lastPath.endsWith("/close"));
        keyId="MERCHANT_SERIAL";assertThrows(ApiException.class,()->provider.close("trade1"));
    }
    @Test void refundAcceptanceDoesNotGrantRefundAndRetriesKeepSameId(){
        response=refund("PROCESSING");var request=new RefundRequest("trade1","refund1",123,123,"CNY","用户申请退款");
        assertEquals("PROCESSING",provider.refund(request).status());String first=lastBody;provider.refund(request);assertEquals(first,lastBody);assertNull(provider.queryRefund("refund1"));
        response=refund("SUCCESS");var event=provider.queryRefund("refund1");assertEquals(EventType.REFUNDED,event.type());assertEquals(123,event.refundAmountMinor());assertEquals(event,provider.queryRefund("refund1"));
        response=refund("SUCCESS").replace(",\"currency\":\"CNY\"", "");assertThrows(ApiException.class,()->provider.queryRefund("refund1"));
        response=refund("SUCCESS").replace("\"refund\":123","\"refund\":124");assertThrows(ApiException.class,()->provider.queryRefund("refund1"));
    }
    byte[] encryptedNotification(String plaintext,String type,String aad)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,"0123456789ab".getBytes(StandardCharsets.UTF_8)));cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        var resource=mapper.createObjectNode().put("algorithm","AEAD_AES_256_GCM").put("original_type",type.startsWith("REFUND")?"refund":"transaction").put("nonce","0123456789ab").put("associated_data",aad).put("ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8))));
        var notice=mapper.createObjectNode().put("id","notification-123").put("create_time","2026-10-12T00:00:00Z").put("event_type",type).put("resource_type","encrypt-resource");notice.set("resource",resource);return mapper.writeValueAsBytes(notice);
    }
    @Test void notificationAuthenticatesRawWhitespaceThenDecryptsAndKeepsStableEventId()throws Exception{
        byte[] encrypted=encryptedNotification(payment("SUCCESS"),"TRANSACTION.SUCCESS","");byte[] raw=(" \n"+new String(encrypted,StandardCharsets.UTF_8)+"\n ").getBytes(StandardCharsets.UTF_8);
        var event=provider.verifyNotification(headers(raw),raw);assertEquals(EventType.PAID,event.type());assertEquals("notification-123",event.eventId());
        assertThrows(ApiException.class,()->provider.verifyNotification(headers(raw),encrypted));
        var json=mapper.readTree(encrypted);((com.fasterxml.jackson.databind.node.ObjectNode)json.path("resource")).put("associated_data","wrong");byte[] wrongAad=mapper.writeValueAsBytes(json);assertThrows(ApiException.class,()->provider.verifyNotification(headers(wrongAad),wrongAad));
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.path("resource")).put("associated_data", "");
        String ciphertext=json.path("resource").path("ciphertext").asText();byte[] broken=Base64.getDecoder().decode(ciphertext);broken[broken.length-1]^=1;((com.fasterxml.jackson.databind.node.ObjectNode)json.path("resource")).put("ciphertext",Base64.getEncoder().encodeToString(broken));byte[] badTag=mapper.writeValueAsBytes(json);assertThrows(ApiException.class,()->provider.verifyNotification(headers(badTag),badTag));
        byte[] mismatch=encryptedNotification(payment("NOTPAY"),"TRANSACTION.SUCCESS","");assertThrows(ApiException.class,()->provider.verifyNotification(headers(mismatch),mismatch));
    }
    @Test void refundNoticeMatchesMerchantAndSupportsOfficialResourceWithoutAppid()throws Exception{
        String body=refund("SUCCESS").replace(",\"currency\":\"CNY\"", "").replace("\"status\"","\"refund_status\"").replace("{\"out_trade_no\"","{\"mchid\":\"1900000001\",\"out_trade_no\"");
        byte[] raw=encryptedNotification(body,"REFUND.SUCCESS","refund");assertEquals(EventType.REFUNDED,provider.verifyNotification(headers(raw),raw).type());
        byte[] bad=encryptedNotification(body.replace("1900000001","1900000002"),"REFUND.SUCCESS","refund");assertThrows(ApiException.class,()->provider.verifyNotification(headers(bad),bad));
        byte[] wrongCurrency=encryptedNotification(body.replace("\"total\":123","\"total\":123,\"currency\":\"USD\""),"REFUND.SUCCESS","refund");assertThrows(ApiException.class,()->provider.verifyNotification(headers(wrongCurrency),wrongCurrency));
        byte[] wrongApp=encryptedNotification(body.replace("\"mchid\":","\"appid\":\"wrongApp\",\"mchid\":"),"REFUND.SUCCESS","refund");assertThrows(ApiException.class,()->provider.verifyNotification(headers(wrongApp),wrongApp));
    }
    @Test void hardDeadlineBoundedBodyAndConcurrencyFailClosed()throws Exception{
        response=payment("SUCCESS");delay=1600;long start=System.nanoTime();assertThrows(ApiException.class,()->provider.queryPayment("trade1"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1450);
        delay=0;response="x".repeat(65537);assertThrows(ApiException.class,()->provider.queryPayment("trade1"));
        response=payment("SUCCESS");delay=600;int previous=calls.get();var pending=CompletableFuture.runAsync(()->provider.queryPayment("trade1"));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(calls.get()==previous&&System.nanoTime()<deadline)Thread.sleep(5);assertThrows(ApiException.class,()->provider.queryPayment("trade1"));pending.get(2,TimeUnit.SECONDS);
    }
    @Test void enabledSpringWiringUsesOnlyWechatProviderAndInvalidPathsNeverSend(){
        Map<String,Object> settings=new HashMap<>();settings.put("cardtalk.billing-provider","wechat-h5");settings.put("cardtalk.wechat-mchid","1900000001");settings.put("cardtalk.wechat-appid","wxApp1");settings.put("cardtalk.wechat-merchant-serial","MERCHANT_SERIAL");settings.put("cardtalk.wechat-private-key-file",temp.resolve("merchant.pem").toString());settings.put("cardtalk.wechat-public-keys-file",temp.resolve("keys.json").toString());settings.put("cardtalk.wechat-api-v3-key",aes);settings.put("cardtalk.billing-notify-url",notify);settings.put("cardtalk.wechat-endpoint",config(1).endpoint());settings.put("cardtalk.wechat-allow-loopback-http",true);
        try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",settings));context.registerBean(ObjectMapper.class,()->mapper);context.register(NoPaymentProvider.Defaults.class,WechatH5PaymentProvider.Wiring.class);context.refresh();assertEquals(1,context.getBeansOfType(PaymentProvider.class).size());assertInstanceOf(WechatH5PaymentProvider.class,context.getBean(PaymentProvider.class));}
        int before=calls.get();assertThrows(ApiException.class,()->provider.queryPayment("trade%2F.."));assertThrows(ApiException.class,()->provider.queryPayment("trade?mchid=other"));assertEquals(before,calls.get());
    }
    @Test void defaultNoneAndBadConfigurationDoNotSilentlyFallback(){
        try(var context=new AnnotationConfigApplicationContext()){context.register(NoPaymentProvider.Defaults.class,WechatH5PaymentProvider.Wiring.class);context.refresh();assertInstanceOf(NoPaymentProvider.class,context.getBean(PaymentProvider.class));assertThrows(ApiException.class,()->context.getBean(PaymentProvider.class).queryPayment("trade1"));}
        for(String mode:List.of("unknown","wechat-h5")){try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("cardtalk.billing-provider",mode)));context.registerBean(ObjectMapper.class,()->mapper);context.register(NoPaymentProvider.Defaults.class,WechatH5PaymentProvider.Wiring.class);assertThrows(Exception.class,context::refresh);}}
        var bad=config(1);assertThrows(IllegalArgumentException.class,()->new WechatH5PaymentProvider(mapper,new WechatH5PaymentProvider.Config(bad.merchantId(),bad.appId(),bad.merchantSerial(),bad.privateKeyFile(),bad.publicKeysFile(),"short",bad.notifyUrl(),bad.endpoint(),true,1,1)));
    }
}
