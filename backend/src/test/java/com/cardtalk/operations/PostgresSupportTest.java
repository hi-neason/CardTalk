package com.cardtalk.operations;

import com.cardtalk.api.*;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.cardtalk.operations.SupportContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PostgresSupportTest {
    String base,url,user,password,schema,token,family;
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    FamilyService families;FamilyService.Login owner,other,operator;
    TrialService trials;SupportService service;MockMvc mvc;LocalValidatorFactoryBean validator;
    @BeforeEach void setup()throws Exception {
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required");user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_support_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
        families=new FamilyService(new PostgresFamilyRepository(url,user,password),true,3600);owner=families.register("owner","test-password-123","o");other=families.register("other","test-password-123","m");operator=families.register("operator","test-password-123","p");family=families.createFamily(owner.account(),"支持测试家庭").id();
        trials=new TrialService(mapper,new ModelGateway(mapper,"","","",1),directory.resolve("unused.json").toString(),20,false,false,true,false,"postgres",url,user,password);token=trials.create("support-installation").token();
        service=store();validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();mvc=endpoint(service);
    }
    SupportService store(){return new SupportService(mapper,true,"postgres",url,user,password,operator.account().id(),5,90);}
    MockMvc endpoint(SupportService selected){return MockMvcBuilders.standaloneSetup(new SupportController(selected,trials,families)).setControllerAdvice(new Errors()).setValidator(validator).addFilters(new FamilyRequestFilter("http://localhost:5173")).build();}
    @AfterEach void cleanup()throws Exception {if(validator!=null)validator.close();if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    Actor actor(FamilyService.Login login){return new Actor(SupportService.hash("account:"+login.account().id()),login.account().id());}
    Create input(String nonce){return new Create(nonce,"support","需要帮助","自填联系信息",null);}
    String createTrial(String nonce)throws Exception {return mapper.readTree(mvc.perform(post("/api/v1/support/tickets").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(input(nonce)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();}
    @Test void anonymousRotationRestartAndOwnerIsolation()throws Exception {
        String id=createTrial("same");assertEquals(id,createTrial("same"));
        mvc.perform(post("/api/v1/support/tickets").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(new Create("same","support","不同内容","",null)))).andExpect(status().isConflict());
        String old=token;token=trials.create("support-installation").token();
        endpoint(store()).perform(get("/api/v1/support/tickets").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.tickets[0].id").value(id));
        mvc.perform(get("/api/v1/support/tickets").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
        String foreign=trials.create("other-installation").token();mvc.perform(get("/api/v1/support/tickets").header("Authorization","Bearer "+foreign)).andExpect(jsonPath("$.tickets.length()").value(0));
        mvc.perform(post("/api/v1/support/tickets/"+id+"/withdraw").header("Authorization","Bearer "+foreign)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/support/tickets/"+id+"/withdraw").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.status").value("withdrawn"));
        mvc.perform(post("/api/v1/support/tickets/"+id+"/withdraw").header("Authorization","Bearer "+token)).andExpect(status().isOk());
    }
    @Test void explicitScopeSeparatesLoggedInBrowserTrialAndAccountAndSupportsNativeBearer()throws Exception {
        String body=mapper.writeValueAsString(input("scope"));
        mvc.perform(post("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",owner.token())).header("Origin","http://localhost:5173").header("X-CardTalk-Support-Scope","trial").header("X-CardTalk-Trial-Token",token).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",owner.token())).header("X-CardTalk-Support-Scope","account")).andExpect(jsonPath("$.tickets.length()").value(0));
        mvc.perform(get("/api/v1/support/status").cookie(new Cookie("cardtalk_family",owner.token())).header("X-CardTalk-Support-Scope","trial").header("X-CardTalk-Trial-Token",token)).andExpect(jsonPath("$.actorScope").value("trial")).andExpect(jsonPath("$.canOperate").value(false));
        mvc.perform(post("/api/v1/support/tickets").header("X-CardTalk-Support-Scope","account").header("Authorization","Bearer "+owner.token()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/support/status").header("X-CardTalk-Support-Scope","account").header("Authorization","Bearer "+operator.token())).andExpect(jsonPath("$.actorScope").value("account")).andExpect(jsonPath("$.canOperate").value(true));
        mvc.perform(get("/api/v1/support/tickets").header("X-CardTalk-Support-Scope","account").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/support/status").header("X-CardTalk-Support-Scope","invalid")).andExpect(status().isBadRequest());
    }
    @Test void trustedFamilyScopeCookieOriginAndBounds()throws Exception {
        String body=mapper.writeValueAsString(new Create("family","privacy_deletion","请求删除本人资料，请联系确认范围","",family));
        mvc.perform(post("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",owner.token())).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",owner.token())).header("Origin","https://evil.invalid").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",other.token())).header("Origin","http://localhost:5173").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/support/tickets").header("Authorization","Bearer "+token).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",owner.token())).header("Origin","http://localhost:5173").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.familyId").value(family));
        for(var bad:List.of(new Create("oversize","support","x".repeat(4001),"",null),new Create("contact","support","帮助","x".repeat(201),null),new Create("bad-kind","delete-now","帮助","",null),new Create("blank","support","  ","",null))){mvc.perform(post("/api/v1/support/tickets").header("Authorization","Bearer "+token).contentType("application/json").content(mapper.writeValueAsString(bad))).andExpect(status().isBadRequest());}
        mvc.perform(post("/api/v1/support/tickets").header("Authorization","Bearer "+token).contentType("application/json").content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/api/v1/support/tickets").cookie(new Cookie("cardtalk_family",other.token()))).andExpect(jsonPath("$.tickets.length()").value(0));
    }
    @Test void operatorReplyAuditNonceAndDeletionEvidenceAreIndependentFromDeletion()throws Exception {
        var userActor=actor(owner);var ops=actor(operator);String id=service.create(userActor,new Create("privacy","privacy_deletion","请人工核实并删除指定数据","",family)).id();
        assertThrows(ApiException.class,()->service.list(userActor,true,0,20));assertThrows(ApiException.class,()->service.update(userActor,id,new Update("denied","resolved","回复",null)));
        assertThrows(ApiException.class,()->service.update(ops,id,new Update("no-evidence","resolved","完成",null)));
        mvc.perform(post("/api/v1/operations/tickets/"+id+"/updates").header("Authorization","Bearer "+operator.token()).contentType("application/json").content(mapper.writeValueAsString(new Update("too-long","in_progress","x".repeat(4001),null)))).andExpect(status().isBadRequest());
        var progress=new Update("progress","in_progress","正在确认身份和范围",null);
        mvc.perform(post("/api/v1/operations/tickets/"+id+"/updates").header("Authorization","Bearer "+operator.token()).contentType("application/json").content(mapper.writeValueAsString(progress))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("in_progress"));
assertEquals("in_progress",service.update(ops,id,progress).status());assertEquals("in_progress",store().update(ops,id,progress).status());
        assertThrows(ApiException.class,()->service.update(ops,id,new Update("progress","in_progress","不同回复",null)));
        assertThrows(ApiException.class,()->service.withdraw(userActor,id));
        assertEquals("resolved",service.update(ops,id,new Update("close","resolved","已核验处理记录","人工记录：本测试未执行删除，仅验证证据字段")).status());
        assertEquals(3,service.audits(ops,id).size());assertEquals(List.of("submitted","in_progress","resolved"),service.audits(ops,id).stream().map(Audit::action).toList());
        assertEquals(1,families.families(owner.account()).size()); // Ticket handling never deletes family data.
        assertThrows(ApiException.class,()->service.update(ops,id,new Update("again","rejected","后续改动",null)));
        mvc.perform(get("/api/v1/operations/tickets").cookie(new Cookie("cardtalk_family",owner.token()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operations/tickets").cookie(new Cookie("cardtalk_family",operator.token()))).andExpect(status().isOk()).andExpect(jsonPath("$.tickets[0].id").value(id));
        mvc.perform(get("/api/v1/operations/tickets/"+id+"/audit").cookie(new Cookie("cardtalk_family",operator.token()))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(post("/api/v1/operations/tickets/"+id+"/updates").cookie(new Cookie("cardtalk_family",operator.token())).contentType("application/json").content(mapper.writeValueAsString(progress))).andExpect(status().isForbidden());
    }
    @Test void multiInstanceDailyLimitAndPagination()throws Exception {
        var identity=actor(owner);var otherStore=store();
        try(var pool=Executors.newFixedThreadPool(10)){
            var gate=new CountDownLatch(1);List<Future<Ticket>> futures=new ArrayList<>();for(int i=0;i<12;i++){int n=i;futures.add(pool.submit(()->{gate.await();try{return(n%2==0?service:otherStore).create(identity,input("parallel"+n));}catch(ApiException ex){assertEquals("今日提交次数已达上限，请稍后再试",ex.getMessage());return null;}}));}gate.countDown();int created=0;for(var result:futures)if(result.get(10,TimeUnit.SECONDS)!=null)created++;assertEquals(5,created);
        }
        var first=service.list(identity,false,0,2);assertTrue(first.hasMore());var second=service.list(identity,false,Long.parseLong(first.nextCursor()),2);var third=service.list(identity,false,Long.parseLong(second.nextCursor()),2);assertFalse(third.hasMore());Set<String> ids=new HashSet<>();for(var page:List.of(first,second,third))for(var ticket:page.tickets())assertTrue(ids.add(ticket.id()));assertEquals(5,ids.size());
        assertThrows(ApiException.class,()->service.list(identity,false,0,51));
    }
    @Test void retentionOnlyPurgesExpiredClosedTicketsAndRecordsMaintenanceAudit()throws Exception {
        var userActor=actor(owner);var ops=actor(operator);String closed=service.create(userActor,input("closed")).id(),open=service.create(userActor,input("open")).id();
        var withdrawn=service.withdraw(userActor,closed);assertNotNull(withdrawn.retainUntil());assertTrue(Instant.parse(withdrawn.retainUntil()).isAfter(Instant.now().plus(Duration.ofDays(89))));assertEquals(0,service.purge(ops));
        try(var c=DriverManager.getConnection(url,user,password);var s=c.prepareStatement("UPDATE ct_support_tickets SET retain_until=clock_timestamp()-interval '1 day' WHERE id=?")){s.setString(1,closed);s.executeUpdate();}
        assertThrows(ApiException.class,()->service.purge(userActor));assertEquals(1,store().purge(ops));assertEquals(open,service.list(userActor,false,0,20).tickets().getFirst().id());
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT sum(removed_count) FROM ct_support_maintenance_audit")){r.next();assertEquals(1,r.getInt(1));}
    }
    @Test void disabledServiceReturnsExplicit503AndCannotFallback()throws Exception {
        var disabled=new SupportService(mapper,false,"json","","","","",5,90);
        endpoint(disabled).perform(get("/api/v1/support/status")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SUPPORT_DISABLED"));
        endpoint(disabled).perform(post("/api/v1/support/tickets").contentType("application/json").content(mapper.writeValueAsString(input("disabled")))).andExpect(status().isServiceUnavailable());
        assertThrows(IllegalArgumentException.class,()->new SupportService(mapper,true,"json","","","","",5,90));
    }

    @Test void supportMetricsAndBillingOperatorsHaveIndependentPermissions()throws Exception {
        var repository=new PostgresFamilyRepository(url,user,password);
        var billing=new com.cardtalk.billing.BillingService(repository,families,new com.cardtalk.billing.ProductsConfig(mapper,""),new com.cardtalk.billing.NoPaymentProvider(),mapper,"postgres",url,user,password,owner.account().id(),"");
        var metrics=new com.cardtalk.operationsmetrics.MetricsController(new com.cardtalk.operationsmetrics.MetricsRegistry(true),families,other.account().id());
        var readonly=new org.springframework.mock.web.MockHttpServletRequest();readonly.addHeader("Authorization","Bearer "+other.token());
        assertEquals(200,metrics.get(readonly).getStatusCode().value());
        assertTrue(service.canOperate(actor(operator)));assertFalse(service.canOperate(actor(other)));assertFalse(service.canOperate(actor(owner)));
        assertEquals("人工测试权益仅可由已配置的操作员授予",assertThrows(ApiException.class,()->billing.grantManual(operator.account(),family,com.cardtalk.billing.ProductsConfig.Feature.profiles,Instant.now(),Instant.now().plusSeconds(3600))).getMessage());
        assertEquals("人工测试权益仅可由已配置的操作员授予",assertThrows(ApiException.class,()->billing.grantManual(other.account(),family,com.cardtalk.billing.ProductsConfig.Feature.profiles,Instant.now(),Instant.now().plusSeconds(3600))).getMessage());
        billing.grantManual(owner.account(),family,com.cardtalk.billing.ProductsConfig.Feature.profiles,Instant.now(),Instant.now().plusSeconds(3600));
        assertEquals("没有运营处理权限",assertThrows(ApiException.class,()->service.list(actor(owner),true,0,50)).getMessage());
        readonly.removeHeader("Authorization");readonly.addHeader("Authorization","Bearer "+operator.token());assertEquals("没有运行指标查看权限",assertThrows(ApiException.class,()->metrics.get(readonly)).getMessage());
    }
}
