package com.cardtalk.operationsmetrics;

import com.cardtalk.api.Application;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricsHttpTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    final HttpClient client=HttpClient.newHttpClient();
    ServletWebServerApplicationContext app;HttpServer upstream;ExecutorService workers;
    String base,operatorToken,ownerToken;
    final AtomicReference<CountDownLatch> entered=new AtomicReference<>(),release=new AtomicReference<>();
    final AtomicBoolean fail=new AtomicBoolean();
    void start(boolean enabled)throws Exception {
        var repo=new JsonFamilyRepository(mapper,directory.resolve("families.json").toString());var families=new FamilyService(repo,true,3600);
        var operator=families.register("operator","test-password-123","ops");var owner=families.register("owner","test-password-123","owner");families.createFamily(owner.account(),"PRIVATE_FAMILY_NAME");operatorToken=operator.token();ownerToken=owner.token();
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);workers=Executors.newCachedThreadPool();upstream.setExecutor(workers);
        upstream.createContext("/chat/completions",exchange->{String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);try {
            if(entered.get()!=null)entered.get().countDown();if(release.get()!=null)release.get().await(5,TimeUnit.SECONDS);
            String content=request.contains("汇总统计")?"{\"text\":\"AI 统计说明：没有已确认表达。\"}":"{\"candidates\":[{\"text\":\"PRIVATE_CANDIDATE\"}],\"needsClarification\":false,\"clarification\":\"\"}";
            byte[] bytes=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content))),"usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15)));
            exchange.sendResponseHeaders(fail.get()?503:200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}});
        upstream.createContext("/speech",exchange->{exchange.sendResponseHeaders(400,-1);exchange.close();});upstream.start();String provider="http://127.0.0.1:"+upstream.getAddress().getPort();
        app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(Application.class).run(
            "--server.port=0","--server.address=127.0.0.1","--cardtalk.metrics-enabled="+enabled,
            "--cardtalk.family-repository-mode=json","--cardtalk.family-data-file="+directory.resolve("families.json"),
            "--cardtalk.trial-repository-mode=json","--cardtalk.data-file="+directory.resolve("trial.json"),
            "--cardtalk.asset-store-mode=local","--cardtalk.asset-metadata-mode=json","--cardtalk.asset-directory="+directory.resolve("assets"),
            "--cardtalk.billing-provider=none","--cardtalk.billing-enforced=false","--cardtalk.metrics-operator-account-ids="+operator.account().id(),
            "--cardtalk.support-enabled=false","--cardtalk.cloud-enabled=false","--cardtalk.report-quota-mode=memory","--cardtalk.analytics-enabled=true",
            "--cardtalk.llm-base-url="+provider,"--cardtalk.llm-model=fixture","--cardtalk.llm-api-key=PRIVATE_KEY","--cardtalk.llm-timeout-seconds=10",
            "--cardtalk.xfyun-endpoint=ws://127.0.0.1:"+upstream.getAddress().getPort()+"/speech","--cardtalk.xfyun-app-id=fixture","--cardtalk.xfyun-api-key=PRIVATE_TTS_KEY","--cardtalk.xfyun-api-secret=PRIVATE_SECRET","--cardtalk.xfyun-vcn=fixture",
            "--logging.level.root=ERROR","--spring.main.banner-mode=off");
        base="http://127.0.0.1:"+app.getWebServer().getPort();
    }
    @AfterEach void close(){if(app!=null)app.close();if(upstream!=null)upstream.stop(0);if(workers!=null)workers.shutdownNow();}
    HttpRequest request(String path,String token,String body){var b=HttpRequest.newBuilder(URI.create(base+path));if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));return b.build();}
    HttpResponse<String> send(String path,String token,String body)throws Exception{return client.send(request(path,token,body),HttpResponse.BodyHandlers.ofString());}
    JsonNode metrics()throws Exception {var response=send("/api/v1/operations/metrics",operatorToken,null);assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));return mapper.readTree(response.body());}
    JsonNode category(JsonNode root,String section,String name){for(var value:root.path(section))if(value.path("category").asText().equals(name))return value;throw new AssertionError("Missing category "+name);}
    @Test void realHttpOperatorIdentityProviderCountersAndPayloadFreeSnapshots()throws Exception {
        start(true);
        assertEquals(401,send("/api/v1/operations/metrics",null,null).statusCode());assertEquals(403,send("/api/v1/operations/metrics",ownerToken,null).statusCode());
        var cookie=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/operations/metrics")).header("Cookie","cardtalk_family="+operatorToken).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,cookie.statusCode());assertEquals("no-store",cookie.headers().firstValue("Cache-Control").orElse(""));
        String trial=mapper.readTree(send("/api/v1/trial-session",null,"{\"clientId\":\"PRIVATE_CLIENT\"}").body()).path("token").asText();
        assertEquals(401,send("/api/v1/operations/metrics",trial,null).statusCode());
        String input="{\"requestId\":\"PRIVATE_REQUEST\",\"inputVersion\":1,\"roomId\":\"PRIVATE_ROOM\",\"cards\":[{\"id\":\"apple\",\"label\":\"PRIVATE_CARD\"}],\"context\":{\"preset\":\"children\",\"participants\":[]}}";
        entered.set(new CountDownLatch(1));release.set(new CountDownLatch(1));var running=client.sendAsync(request("/api/v1/expansions",trial,input),HttpResponse.BodyHandlers.ofString());assertTrue(entered.get().await(5,TimeUnit.SECONDS));
        var during=metrics();assertEquals(1,category(during,"api","expansion").path("inFlight").asLong());assertEquals(1,category(during,"providers","expansion_llm").path("inFlight").asLong());release.get().countDown();assertEquals(200,running.get(5,TimeUnit.SECONDS).statusCode());
        entered.set(null);release.set(null);assertEquals(200,send("/api/v1/expansions",trial,input).statusCode()); // cached retry is not a provider call
        fail.set(true);assertEquals(502,send("/api/v1/expansions",trial,input.replace("PRIVATE_REQUEST","PRIVATE_FAILURE")).statusCode());fail.set(false);
        assertEquals(502,send("/api/v1/speech",trial,"{\"requestId\":\"PRIVATE_SPEECH\",\"text\":\"PRIVATE_AUDIO_TEXT\"}").statusCode());
        var facts=new LinkedHashMap<String,Integer>();for(String field:List.of("confirmedCount","roomCount","activeDays","understood","clarify","notUnderstood","unrecorded","raw","ai","edited","saved","processCollected","processMissing"))facts.put(field,0);
        assertEquals(200,send("/api/v1/reports/narrative",trial,mapper.writeValueAsString(Map.of("requestId","PRIVATE_REPORT","facts",facts))).statusCode());
        send("/api/v1/families/PRIVATE_FAMILY_ID/members",ownerToken,null);
        var snapshot=metrics();String json=snapshot.toString();for(String privateValue:List.of("PRIVATE_",trial,operatorToken,ownerToken,"/api/","127.0.0.1"))assertFalse(json.contains(privateValue));
        var expansion=category(snapshot,"providers","expansion_llm");assertEquals(2,expansion.path("started").asInt());assertEquals(1,expansion.path("errors").asInt());assertEquals(0,expansion.path("inFlight").asInt());assertEquals("partial",expansion.path("usage").path("status").asText());assertEquals(15,expansion.path("usage").path("totalTokens").asInt());
        var speech=category(snapshot,"providers","speech_tts");assertEquals(1,speech.path("started").asInt());assertEquals(1,speech.path("errors").asInt());assertEquals("unknown",speech.path("usage").path("status").asText());assertTrue(speech.path("usage").path("totalTokens").isNull());
        assertEquals(1,category(snapshot,"providers","report_llm").path("completed").asInt());assertEquals(15,category(snapshot,"providers","report_llm").path("usage").path("totalTokens").asInt());
        assertEquals(3,category(snapshot,"api","expansion").path("completed").asInt());assertEquals(1,category(snapshot,"api","expansion").path("statusClasses").path("5xx").asInt());
        assertEquals("unknown",snapshot.path("cost").path("status").asText());assertTrue(snapshot.path("cost").path("amount").isNull());assertEquals("process_memory",snapshot.path("storage").asText());
        assertEquals(0,new MetricsRegistry(true).snapshot().providers().getFirst().started());
    }
    @Test void disabledEndpointFailsClosedWithNoStore()throws Exception {start(false);var response=send("/api/v1/operations/metrics",operatorToken,null);assertEquals(503,response.statusCode());assertEquals("METRICS_DISABLED",mapper.readTree(response.body()).path("code").asText());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));}
    @Test void usageValidationAndCategoryCardinalityAreBounded()throws Exception {
        var metrics=new MetricsRegistry(true);
        try(var call=MetricsRegistry.provider(metrics,MetricsRegistry.Provider.expansion_llm)){call.usage(mapper.readTree("{\"prompt_tokens\":-1,\"completion_tokens\":2,\"total_tokens\":1}"));call.success();}
        assertEquals("unknown",metrics.snapshot().providers().getFirst().usage().status());
        assertEquals(MetricsRegistry.Api.expansion,MetricsFilter.category("/api/v1/families/arbitrary-id/expansions"));assertEquals(MetricsRegistry.Api.other_api,MetricsFilter.category("/api/v1/arbitrary-sensitive-path"));
        var disabled=new MetricsRegistry(false);try(var call=MetricsRegistry.provider(disabled,MetricsRegistry.Provider.speech_tts)){call.success();}assertEquals(0,disabled.snapshot().providers().get(2).started());
    }
}
