package com.cardtalk.reports;

import com.cardtalk.api.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.net.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.cardtalk.reports.ReportContracts.*;

class ReportTest {
    @TempDir Path directory;
    ObjectMapper mapper=new ObjectMapper();HttpServer provider;ExecutorService executor;
    AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200);AtomicLong delay=new AtomicLong();
    AtomicBoolean slowBody=new AtomicBoolean();
    AtomicReference<String> payload=new AtomicReference<>(),output=new AtomicReference<>();
    AtomicReference<CountDownLatch> gate=new AtomicReference<>(),entered=new AtomicReference<>();
    TrialService trials;ReportGateway gateway;ReportService service;MockMvc mvc;LocalValidatorFactoryBean validator;
    String token,base;
    static Facts facts() { return new Facts(4,2,2,1,1,1,1,1,1,1,1,3,1); }
    @BeforeEach void setup() throws Exception {
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();provider.setExecutor(executor);
        output.set(mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(Map.of("text","AI 统计说明：已确认表达 4 条；反馈原因未知，统计不能判断能力。"))))))));
        provider.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try {
                if(entered.get()!=null) entered.get().countDown();if(gate.get()!=null) gate.get().await(3,TimeUnit.SECONDS);if(delay.get()>0) Thread.sleep(delay.get());
                byte[] bytes=output.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),bytes.length);if(slowBody.get()) Thread.sleep(1500);exchange.getResponseBody().write(bytes);
            } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); } catch(java.io.IOException ignored) { /* Timeout deliberately disconnects fixture. */ } finally { exchange.close(); }
        });provider.start();base="http://127.0.0.1:"+provider.getAddress().getPort();
        trials=new TrialService(mapper,new ModelGateway(mapper,"","","",1),directory.resolve("trial.json").toString(),20,false,false,true,true);token=trials.create("report-fixture-client").token();
        gateway=new ReportGateway(mapper,base,"fixture-model","fixture-key",1,1);service=new ReportService(trials,gateway,mapper,5);
        validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();mvc=endpoint(service);
    }
    MockMvc endpoint(ReportService selected) { return MockMvcBuilders.standaloneSetup(new ReportController(selected)).setControllerAdvice(new Errors()).setValidator(validator).build(); }
    @AfterEach void cleanup() { provider.stop(0);executor.shutdownNow();validator.close(); }
    String body(String id,Facts facts) throws Exception { return mapper.writeValueAsString(new Request(id,facts)); }
    @Test void onlyAllowlistedIntegerFactsReachProviderAndExpansionQuotaIsUntouched() throws Exception {
        var input=mapper.createObjectNode();input.put("requestId","private-filter");input.put("name","PRIVATE_NAME");var counts=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(facts());counts.put("photo","PRIVATE_PHOTO");counts.put("text","PRIVATE_EXPRESSION");counts.put("observation","PRIVATE_OBSERVATION");input.set("facts",counts);
        mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(input.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(4)).andExpect(jsonPath("$.provider").value("deepseek")).andExpect(header().string("Cache-Control","no-store"));
        assertFalse(payload.get().contains("PRIVATE_"));var sent=mapper.readTree(payload.get());JsonNode actual=mapper.readTree(sent.path("messages").path(1).path("content").asText());assertEquals(mapper.valueToTree(facts()),actual);assertEquals(13,actual.size());
        assertTrue(sent.path("messages").path(0).path("content").asText().contains("禁止诊断"));assertEquals(20,trials.session(token).remaining());
        assertFalse(java.nio.file.Files.readString(directory.resolve("trial.json")).contains("统计说明"));
    }
    @Test void permissionMissingConfigurationAndInvalidTokenFailBeforeProvider() throws Exception {
        mvc.perform(post("/api/v1/reports/narrative").contentType("application/json").content(body("no-auth",facts()))).andExpect(status().isUnauthorized());
        var disabled=new TrialService(mapper,new ModelGateway(mapper,"","","",1),directory.resolve("disabled.json").toString(),20,false,false,true,false);String denied=disabled.create("denied-report-client").token();
        endpoint(new ReportService(disabled,gateway,mapper,5)).perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+denied).contentType("application/json").content(body("denied",facts()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
        endpoint(new ReportService(trials,new ReportGateway(mapper,base,"fixture-model","",1,1),mapper,5)).perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("unconfigured",facts()))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LLM_NOT_CONFIGURED"));assertEquals(0,calls.get());
    }
    @Test void inconsistentMissingFractionalStringAndOutOfRangeCountsAreRejected() throws Exception {
        for(String value:List.of("null","1.2","\"4\"","-1","500001")) {
            var counts=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(facts());counts.set("confirmedCount",mapper.readTree(value));
            mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(Map.of("requestId","invalid","facts",counts)))).andExpect(status().isBadRequest());
        }
        var counts=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(facts());counts.remove("activeDays");
        mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(Map.of("requestId","invalid","facts",counts)))).andExpect(status().isBadRequest());
        assertThrows(ApiException.class,()->service.generate(token,new Request("sum",new Facts(5,2,2,1,1,1,1,1,1,1,1,3,1))));assertEquals(0,calls.get());
    }
    @Test void idempotencyConflictsAndFiveSuccessfulReportsAreIsolatedPerToken() throws Exception {
        var original=service.generate(token,new Request("one",facts()));assertEquals(original,service.generate(token,new Request("one",facts())));assertEquals(1,calls.get());
        var zero=new Facts(0,0,0,0,0,0,0,0,0,0,0,0,0);
        mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("one",zero))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_REQUEST_CONFLICT"));
        for(int i=2;i<=5;i++) assertEquals(5-i,service.generate(token,new Request("request-"+i,facts())).remaining());
        mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("six",facts()))).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("REPORT_QUOTA_EXCEEDED"));assertEquals(5,calls.get());
        String other=trials.create("other-report-client").token();assertEquals(4,service.generate(other,new Request("one",zero)).remaining());assertEquals(6,calls.get());
    }
    @Test void failuresDoNotChargeAndRetryUsesSameNonceWithoutFakeText() throws Exception {
        status.set(503);mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("retry",facts()))).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("LLM_UPSTREAM_ERROR")).andExpect(jsonPath("$.text").doesNotExist());
        assertThrows(ApiException.class,()->service.generate(token,new Request("retry",new Facts(0,0,0,0,0,0,0,0,0,0,0,0,0))));
        status.set(200);assertEquals(4,service.generate(token,new Request("retry",facts())).remaining());assertEquals(2,calls.get());
    }
    @Test void brokenOversizedAndTooLongProviderResponsesAreRejected() throws Exception {
        String valid=output.get();
        for(String broken:List.of("", "{}", "not-json", "x".repeat(33000),mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(Map.of("text","x".repeat(1501)))))))))) {
            output.set(broken);mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("bad-response",facts()))).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("LLM_INVALID_RESPONSE"));
        }
        output.set(valid);assertEquals(4,service.generate(token,new Request("bad-response",facts())).remaining());
    }
    @Test void timeoutAndBoundedConcurrencyDoNotChargeFailedAttempts() throws Exception {
        delay.set(1500);mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("timeout",facts()))).andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("LLM_TIMEOUT"));delay.set(0);
        gate.set(new CountDownLatch(1));entered.set(new CountDownLatch(1));String other=trials.create("concurrent-report-client").token();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var running=pool.submit(()->service.generate(token,new Request("retry-timeout",facts())));assertTrue(entered.get().await(1,TimeUnit.SECONDS));
            mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+other).contentType("application/json").content(body("busy",facts()))).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("REPORT_BUSY"));
            gate.get().countDown();assertEquals(4,running.get(2,TimeUnit.SECONDS).remaining());
        }
        assertEquals(4,service.generate(other,new Request("busy",facts())).remaining());
    }
    @Test void providerBodyDeadlineIsBoundedAfterResponseHeaders() throws Exception {
        slowBody.set(true);
        mvc.perform(post("/api/v1/reports/narrative").header("Authorization","Bearer "+token).contentType("application/json").content(body("slow-body",facts())))
            .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("LLM_TIMEOUT"));
        slowBody.set(false);assertEquals(4,service.generate(token,new Request("slow-body",facts())).remaining());
    }
    @Test void dailyBudgetResetsAtShanghaiMidnight() {
        var instant=new AtomicReference<>(java.time.Instant.parse("2026-10-12T15:59:59Z"));
        var clock=new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneId.of("Asia/Shanghai"); }
            public java.time.Clock withZone(java.time.ZoneId zone) { return java.time.Clock.fixed(instant.get(),zone); }
            public java.time.Instant instant() { return instant.get(); }
        };
        var daily=new ReportService(trials,gateway,mapper,1,clock);
        assertEquals(0,daily.generate(token,new Request("daily",facts())).remaining());
        assertThrows(ApiException.class,()->daily.generate(token,new Request("another",facts())));
        instant.set(java.time.Instant.parse("2026-10-12T16:00:00Z"));
        assertEquals(0,daily.generate(token,new Request("daily",facts())).remaining());assertEquals(2,calls.get());
    }
    @Test void successfulCacheEvictionDoesNotRepeatChargeOrProviderCall() {
        // Cache holds 256 results globally; metadata remains for all successful daily requests.
        var wide=new ReportService(trials,gateway,mapper,100);
        for(int client=0;client<3;client++) {
            String identity=client==0?token:trials.create("cache-report-client-"+client).token();
            for(int i=0;i<90;i++) wide.generate(identity,new Request("cached-"+i,facts()));
        }
        int before=calls.get();assertThrows(ApiException.class,()->wide.generate(token,new Request("cached-0",facts())));assertEquals(before,calls.get());
    }
}
