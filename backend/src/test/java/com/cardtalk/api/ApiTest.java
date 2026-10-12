package com.cardtalk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.cardtalk.api.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiTest {
    @TempDir Path directory;
    ObjectMapper mapper=new ObjectMapper();
    HttpServer upstream;
    ModelGateway gateway;
    TrialService trials;
    MockMvc mvc;
    AtomicInteger calls=new AtomicInteger();
    AtomicReference<String> content=new AtomicReference<>("{\"candidates\":[{\"text\":\"我不想吃苹果\"}],\"needsClarification\":false,\"clarification\":\"\"}");
    AtomicReference<String> sent=new AtomicReference<>();
    AtomicInteger status=new AtomicInteger(200);
    AtomicInteger delay=new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/v1/chat/completions", exchange->{
            calls.incrementAndGet(); sent.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { Thread.sleep(delay.get()); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] data=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content.get())))));
            exchange.sendResponseHeaders(status.get(),data.length);
            try(var out=exchange.getResponseBody()) { out.write(data); }
        });
        upstream.start();
        gateway=new ModelGateway(mapper,"http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1","test-model","test-key",1);
        trials=new TrialService(mapper,gateway,directory.resolve("state.json").toString(),2);
        var validator=new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(new ApiController(trials,gateway,new SpeechGateway(mapper,"","","","","",1,"super-smart",""))).setControllerAdvice(new Errors()).setValidator(validator).build();
    }
    @AfterEach void stop() { upstream.stop(0); }
    ExpansionRequest request(String id,int size) {
        var cards=new ArrayList<Card>();
        for(int i=0;i<size;i++) cards.add(new Card("apple","苹果","水果",null));
        return new ExpansionRequest(id,1L,"private-room",cards,new Context("children",new Scene("kitchen","厨房","吃饭"),List.of(new Participant("mom","妈妈")),new Profile("私密姓名",8,"苹果","短句","累"),null));
    }
    String token() { return trials.create("test-client-123").token(); }
    @Test void validTwentyCardsKeepOrderAndRequireConfirmation() throws Exception {
        String token=token();
        mvc.perform(post("/api/v1/expansions").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(request("r1",20))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(1)).andExpect(jsonPath("$.candidates[0].sourceCardIds.length()").value(20));
        assertTrue(sent.get().contains("否定"));
        String persisted=Files.readString(directory.resolve("state.json"));
        for(String secret:List.of("苹果","私密姓名","private-room",token,"test-key","厨房","妈妈")) assertFalse(persisted.contains(secret),secret);
        assertTrue(persisted.contains("r1"));
    }
    @Test void invalidInputNeverCallsProvider() throws Exception {
        for(int size:List.of(0,21)) mvc.perform(post("/api/v1/expansions").header("Authorization","Bearer "+token()).contentType("application/json").content(mapper.writeValueAsString(request("r1",size))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(0,calls.get());
    }
    @Test void unknownTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/trial-session").header("Authorization","Bearer missing")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/expansions").contentType("application/json").content(mapper.writeValueAsString(request("r1",1))))
            .andExpect(status().isUnauthorized());
    }
    @Test void quotaAndRestartPreventDuplicateCharge() throws Exception {
        String token=token(); var original=request("r1",1);
        assertEquals(1,trials.expand(token,original).remaining());
        assertEquals(1,trials.expand(token,original).remaining());
        assertEquals(1,calls.get());
        assertEquals("REQUEST_ID_CONFLICT",assertThrows(ApiException.class,()->trials.expand(token,request("r1",2))).code);
        var restarted=new TrialService(mapper,gateway,directory.resolve("state.json").toString(),2);
        assertEquals(1,restarted.session(token).remaining());
        assertEquals("REQUEST_ALREADY_COMPLETED",assertThrows(ApiException.class,()->restarted.expand(token,original)).code);
        assertEquals(0,restarted.expand(token,request("r2",1)).remaining());
        assertEquals("QUOTA_EXHAUSTED",assertThrows(ApiException.class,()->restarted.expand(token,request("r3",1))).code);
        String rotated=restarted.create("test-client-123").token();
        assertEquals(0,restarted.session(rotated).remaining());
        assertEquals("UNAUTHORIZED",assertThrows(ApiException.class,()->restarted.session(token)).code);
    }
    @Test void concurrentRetryOnlyCallsAndChargesOnce() throws Exception {
        String token=token(); delay.set(100);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<ExpansionResponse>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(()->trials.expand(token,request("same",1))));
            for(var future:futures) assertEquals(1,future.get().remaining());
        }
        assertEquals(1,calls.get());
    }
    @Test void upstreamFailureDoesNotSpendQuotaAndCanRetrySameId() {
        String token=token(); status.set(500);
        assertEquals("LLM_UPSTREAM_ERROR",assertThrows(ApiException.class,()->trials.expand(token,request("r1",1))).code);
        assertEquals(2,trials.session(token).remaining()); status.set(200);
        assertEquals(1,trials.expand(token,request("r1",1)).remaining());
    }
    @Test void malformedOrTooManyOrDuplicateCandidatesDoNotSpendQuota() {
        String token=token();
        for(String invalid:List.of("not json","{\"candidates\":[],\"needsClarification\":false,\"clarification\":\"\"}",
            "{\"candidates\":[{\"text\":\"a\"},{\"text\":\"b\"},{\"text\":\"c\"},{\"text\":\"d\"}],\"needsClarification\":false,\"clarification\":\"\"}",
            "{\"candidates\":[{\"text\":\"同一句\"},{\"text\":\"同一句\"}],\"needsClarification\":false,\"clarification\":\"\"}")) {
            content.set(invalid);
            assertEquals("LLM_INVALID_RESPONSE",assertThrows(ApiException.class,()->trials.expand(token,request("r1",1))).code);
            assertEquals(2,trials.session(token).remaining());
        }
    }
    @Test void timeoutDoesNotSpendQuota() {
        String token=token(); delay.set(1400);
        assertEquals("LLM_TIMEOUT",assertThrows(ApiException.class,()->trials.expand(token,request("r1",1))).code);
        assertEquals(2,trials.session(token).remaining());
    }
    @Test void unconfiguredProviderReturnsExplicitUnavailable() {
        var absent=new ModelGateway(mapper,"","","",1);
        assertFalse(absent.configured());
        assertEquals("LLM_NOT_CONFIGURED",assertThrows(ApiException.class,()->absent.expand(request("r1",1))).code);
    }
    @Test void speechMissingConfigurationAndValidation() throws Exception {
        String token=token();
        mvc.perform(post("/api/v1/speech").header("Authorization","Bearer "+token).contentType("application/json")
            .content("{\"requestId\":\"s1\",\"text\":\"你好\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TTS_NOT_CONFIGURED"));
        mvc.perform(post("/api/v1/speech").header("Authorization","Bearer "+token).contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("requestId","s1","text","a".repeat(251)))))
            .andExpect(status().isBadRequest());
    }
    @Test void threeCandidatesAcceptedAndBlankRejected() {
        String token=token();
        content.set("{\"candidates\":[{\"text\":\"一\"},{\"text\":\"二\"},{\"text\":\"三\"}],\"needsClarification\":false,\"clarification\":\"\"}");
        assertEquals(3,trials.expand(token,request("three",1)).candidates().size());
        content.set("{\"candidates\":[{\"text\":\" \"}],\"needsClarification\":false,\"clarification\":\"\"}");
        assertEquals("LLM_INVALID_RESPONSE",assertThrows(ApiException.class,()->trials.expand(token,request("blank",1))).code);
    }
    @Test void emotionOnlyUsesSelectedCardsAndConflictsBecomeNeutral() throws Exception {
        String token=token();
        var context=new Context("children",null,List.of(),null,"happy");
        var missing=new ExpansionRequest("missing",1L,"room",List.of(new Card("apple","苹果",null,null)),context);
        assertEquals("INVALID_EMOTION_CONTEXT",assertThrows(ApiException.class,()->trials.expand(token,missing)).code);
        assertEquals(0,calls.get());
        var happy=new ExpansionRequest("happy",1L,"room",List.of(new Card("happy","开心",null,null)),context);
        trials.expand(token,happy);
        var userJson=mapper.readTree(mapper.readTree(sent.get()).path("messages").path(1).path("content").asText());
        assertEquals("happy",userJson.at("/context/emotion").asText());
        var conflict=new ExpansionRequest("conflict",1L,"room",List.of(new Card("happy","开心",null,null),new Card("angry","生气",null,null)),context);
        trials.expand(token,conflict);
        userJson=mapper.readTree(mapper.readTree(sent.get()).path("messages").path(1).path("content").asText());
        assertEquals("neutral",userJson.at("/context/emotion").asText());
        assertEquals(2,userJson.path("cards").size());
    }
    @Test void negatedEmotionNeverBecomesPositiveStyling() throws Exception {
        String token=token();
        var input=new ExpansionRequest("negated",1L,"room",List.of(new Card("not","不",null,null),new Card("happy","开心",null,null)),new Context("adult",null,List.of(),null,"happy"));
        trials.expand(token,input);
        var userJson=mapper.readTree(mapper.readTree(sent.get()).path("messages").path(1).path("content").asText());
        assertEquals("neutral",userJson.at("/context/emotion").asText());
        assertEquals("不",userJson.at("/cards/0/label").asText());
    }
    @Test void emotionOffKeepsExplicitCardMeaningAndCancelRequiresIdentity() throws Exception {
        String token=token();
        var input=new ExpansionRequest("off",1L,"room",List.of(new Card("angry","生气",null,null),new Card("no","不要",null,null)),new Context("adult",null,List.of(),null,null));
        trials.expand(token,input);
        var userJson=mapper.readTree(mapper.readTree(sent.get()).path("messages").path(1).path("content").asText());
        assertTrue(userJson.at("/context/emotion").isNull());
        assertEquals("生气",userJson.at("/cards/0/label").asText());
        assertEquals("不要",userJson.at("/cards/1/label").asText());
        mvc.perform(post("/api/v1/speech/play-1/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/speech/play-1/cancel").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(false));
    }
    @Test void sessionAndHealthContract() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.llmConfigured").value(true));
        mvc.perform(post("/api/v1/trial-session").contentType("application/json").content("{\"clientId\":\"test-client-123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities.analytics").value(false)).andExpect(jsonPath("$.remaining").value(2));
    }
}
