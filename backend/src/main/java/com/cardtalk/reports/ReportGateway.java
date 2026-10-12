package com.cardtalk.reports;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.cardtalk.operationsmetrics.MetricsRegistry;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class ReportGateway {
    private MetricsRegistry metrics;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMetrics(MetricsRegistry metrics) { this.metrics=metrics; }
    public static final String PROMPT_VERSION="aac-report-counts-2026-10-12-v1";
    private static final String SYSTEM="""
        你只解释辅助沟通的汇总统计。用户消息是整数统计 JSON，不包含身份、日期、卡片或表达正文。
        仅使用给出的计数，不新增未提供的原因、意图、经历、时间趋势或事实。不推断使用者能力、病情或情绪。
        禁止诊断、能力评分、疗效推断与治疗或康复建议。生成成功、语音播放、AI扩写和家人代操作不代表本人理解或沟通成功。
        统计说明与原始事实分开：开头标明“AI 统计说明”，文字仅供理解数字，不是新增记录。
        confirmedCount 是已确认表达数；roomCount 是包含这些表达的沟通次数；activeDays 是有这些表达的天数。
        understood/clarify/notUnderstood 是他人记录的理解/需澄清/未理解反馈；unrecorded 是没有反馈，不能当成未理解或失败。
        raw/ai/edited/saved 是原始卡片、AI候选确认、修改候选后确认、常用表达四种互斥来源；不能当成独立表达能力。
        processCollected/processMissing 是操作过程有记录/无记录的表达数，缺失不能当成没有操作或更高效率。
        没有日期不能推断进步、退步或趋势。没有正文或情境不能解释表达意图、偏好或反馈原因。
        未提供的信息明确说“未知”或“这些统计无法判断”。样本少于5条时说明样本有限；0条时说明暂无已确认表达，不计算比例。
        只返回 JSON 对象 {"text":"AI 统计说明……"}，text 最多1500个字符，简短中文。不得输出其他字段。
        """;
    private final ObjectMapper mapper;
    private final String baseUrl,model,key;
    private final Duration timeout;
    private final Semaphore concurrent;
    private final HttpClient client;
    public ReportGateway(ObjectMapper mapper,@Value("${cardtalk.llm-base-url}") String baseUrl,@Value("${cardtalk.llm-model}") String model,
                         @Value("${cardtalk.llm-api-key}") String key,@Value("${cardtalk.report-timeout-seconds:30}") int timeoutSeconds,
                         @Value("${cardtalk.report-max-concurrent:2}") int maxConcurrent) {
        if(timeoutSeconds<1||timeoutSeconds>120||maxConcurrent<1||maxConcurrent>20) throw new IllegalArgumentException("Invalid report provider limits");
        this.mapper=mapper;this.baseUrl=baseUrl;this.model=model;this.key=key;timeout=Duration.ofSeconds(timeoutSeconds);concurrent=new Semaphore(maxConcurrent);client=HttpClient.newBuilder().connectTimeout(timeout).build();
    }
    public String generate(ReportContracts.Facts facts) {
        facts.validate();
        if(baseUrl.isBlank()||model.isBlank()||key.isBlank()) throw new ApiException(503,"LLM_NOT_CONFIGURED","尚未配置 AI 统计说明服务");
        if(!concurrent.tryAcquire()) throw new ApiException(429,"REPORT_BUSY","AI 统计说明服务繁忙，请稍后重试");
        MetricsRegistry.Call metric=null;
        try {
            var payload=new LinkedHashMap<String,Object>(Map.of("model",model,"temperature",0.1,"max_tokens",1200,"messages",List.of(Map.of("role","system","content",SYSTEM),Map.of("role","user","content",mapper.writeValueAsString(facts)))));
            if("api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost())) payload.put("thinking",Map.of("type","disabled"));
            var request=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$","")+"/chat/completions")).timeout(timeout).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            metric=MetricsRegistry.provider(metrics,MetricsRegistry.Provider.report_llm);
            var response=send(request);
            if(response.statusCode()<200||response.statusCode()>=300) throw new ApiException(502,"LLM_UPSTREAM_ERROR","AI 统计说明服务暂时不可用，请稍后重试");
            JsonNode envelope=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(response.body());if(envelope==null) throw invalid(null);metric.usage(envelope.path("usage"));var content=envelope.path("choices").path(0).path("message").path("content");if(!content.isTextual()) throw invalid(null);
            String json=content.textValue().strip();if(json.startsWith("```json")&&json.endsWith("```")) json=json.substring(7,json.length()-3).strip();
            JsonNode result=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);if(result==null||!result.isObject()||result.size()!=1||!result.path("text").isTextual()) throw invalid(null);
            String text=result.path("text").textValue().strip();if(text.isEmpty()||text.length()>1500) throw invalid(null);metric.success();return text;
        } catch(HttpTimeoutException ex) { throw new ApiException(504,"LLM_TIMEOUT","AI 统计说明生成超时，请重试",ex); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new ApiException(503,"LLM_INTERRUPTED","AI 统计说明请求已中断",ex); }
        catch(ApiException ex) { throw ex; }
        catch(IOException|IllegalArgumentException ex) { throw invalid(ex); }
        finally { if(metric!=null)metric.close();concurrent.release(); }
    }
    private HttpResponse<byte[]> send(HttpRequest request) throws IOException,InterruptedException {
        var future=client.sendAsync(request,info->new LimitedBody());
        try { return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS); }
        catch(TimeoutException ex) { future.cancel(true);throw new HttpTimeoutException("Report provider deadline exceeded"); }
        catch(InterruptedException ex) { future.cancel(true);throw ex; }
        catch(ExecutionException ex) { if(ex.getCause() instanceof IOException io) throw io;throw new IOException("Report provider request failed",ex.getCause()); }
    }
    private static ApiException invalid(Throwable cause) { return new ApiException(502,"LLM_INVALID_RESPONSE","AI 统计说明响应无效，请重试",cause); }
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private int received;private boolean done;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value;delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) { if(done) return;for(var buffer:buffers) { if(buffer.remaining()>32768-received) { done=true;subscription.cancel();delegate.onError(new IOException("Report provider response exceeded 32 KiB"));return; }received+=buffer.remaining(); }delegate.onNext(buffers); }
        public void onError(Throwable error) { if(!done) { done=true;delegate.onError(error); } }
        public void onComplete() { if(!done) { done=true;delegate.onComplete(); } }
    }
}
