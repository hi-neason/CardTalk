package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.cardtalk.billing.PaymentProvider.*;
import static com.cardtalk.billing.ProductsConfig.*;

/** All provider events and prices here are isolated test fixtures, never a public fake-payment mode. */
class PostgresBillingTest {
    @TempDir Path dir;
    String base,url,user,password,schema,family;
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    PostgresFamilyRepository repository;FamilyService families;ProductsConfig catalog;BillingService billing;
    FamilyService.Login owner,admin,member,helper,operator;FixtureProvider provider;
    class FixtureProvider implements PaymentProvider {
        boolean uncertain;int checkouts;
        public String name(){return "fixture";}public boolean configured(){return true;}public String merchantId(){return "merchant-fixture";}public String appId(){return "app-fixture";}
        public Checkout createCheckout(CheckoutRequest request){checkouts++;if(uncertain)throw new ApiException(503,"UNCERTAIN","fixture lost response");return new Checkout("https://fixture.invalid/checkout/"+request.outTradeNo(),"fixture-checkout");}
        public ProviderEvent queryPayment(String id){return null;}public void close(String id){}
        public RefundResult refund(RefundRequest r){return new RefundResult(r.outRefundNo(),"fixture-refund","PROCESSING");}
        public ProviderEvent queryRefund(String id){return null;}
        public ProviderEvent verifyNotification(Map<String,String> headers,byte[] raw){if(!"test-only".equals(headers.get("Fixture-Signature")))throw new ApiException(401,"BAD_SIGNATURE","fixture invalid signature");try{return mapper.readValue(raw,ProviderEvent.class);}catch(Exception e){throw new ApiException(400,"BAD_FIXTURE","fixture invalid event");}}
    }
    @BeforeEach void setup()throws Exception{
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required via CARDTALK_TEST_PG_URL/USER/PASSWORD");user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_billing_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
        repository=new PostgresFamilyRepository(url,user,password);families=new FamilyService(repository,true,3600);owner=register("owner");admin=register("admin");member=register("member");helper=register("helper");operator=register("operator");family=families.createFamily(owner.account(),"测试家庭").id();
        for(var entry:Map.of(admin,"admin",member,"member",helper,"helper").entrySet())families.accept(entry.getKey().account(),families.invite(owner.account(),family,entry.getValue(),List.of()).code());
        Path file=dir.resolve("fixture-products.json");Files.writeString(file,"{\"products\":[{\"id\":\"profiles-test\",\"feature\":\"profiles\",\"title\":\"测试档案\",\"amountMinor\":100,\"currency\":\"CNY\",\"durationDays\":30},{\"id\":\"analytics-test\",\"feature\":\"analytics\",\"title\":\"测试分析\",\"amountMinor\":200,\"currency\":\"CNY\",\"durationDays\":10}]}");catalog=new ProductsConfig(mapper,file.toString());provider=new FixtureProvider();billing=service(provider);
    }
    FamilyService.Login register(String name){return families.register(name,"test-password-123",name);}
    BillingService service(PaymentProvider p){return new BillingService(repository,families,catalog,p,mapper,"postgres",url,user,password,operator.account().id(),"https://fixture.invalid/notify");}
    BillingService.Order order(String request,String product){return billing.createOrder(owner.account(),family,product,request,"127.0.0.1").order();}
    ProviderEvent event(BillingService.Order o,String id,EventType type){return new ProviderEvent(id,type,o.outTradeNo(),o.outRefundNo(),"transaction-"+o.id(),o.amountMinor(),type==EventType.REFUNDED||type==EventType.REFUND_FAILED?o.amountMinor():0,"CNY",provider.merchantId(),provider.appId(),Instant.now());}
    void notify(ProviderEvent e)throws Exception{billing.notification("fixture",Map.of("Fixture-Signature","test-only"),mapper.writeValueAsBytes(e));}
    BillingService.Entitlement entitlement(Feature feature){return billing.entitlements(owner.account(),family).features().stream().filter(e->e.feature()==feature).findFirst().orElseThrow();}
    long count(String table)throws Exception{try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}}
    @AfterEach void cleanup()throws Exception{if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    com.cardtalk.cloud.CloudStore cloud(boolean enforced){return new com.cardtalk.cloud.CloudStore(repository,mapper,null,billing,enforced,true,"postgres",url,user,password);}
    com.fasterxml.jackson.databind.JsonNode profile(String id){var n=mapper.createObjectNode();n.put("id",id);n.put("name","测试使用人");n.put("preset","children");n.putNull("age");n.put("language","普通话");for(String field:List.of("interests","personality","communicationStyle","observations","observer"))n.put(field,"");n.putNull("observedAt");n.put("archived",false);n.put("updatedAt","2026-10-12T00:00:00Z");return n;}
    com.cardtalk.cloud.CloudDtos.Operation operation(String nonce,String type,String id,long revision,com.fasterxml.jackson.databind.JsonNode payload){return new com.cardtalk.cloud.CloudDtos.Operation(nonce,type,id,revision,false,payload);}
    com.cardtalk.cloud.CloudDtos.Push sync(com.cardtalk.cloud.CloudDtos.Operation... operations){return new com.cardtalk.cloud.CloudDtos.Push(new com.cardtalk.cloud.CloudDtos.Consent(false,false),List.of(operations));}
    com.fasterxml.jackson.databind.JsonNode emptyReport(){
        var report=mapper.createObjectNode();report.put("id","report1");report.put("createdAt","2026-10-12T00:00:00Z");report.put("metricVersion",1);report.putObject("filters");report.putArray("evidenceIds");report.put("sourceSignature","[]");var metrics=report.putObject("metrics");for(String field:List.of("activeDays","confirmedCount","roomCount"))metrics.put(field,0);metrics.put("sampleWarning",true);metrics.putNull("feedbackCoverage");metrics.putNull("understoodRate");
        for(var entry:Map.of("inputCards","total,average,max","playbackCounts","completed,failed,interrupted,unavailable,startedOnly,neverPlayed","feedback","understood,clarify,notUnderstood,unrecorded","kind","raw,ai,edited,saved","source","independent,assisted,unknown").entrySet()){var group=metrics.putObject(entry.getKey());for(String field:entry.getValue().split(","))group.put(field,0);}var process=metrics.putObject("process");for(String field:"collectedCount,missingCount,usableTimingCount,generationStarted,generationSucceeded,generationFailed,generationCancelled,candidatesRejected,candidateEditStarted".split(","))process.put(field,0);process.putNull("averageSeconds");process.putNull("rejectionRate");for(String field:List.of("categoryCounts","topCards","sceneCounts","dailyCounts"))metrics.putArray(field);return report;
    }
    @Test void enforcedCloudWritesCheckIndependentFeaturesAndMembershipInsideTransaction()throws Exception{
        var cloud=cloud(true);var profile=operation("profile","profile","p1",0,profile("p1"));
        var scene=mapper.readTree("{\"id\":\"scene1\",\"name\":\"场景\",\"description\":\"测试场景\",\"activities\":\"\",\"objects\":\"\",\"recommendedCardIds\":[],\"archived\":false,\"updatedAt\":\"2026-10-12T00:00:00Z\"}");
        var scenario=operation("scenario","scene","scene1",0,scene);var report=operation("report","report","report1",0,emptyReport());
        assertThrows(ApiException.class,()->cloud.push(owner.account(),family,sync(profile)));
        billing.grantManual(operator.account(),family,Feature.profiles,Instant.now().minusSeconds(1),Instant.now().plusSeconds(3600));cloud.push(member.account(),family,sync(profile));
        assertThrows(ApiException.class,()->cloud.push(owner.account(),family,sync(scenario)));assertThrows(ApiException.class,()->cloud.push(owner.account(),family,sync(report)));
        billing.grantManual(operator.account(),family,Feature.scenarios,Instant.now().minusSeconds(1),Instant.now().plusSeconds(3600));cloud.push(owner.account(),family,sync(scenario));assertThrows(ApiException.class,()->cloud.push(owner.account(),family,sync(report)));
        billing.grantManual(operator.account(),family,Feature.analytics,Instant.now().minusSeconds(1),Instant.now().plusSeconds(3600));cloud.push(owner.account(),family,sync(report));
        assertThrows(ApiException.class,()->cloud.push(helper.account(),family,sync(operation("helper","profile","p2",0,profile("p2")))));
        String other=families.createFamily(owner.account(),"另一家庭").id();assertThrows(ApiException.class,()->cloud.push(owner.account(),other,sync(operation("cross-family","profile","p2",0,profile("p2")))));
        assertEquals(3,cloud.pull(owner.account(),family,"0",100).entities().size());
    }
    @Test void cloudExpiryRefundAndReplayPreserveReadWhileNewWritesFail()throws Exception{
        var initial=sync(operation("legacy","profile","p1",0,profile("p1")));long revision=cloud(false).push(owner.account(),family,initial).results().get(0).revision();var enforced=cloud(true);
        billing.grantManual(operator.account(),family,Feature.profiles,Instant.now().minusSeconds(7200),Instant.now().minusSeconds(3600));assertThrows(ApiException.class,()->enforced.push(owner.account(),family,sync(operation("expired","profile","p1",revision,profile("p1")))));
        assertEquals(1,enforced.pull(owner.account(),family,"0",100).entities().size());assertEquals(revision,enforced.push(owner.account(),family,initial).results().get(0).revision());
        var order=order("cloud-pay","profiles-test");notify(event(order,"cloud-paid",EventType.PAID));long next=enforced.push(owner.account(),family,sync(operation("paid-write","profile","p1",revision,profile("p1")))).results().get(0).revision();
        var refund=billing.refund(owner.account(),family,order.id(),"测试退款");notify(event(refund,"cloud-refunded",EventType.REFUNDED));assertThrows(ApiException.class,()->enforced.push(owner.account(),family,sync(operation("refund-write","profile","p1",next,profile("p1")))));assertEquals(next,enforced.pull(owner.account(),family,"0",100).entities().get(0).revision());
    }
    @Test void unentitledAdvancedOperationRollsBackBasicWritesAndFormalModeRequiresPostgres()throws Exception{
        var card=mapper.readTree("{\"id\":\"card1\",\"label\":\"水\",\"category\":\"daily\",\"icon\":\"water\",\"kind\":\"word\",\"sceneIds\":[],\"archived\":false,\"updatedAt\":\"2026-10-12T00:00:00Z\"}");var cloud=cloud(true);var basic=operation("card","card","card1",0,card);
        assertThrows(ApiException.class,()->cloud.push(owner.account(),family,sync(basic,operation("blocked-profile","profile","p1",0,profile("p1")))));assertTrue(cloud.pull(owner.account(),family,"0",10).entities().isEmpty());cloud.push(owner.account(),family,sync(basic));assertEquals(1,cloud.pull(owner.account(),family,"0",10).entities().size());
        assertThrows(IllegalStateException.class,()->new com.cardtalk.cloud.CloudStore(repository,mapper,null,billing,true,true,"json","","",""));
    }
    @Test void providerNoneEmptyCatalogAndOwnerCannotSelfGrant()throws Exception{
        assertTrue(new ProductsConfig(mapper,"").products().isEmpty());var disabled=service(new NoPaymentProvider());assertThrows(ApiException.class,()->disabled.createOrder(owner.account(),family,"profiles-test","none","127.0.0.1"));assertEquals(0,count("ct_billing_orders"));
        assertThrows(ApiException.class,()->billing.grantManual(owner.account(),family,Feature.profiles,Instant.now(),Instant.now().plusSeconds(3600)));
        billing.grantManual(operator.account(),family,Feature.profiles,Instant.now().minusSeconds(10),Instant.now().plusSeconds(3600));assertTrue(entitlement(Feature.profiles).active());assertFalse(entitlement(Feature.analytics).active());assertFalse(entitlement(Feature.scenarios).active());
        assertFalse(billing.entitlements(helper.account(),family).features().get(0).actions().writeAdvanced());assertFalse(billing.entitlements(helper.account(),family).features().get(0).actions().purchase());
    }
    @Test void expiryPreservesReadActionsAndDoesNotDeleteKnownData()throws Exception{
        billing.grantManual(operator.account(),family,Feature.analytics,Instant.now().minusSeconds(7200),Instant.now().minusSeconds(3600));assertFalse(entitlement(Feature.analytics).active());assertTrue(entitlement(Feature.analytics).actions().readExisting());assertThrows(ApiException.class,()->billing.requireAdvancedWrite(owner.account(),family,Feature.analytics));assertEquals(1,count("ct_billing_grants"));assertEquals(1,families.families(owner.account()).size());
    }
    @Test void orderNonceAmountSnapshotRolesAndUnknownProviderResponseRetry()throws Exception{
        var first=order("nonce","profiles-test");assertEquals("PENDING",first.status());assertEquals(100,first.amountMinor());assertEquals(first.id(),order("nonce","profiles-test").id());assertEquals(1,provider.checkouts);
        assertThrows(ApiException.class,()->order("nonce","analytics-test"));assertThrows(ApiException.class,()->billing.createOrder(admin.account(),family,"profiles-test","nonce","127.0.0.1"));
        for(var actor:List.of(member,helper,operator))assertThrows(ApiException.class,()->billing.createOrder(actor.account(),family,"profiles-test","new","127.0.0.1"));
        String other=families.createFamily(operator.account(),"另一个家庭").id();assertThrows(ApiException.class,()->billing.order(owner.account(),other,first.id()));
        provider.uncertain=true;assertThrows(ApiException.class,()->order("lost","profiles-test"));String trade=billing.orders(owner.account(),family).stream().filter(o->o.status().equals("CREATED")).findFirst().orElseThrow().outTradeNo();provider.uncertain=false;assertEquals(trade,order("lost","profiles-test").outTradeNo());assertEquals(2,count("ct_billing_orders"));
    }
    @Test void signedPaymentInboxIsDurableIdempotentAndRejectsAmountMerchantCurrencyAndForgery()throws Exception{
        var o=order("paid","profiles-test");var paid=event(o,"event-paid",EventType.PAID);byte[] body=mapper.writeValueAsBytes(paid);
        assertThrows(ApiException.class,()->billing.notification("fixture",Map.of(),body));assertEquals(0,count("ct_billing_event_inbox"));
        for(var bad:List.of(new ProviderEvent("amount",EventType.PAID,o.outTradeNo(),null,"tx",999,0,"CNY",provider.merchantId(),provider.appId(),Instant.now()),new ProviderEvent("merchant",EventType.PAID,o.outTradeNo(),null,"tx",100,0,"CNY","other",provider.appId(),Instant.now()),new ProviderEvent("currency",EventType.PAID,o.outTradeNo(),null,"tx",100,0,"USD",provider.merchantId(),provider.appId(),Instant.now())))assertThrows(ApiException.class,()->notify(bad));
        notify(paid);billing=service(provider);notify(paid);assertEquals(1,count("ct_billing_event_inbox"));assertEquals(1,count("ct_billing_grants"));assertTrue(entitlement(Feature.profiles).active());
        notify(event(o,"late-close",EventType.CLOSED));assertEquals("PAID",billing.order(owner.account(),family,o.id()).status());
        assertThrows(ApiException.class,()->notify(new ProviderEvent(paid.eventId(),paid.type(),paid.outTradeNo(),null,paid.transactionId(),paid.amountMinor(),0,paid.currency(),paid.merchantId(),paid.appId(),paid.occurredAt().plusSeconds(1))));
    }
    @Test void databaseFailureKeepsCauseAndRollsBackPaymentGrantBeforeRetry()throws Exception {
        var order=order("rollback-inbox","profiles-test");var paid=event(order,"rollback-event",EventType.PAID);
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("ALTER TABLE ct_billing_event_inbox ADD CONSTRAINT reject_fixture CHECK(event_type='fixture-never')");}
        var failure=assertThrows(ApiException.class,()->notify(paid));assertInstanceOf(SQLException.class,failure.getCause());assertEquals(0,count("ct_billing_grants"));assertEquals(0,count("ct_billing_event_inbox"));assertEquals("PENDING",billing.order(owner.account(),family,order.id()).status());
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("ALTER TABLE ct_billing_event_inbox DROP CONSTRAINT reject_fixture");}
        notify(paid);assertEquals(1,count("ct_billing_grants"));assertEquals("PAID",billing.order(owner.account(),family,order.id()).status());
    }
    @Test void cookieHttpContractsRequireIdentityOriginAndProviderProof()throws Exception {
        var validator=new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();validator.afterPropertiesSet();
        try {
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BillingController(families,service(new NoPaymentProvider()))).setControllerAdvice(new com.cardtalk.api.Errors()).setValidator(validator).addFilters(new FamilyRequestFilter("http://127.0.0.1:5173")).build();String path="/api/v1/families/"+family;
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/entitlements")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/entitlements").header("Authorization","Bearer trial-fixture-token")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/entitlements").cookie(new jakarta.servlet.http.Cookie("cardtalk_family",helper.token()))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.features[0].actions.readExisting").value(true));
            String body="{\"productId\":\"profiles-test\",\"requestId\":\"http-order\"}";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/billing/orders").cookie(new jakarta.servlet.http.Cookie("cardtalk_family",owner.token())).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/billing/orders").cookie(new jakarta.servlet.http.Cookie("cardtalk_family",owner.token())).header("Origin","http://127.0.0.1:5173").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("PAYMENT_NOT_CONFIGURED"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/billing/notifications/none").contentType("application/json").content("{\"status\":\"PAID\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());assertEquals(0,count("ct_billing_orders"));
        }finally{validator.close();}
    }
    @Test void refundOnlyRevokesItsPurchaseAndLateEventsCannotRegrant()throws Exception{
        billing.grantManual(operator.account(),family,Feature.profiles,Instant.now().minusSeconds(1),Instant.now().plusSeconds(3600));var p=order("profiles","profiles-test");var a=order("analytics","analytics-test");notify(event(p,"paid-p",EventType.PAID));notify(event(a,"paid-a",EventType.PAID));
        var refund=billing.refund(owner.account(),family,p.id(),"测试退款");assertEquals("REFUND_PENDING",refund.status());assertEquals(refund.outRefundNo(),billing.refund(owner.account(),family,p.id(),"测试退款").outRefundNo());
        notify(event(refund,"refunded",EventType.REFUNDED));notify(event(p,"late-paid",EventType.PAID));notify(event(refund,"late-refund-failed",EventType.REFUND_FAILED));assertEquals("REFUNDED",billing.order(owner.account(),family,p.id()).status());assertTrue(entitlement(Feature.profiles).active());assertTrue(entitlement(Feature.analytics).active());
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT source,order_id,revoked_at FROM ct_billing_grants")){while(r.next()){if(p.id().equals(r.getString(2)))assertNotNull(r.getTimestamp(3));else assertNull(r.getTimestamp(3));}}
    }
}
