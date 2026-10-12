package com.cardtalk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class SpeechGatewayTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void signatureUsesOfficialCanonicalFields() throws Exception {
        var uri=SpeechGateway.sign(URI.create("wss://example.test/v1/private/test"),"key","secret",Instant.parse("2026-10-12T03:00:00Z"));
        var params=query(uri.getRawQuery());
        assertEquals("Mon, 12 Oct 2026 03:00:00 GMT",params.get("date"));
        assertEquals("example.test",params.get("host"));
        var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var expected=Base64.getEncoder().encodeToString(mac.doFinal("host: example.test\ndate: Mon, 12 Oct 2026 03:00:00 GMT\nGET /v1/private/test HTTP/1.1".getBytes(StandardCharsets.UTF_8)));
        String authorization=new String(Base64.getDecoder().decode(params.get("authorization")),StandardCharsets.UTF_8);
        assertEquals("api_key=\"key\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""+expected+"\"",authorization);
        assertThrows(IllegalArgumentException.class,()->SpeechGateway.sign(URI.create("ws://remote.test/path"),"key","secret",Instant.now()));
    }
    @Test void synthesizesFragmentedFramesAndPreservesConfirmedText() throws Exception {
        try(var server=new FakeSocket(peer->{
            peer.send(frame(0,0,new byte[]{73,68}),false,1);
            peer.send("",true,0); // Completes one fragmented WebSocket text message.
            peer.send(frame(1,2,new byte[]{51,1,2}),true,1);
        })) {
            var gateway=gateway(server,2);
            assertArrayEquals(new byte[]{73,68,51,1,2},gateway.synthesize("identity","request","不要碰我",0.5,null));
            var payload=mapper.readTree(server.request.get(2,TimeUnit.SECONDS));
            assertEquals("app",payload.path("header").path("app_id").asText());
            assertEquals(2,payload.path("header").path("status").asInt());
            assertEquals("lame",payload.at("/parameter/tts/audio/encoding").asText());
            assertEquals(24000,payload.at("/parameter/tts/audio/sample_rate").asInt());
            assertEquals(0,payload.at("/parameter/tts/speed").asInt());
            assertEquals(0,payload.at("/parameter/oral/spark_assist").asInt());
            assertEquals(1,payload.at("/parameter/oral/remain").asInt());
            assertEquals("不要碰我",new String(Base64.getDecoder().decode(payload.at("/payload/text/text").asText()),StandardCharsets.UTF_8));
            assertFalse(payload.path("parameter").path("tts").has("emotion"));
            assertTrue(server.target.get().contains("authorization="));
        }
    }
    @Test void rejectsUnauthorizedVoiceMalformedAudioAndEarlyClose() throws Exception {
        for(String message:List.of("{\"header\":{\"code\":11200}}","not-json",frame(0,2,new byte[0]),frame(-1,2,new byte[]{1}),frame(10_000_000,2,new byte[]{1}))) {
            try(var server=new FakeSocket(peer->peer.send(message,true,1))) {
                String code=assertThrows(ApiException.class,()->gateway(server,2).synthesize("id","r1","你好",null,null)).code;
                assertEquals(message.contains("11200")?"TTS_VOICE_NOT_AUTHORIZED":"TTS_INVALID_RESPONSE",code);
            }
        }
        try(var server=new FakeSocket(peer->{})) {
            assertEquals("TTS_INVALID_RESPONSE",assertThrows(ApiException.class,()->gateway(server,2).synthesize("id","r1","你好",null,null)).code);
        }
    }
    @Test void acceptsFirstSequenceOneWithoutLosingAudio() throws Exception {
        try(var server=new FakeSocket(peer->{
            peer.send(frame(1,0,new byte[]{73,68}),true,1);
            peer.send(frame(2,2,new byte[]{51,1,2}),true,1);
        })) {
            assertArrayEquals(new byte[]{73,68,51,1,2},gateway(server,2).synthesize("id","r1","你好",null,null));
        }
    }
    @Test void rejectsSkippedOrRepeatedSubsequentSequence() throws Exception {
        for(int next:List.of(1,3)) {
            try(var server=new FakeSocket(peer->{
                peer.send(frame(1,0,new byte[]{73,68}),true,1);
                peer.send(frame(next,2,new byte[]{51}),true,1);
            })) {
                assertEquals("TTS_INVALID_RESPONSE",assertThrows(ApiException.class,()->gateway(server,2).synthesize("id","r1","你好",null,null)).code);
            }
        }
    }
    @Test void timeoutAndExplicitCancellationAbortUpstream() throws Exception {
        try(var server=new FakeSocket(peer->{ peer.in.read(); })) {
            assertEquals("TTS_TIMEOUT",assertThrows(ApiException.class,()->gateway(server,1).synthesize("id","r1","你好",null,null)).code);
        }
        try(var server=new FakeSocket(peer->{ peer.in.read(); });var executor=Executors.newSingleThreadExecutor()) {
            var gateway=gateway(server,5);
            var future=executor.submit(()->gateway.synthesize("id","cancel-me","你好",null,null));
            server.request.get(2,TimeUnit.SECONDS);
            assertFalse(gateway.cancel("different-id","cancel-me"));
            assertTrue(gateway.cancel("id","cancel-me"));
            var error=assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));
            assertEquals("TTS_CANCELLED",((ApiException)error.getCause()).code);
            assertEquals("TTS_CANCELLED",assertThrows(ApiException.class,()->gateway.synthesize("id","cancel-me","你好",null,null)).code);
        }
    }
    @Test void interruptionRestoresInterruptStatus() throws Exception {
        try(var server=new FakeSocket(peer->{ peer.in.read(); })) {
            var failure=new CompletableFuture<String>();
            var thread=new Thread(()->{
                try { gateway(server,5).synthesize("id","r1","你好",null,null); failure.complete("unexpected-success"); }
                catch(ApiException ex) { failure.complete(ex.code+":"+Thread.currentThread().isInterrupted()); }
            });
            thread.start(); server.request.get(2,TimeUnit.SECONDS); thread.interrupt();
            assertEquals("TTS_CANCELLED:true",failure.get(2,TimeUnit.SECONDS)); thread.join(1000);
        }
    }
    @Test void unsupportedEmotionNeverContactsProvider() throws Exception {
        try(var server=new FakeSocket(peer->{})) {
            assertEquals("TTS_EMOTION_UNSUPPORTED",assertThrows(ApiException.class,()->gateway(server,2).synthesize("id","r1","你好",null,"happy")).code);
            assertFalse(server.request.isDone());
        }
    }
    @Test void voiceCloneUsesRealStyleMappingAndRequiresResource() throws Exception {
        for(var pair:Map.of("happy","glad","sad","sad","angry","angry","fearful","afraid","neutral","").entrySet()) {
            try(var server=new FakeSocket(peer->peer.send(frame(0,2,new byte[]{73,68,51}),true,1))) {
                var gateway=new SpeechGateway(mapper,"ws://127.0.0.1:"+server.server.getLocalPort()+"/v1/private/voice_clone","app","key","secret","ignored",2,"voice-clone","authorized-omni-v1-resource");
                assertTrue(gateway.emotionSupported());
                gateway.synthesize("id","r1","不要碰我",1.0,pair.getKey());
                var payload=mapper.readTree(server.request.get(2,TimeUnit.SECONDS));
                assertEquals("authorized-omni-v1-resource",payload.at("/header/res_id").asText());
                assertEquals("x6_clone",payload.at("/parameter/tts/vcn").asText());
                assertEquals(0,payload.at("/parameter/tts/languageID").asInt());
                assertFalse(payload.path("parameter").has("oral"));
                if(pair.getKey().equals("neutral")) assertTrue(payload.at("/parameter/tts/style").isMissingNode());
                else assertEquals(pair.getValue(),payload.at("/parameter/tts/style").asText());
            }
        }
        var incomplete=new SpeechGateway(mapper,"","app","key","secret","ignored",2,"voice-clone","");
        assertFalse(incomplete.configured()); assertFalse(incomplete.emotionSupported());
    }
    @Test void earlyCancellationIsRememberedAndScoped() throws Exception {
        try(var server=new FakeSocket(peer->peer.send(frame(0,2,new byte[]{1}),true,1))) {
            var gateway=gateway(server,2);
            assertFalse(gateway.cancel("one","early"));
            assertEquals("TTS_CANCELLED",assertThrows(ApiException.class,()->gateway.synthesize("one","early","你好",null,null)).code);
            assertArrayEquals(new byte[]{1},gateway.synthesize("two","early","你好",null,null));
        }
    }
    private SpeechGateway gateway(FakeSocket server,int seconds) {
        return new SpeechGateway(mapper,"ws://127.0.0.1:"+server.server.getLocalPort()+"/v1/private/test","app","key","secret","x6_lingxiaoxuan_pro",seconds,"super-smart","");
    }
    private String frame(int seq,int status,byte[] audio) throws Exception {
        return mapper.writeValueAsString(Map.of("header",Map.of("code",0,"status",status),"payload",Map.of("audio",Map.of("encoding","lame","status",status,"seq",seq,"audio",Base64.getEncoder().encodeToString(audio)))));
    }
    static Map<String,String> query(String raw) {
        var result=new HashMap<String,String>();
        for(String part:raw.split("&")) { var pair=part.split("=",2); result.put(pair[0],URLDecoder.decode(pair[1],StandardCharsets.UTF_8)); }
        return result;
    }
    @FunctionalInterface interface Script { void run(Peer peer) throws Exception; }
    static final class Peer {
        final InputStream in; final OutputStream out;
        Peer(Socket socket) throws Exception { in=socket.getInputStream(); out=socket.getOutputStream(); }
        void send(String message,boolean last,int opcode) throws Exception {
            byte[] data=message.getBytes(StandardCharsets.UTF_8); out.write((last?128:0)|opcode);
            if(data.length<126) out.write(data.length); else { out.write(126); out.write(data.length>>8); out.write(data.length); }
            out.write(data); out.flush();
        }
        String receive() throws Exception {
            in.read(); int flags=in.read(); int length=flags&127;
            if(length==126) length=(in.read()<<8)|in.read();
            if(length==127) throw new IOException("Test frame too large");
            byte[] mask=(flags&128)!=0?in.readNBytes(4):new byte[4];
            byte[] data=in.readNBytes(length);
            for(int i=0;i<data.length;i++) data[i]^=mask[i%4];
            return new String(data,StandardCharsets.UTF_8);
        }
    }
    static final class FakeSocket implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final CompletableFuture<String> request=new CompletableFuture<>();
        final AtomicReference<String> target=new AtomicReference<>();
        final Thread worker;
        volatile Socket connection;
        FakeSocket(Script script) throws Exception {
            worker=new Thread(()->{
                try(Socket socket=server.accept()) {
                    connection=socket;socket.setSoTimeout(6000);
                    var peer=new Peer(socket); var header=new ByteArrayOutputStream(); int value;
                    while((value=peer.in.read())>=0) { header.write(value); if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break; }
                    String raw=header.toString(StandardCharsets.US_ASCII); target.set(raw.lines().findFirst().orElse(""));
                    String key=raw.lines().filter(l->l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":",2)[1].strip();
                    String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    peer.out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));peer.out.flush();
                    request.complete(peer.receive());script.run(peer);
                } catch(Exception ex) { request.completeExceptionally(ex); }
            }); worker.setDaemon(true); worker.start();
        }
        public void close() throws Exception { server.close(); if(connection!=null) connection.close(); worker.join(2000); }
    }
}
