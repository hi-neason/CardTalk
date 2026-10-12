package com.cardtalk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Xfyun super-human TTS protocol. No speech content, audio or signed URL is logged or persisted. */
@Component
public class SpeechGateway {
    private static final int MAX_AUDIO=5_000_000;
    private static final int MAX_MESSAGE=7_000_000;
    private final ObjectMapper mapper;
    private final String endpoint,appId,key,secret,voice,engine,resourceId;
    private final long timeoutSeconds;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String,Operation> active=new HashMap<>();
    private final LinkedHashMap<String,Instant> cancelled=new LinkedHashMap<>();

    public SpeechGateway(ObjectMapper mapper,@Value("${cardtalk.xfyun-endpoint}") String endpoint,
                         @Value("${cardtalk.xfyun-app-id}") String appId,@Value("${cardtalk.xfyun-api-key}") String key,
                         @Value("${cardtalk.xfyun-api-secret}") String secret,@Value("${cardtalk.xfyun-vcn}") String voice,
                         @Value("${cardtalk.xfyun-timeout-seconds}") long timeoutSeconds,
                         @Value("${cardtalk.xfyun-engine}") String engine,@Value("${cardtalk.xfyun-resource-id}") String resourceId) {
        if(!Set.of("super-smart","voice-clone").contains(engine)) throw new IllegalArgumentException("Unsupported Xfyun engine");
        this.mapper=mapper; this.engine=engine; this.resourceId=resourceId;
        this.endpoint=endpoint.isBlank()?(engine.equals("voice-clone")?"wss://cn-huabei-1.xf-yun.com/v1/private/voice_clone":"wss://cbm01.cn-huabei-1.xf-yun.com/v1/private/mcd9m97e6"):endpoint;
        this.appId=appId; this.key=key; this.secret=secret; this.voice=engine.equals("voice-clone")?"x6_clone":voice;
        if(timeoutSeconds<1 || timeoutSeconds>120) throw new IllegalArgumentException("Speech timeout must be 1–120 seconds");
        this.timeoutSeconds=timeoutSeconds;
    }
    public boolean configured() { return !endpoint.isBlank() && !appId.isBlank() && !key.isBlank() && !secret.isBlank() && !voice.isBlank() && (!engine.equals("voice-clone") || !resourceId.isBlank()); }
    public boolean emotionSupported() { return configured() && engine.equals("voice-clone"); }
    public String engine() { return engine; }
    public byte[] synthesize(String identity,String requestId,String text,Double rate,String emotion) {
        if(!configured()) throw new ApiException(503,"TTS_NOT_CONFIGURED","尚未配置讯飞拟人合成，请在设置中选择设备发声");
        if(emotion!=null && !emotion.equals("neutral") && !emotionSupported())
            throw new ApiException(422,"TTS_EMOTION_UNSUPPORTED","当前讯飞拟人接口未提供可验证的情绪控制，请关闭情绪识别或切换设备发声");
        String id=operationId(identity,requestId);
        var operation=new Operation();
        register(id,operation);
        CompletableFuture<WebSocket> opening=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            URI signed=sign(URI.create(endpoint),key,secret,Instant.now());
            opening=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(Math.min(10,timeoutSeconds)))
                .buildAsync(signed,operation);
            operation.opening=opening;
            if(operation.aborted) opening.cancel(true);
            WebSocket socket=opening.get(remaining(deadline),TimeUnit.NANOSECONDS);
            if(operation.result.isCompletedExceptionally()) return operation.result.get();
            socket.sendText(mapper.writeValueAsString(payload(text,rate,emotion)),true).get(remaining(deadline),TimeUnit.NANOSECONDS);
            return operation.result.get(remaining(deadline),TimeUnit.NANOSECONDS);
        } catch(CancellationException ex) { throw new ApiException(409,"TTS_CANCELLED","语音合成已取消");
        } catch(TimeoutException ex) { throw new ApiException(504,"TTS_TIMEOUT","讯飞语音生成超时，请重试");
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new ApiException(409,"TTS_CANCELLED","语音合成已取消");
        } catch(ExecutionException ex) {
            if(ex.getCause() instanceof ApiException api) throw api;
            throw new ApiException(502,"TTS_UPSTREAM_ERROR","讯飞语音连接或响应失败，请检查服务配置");
        } catch(ApiException ex) { throw ex;
        } catch(Exception ex) { throw new ApiException(502,"TTS_UPSTREAM_ERROR","讯飞语音服务响应不可用");
        } finally {
            operation.abort();
            if(opening!=null && !opening.isDone()) opening.cancel(true);
            synchronized(this) { active.remove(id,operation); }
        }
    }
    public synchronized boolean cancel(String identity,String requestId) {
        pruneCancelled();
        String id=operationId(identity,requestId);
        cancelled.put(id,Instant.now().plusSeconds(120));
        while(cancelled.size()>256) cancelled.remove(cancelled.keySet().iterator().next());
        var operation=active.get(id);
        if(operation==null) return false;
        operation.fail(new ApiException(409,"TTS_CANCELLED","语音合成已取消"));
        return true;
    }
    private synchronized void register(String id,Operation operation) {
        pruneCancelled();
        if(cancelled.containsKey(id)) throw new ApiException(409,"TTS_CANCELLED","语音合成已取消，请创建新的播放请求");
        if(active.containsKey(id)) throw new ApiException(409,"TTS_ALREADY_RUNNING","此语音正在合成");
        if(active.size()>=32) throw new ApiException(503,"TTS_BUSY","语音服务繁忙，请稍后重试");
        active.put(id,operation);
    }
    private void pruneCancelled() { cancelled.values().removeIf(until->!until.isAfter(Instant.now())); }
    private static long remaining(long deadline) throws TimeoutException {
        long time=deadline-System.nanoTime(); if(time<=0) throw new TimeoutException(); return time;
    }
    private static String operationId(String identity,String id) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)))+":"+id; }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    Map<String,Object> payload(String text,Double rate,String emotion) {
        double actual=rate==null?1.0:rate;
        // App rate 0.5/1/2 maps to the vendor's documented 0/50/100 endpoints.
        int speed=(int)Math.round(actual<=1?(actual-0.5)*100:50+(actual-1)*50);
        var tts=new LinkedHashMap<String,Object>(Map.of("vcn",voice,"speed",Math.max(0,Math.min(100,speed)),"volume",50,"pitch",50,"bgs",0,"rhy",0,
            "audio",Map.of("encoding","lame","sample_rate",24000,"channels",1,"bit_depth",16,"frame_size",0)));
        var header=new LinkedHashMap<String,Object>(Map.of("app_id",appId,"status",2));
        var parameters=new LinkedHashMap<String,Object>();
        if(engine.equals("voice-clone")) {
            header.put("res_id",resourceId); tts.put("languageID",0);
            if(emotion!=null && !emotion.equals("neutral")) tts.put("style",Map.of("happy","glad","sad","sad","angry","angry","fearful","afraid").get(emotion));
        } else {
            parameters.put("oral",Map.of("oral_level","low","spark_assist",0,"remain",1));
        }
        parameters.put("tts",tts);
        return Map.of("header",header,
            "parameter",parameters,
            "payload",Map.of("text",Map.of("encoding","utf8","compress","raw","format","plain","status",2,"seq",0,
                "text",Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)))));
    }
    static URI sign(URI endpoint,String key,String secret,Instant now) throws Exception {
        String scheme=endpoint.getScheme();
        boolean loopback=Set.of("127.0.0.1","localhost","[::1]").contains(endpoint.getHost());
        if((!"wss".equals(scheme) && !("ws".equals(scheme)&&loopback)) || endpoint.getRawQuery()!=null || endpoint.getFragment()!=null || endpoint.getUserInfo()!=null)
            throw new IllegalArgumentException("Speech endpoint must be secure WebSocket (loopback ws allowed for tests)");
        String date=DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US).withZone(ZoneOffset.UTC).format(now);
        String host=endpoint.getRawAuthority();
        String canonical="host: "+host+"\ndate: "+date+"\nGET "+endpoint.getRawPath()+" HTTP/1.1";
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        String authorization=Base64.getEncoder().encodeToString(("api_key=\""+key+"\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""+signature+"\"").getBytes(StandardCharsets.UTF_8));
        return URI.create(endpoint+"?authorization="+encode(authorization)+"&date="+encode(date)+"&host="+encode(host));
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private static ApiException invalid() { return new ApiException(502,"TTS_INVALID_RESPONSE","讯飞返回的语音内容不完整或无效"); }

    private final class Operation implements WebSocket.Listener {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final StringBuilder message=new StringBuilder();
        final ByteArrayOutputStream audio=new ByteArrayOutputStream();
        volatile WebSocket socket;
        volatile CompletableFuture<WebSocket> opening;
        volatile boolean aborted;
        int sequence=-1;
        public void onOpen(WebSocket socket) {
            this.socket=socket;
            if(aborted) socket.abort(); else socket.request(1);
        }
        public CompletionStage<?> onText(WebSocket socket,CharSequence fragment,boolean last) {
            if(result.isDone()) return null;
            if(message.length()+fragment.length()>MAX_MESSAGE) { fail(invalid()); return null; }
            message.append(fragment);
            if(last) {
                try {
                    var frame=mapper.readTree(message.toString()); message.setLength(0);
                    var code=frame.path("header").path("code");
                    if(!code.isInt()) throw invalid();
                    if(code.asInt()!=0) {
                        if(code.asInt()==11200) throw new ApiException(502,"TTS_VOICE_NOT_AUTHORIZED","讯飞服务或发音人尚未授权，请检查控制台配置");
                        throw new ApiException(502,"TTS_UPSTREAM_ERROR","讯飞语音合成失败，请检查额度和服务配置");
                    }
                    var chunk=frame.path("payload").path("audio");
                    if(!chunk.isMissingNode()) {
                        if(!chunk.path("audio").isTextual() || !"lame".equals(chunk.path("encoding").asText()) || !chunk.path("status").isInt() || !chunk.path("seq").isInt()) throw invalid();
                        int next=chunk.path("seq").asInt(), status=chunk.path("status").asInt();
                        if(next<0 || next>9_999_999 || (sequence>=0 && next!=sequence+1) || status<0 || status>2) throw invalid();
                        sequence=next;
                        byte[] bytes=Base64.getDecoder().decode(chunk.path("audio").asText());
                        if(bytes.length>MAX_AUDIO-audio.size()) throw invalid();
                        audio.writeBytes(bytes);
                        if(status==2) {
                            if(audio.size()==0) throw invalid();
                            result.complete(audio.toByteArray());
                            socket.sendClose(WebSocket.NORMAL_CLOSURE,"complete");
                        }
                    } else if(frame.path("header").path("status").asInt(-1)==2) throw invalid();
                } catch(ApiException ex) { fail(ex);
                } catch(Exception ex) { fail(invalid()); }
            }
            if(!result.isDone()) socket.request(1);
            return null;
        }
        public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean last) { fail(invalid()); return null; }
        public CompletionStage<?> onClose(WebSocket socket,int status,String reason) { if(!result.isDone()) fail(invalid()); return null; }
        public void onError(WebSocket socket,Throwable error) { fail(new ApiException(502,"TTS_UPSTREAM_ERROR","讯飞语音连接中断，请重试")); }
        void fail(ApiException error) { result.completeExceptionally(error); abort(); }
        void abort() {
            aborted=true; WebSocket current=socket; if(current!=null) current.abort();
            var pending=opening; if(pending!=null && !pending.isDone()) pending.cancel(true);
        }
    }
}
