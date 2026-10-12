package com.cardtalk.reports;

import com.cardtalk.api.ApiException;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresReportQuotaTest {
    String base,url,user,password,schema;
    AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-12T00:00:00Z"));
    Clock clock=new Clock() {
        public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
        public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}
        public Instant instant(){return now.get();}
    };
    String identity=ReportQuota.hash("private-token"),nonce=ReportQuota.hash("request"),facts=ReportQuota.hash("counts");
    @BeforeEach void setup()throws Exception {
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required");
        user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_report_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
    }
    ReportQuota store(){return new ReportQuota("postgres",url,user,password,5,clock);}
    @AfterEach void cleanup()throws Exception {if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    @Test void restartPreservesSuccessfulChargesAndDailyNonceBinding()throws Exception {
        var q=store();var first=q.reserve(identity,nonce,facts);assertEquals(4,q.complete(first));assertEquals(4,q.complete(first));
        q=store();assertTrue(q.reserve(identity,nonce,facts).completed());
        var reloaded=q;assertThrows(ApiException.class,()->reloaded.reserve(identity,nonce,ReportQuota.hash("different")));
        for(int i=0;i<4;i++)assertEquals(3-i,q.complete(q.reserve(identity,ReportQuota.hash("n"+i),facts)));
        assertThrows(ApiException.class,()->store().reserve(identity,ReportQuota.hash("over"),facts));
        assertEquals(4,q.complete(q.reserve(ReportQuota.hash("other"),nonce,facts)));
        now.set(Instant.parse("2026-10-12T16:00:00Z"));assertFalse(store().reserve(identity,nonce,facts).completed());
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var rows=s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='ct_report_quota'")) {
            Set<String> columns=new HashSet<>();while(rows.next())columns.add(rows.getString(1));assertEquals(Set.of("identity_hash","quota_day","nonce_hash","facts_hash","state","attempt","expires_at"),columns);
        }
    }
    @Test void parallelInstancesReserveOnlyFiveAndDuplicateDoesNotStartAgain()throws Exception {
        var a=store();var b=store();List<ReportQuota.Reservation> reservations=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(12)) {
            var start=new CountDownLatch(1);List<Future<ReportQuota.Reservation>> futures=new ArrayList<>();
            for(int i=0;i<20;i++){int n=i;futures.add(pool.submit(()->{start.await();try{return(n%2==0?a:b).reserve(identity,ReportQuota.hash("parallel"+n),facts);}catch(ApiException ex){assertEquals("今日 AI 统计说明体验次数已用完",ex.getMessage());return null;}}));}
            start.countDown();for(var future:futures){var reservation=future.get(10,TimeUnit.SECONDS);if(reservation!=null)reservations.add(reservation);}
        }
        assertEquals(5,reservations.size());var first=reservations.getFirst();assertThrows(ApiException.class,()->b.reserve(identity,first.nonce(),facts));
        for(var reservation:reservations)a.complete(reservation);
        assertTrue(b.reserve(identity,first.nonce(),facts).completed());
    }
    @Test void failureReleasesSlotButCannotChangeFactsOrReleaseAnotherAttempt() {
        var a=store();var first=a.reserve(identity,nonce,facts);a.release(first);
        assertThrows(ApiException.class,()->store().reserve(identity,nonce,ReportQuota.hash("changed")));
        var retry=store().reserve(identity,nonce,facts);a.release(first);assertEquals(4,a.complete(retry));a.release(retry);assertEquals(4,a.complete(retry));
    }
    @Test void crashedReservationExpiresAndLateCompletionCannotChargeReplacement() {
        var q=store();var crashed=q.reserve(identity,nonce,facts);
        now.set(now.get().plusSeconds(301));var retry=store().reserve(identity,nonce,facts);
        assertNotEquals(crashed.attempt(),retry.attempt());assertThrows(ApiException.class,()->q.complete(crashed));q.release(crashed);assertEquals(4,q.complete(retry));
    }
    @Test void familyEndpointUsesEntitlementAndPersistentActorQuotaWithoutTrialFlags()throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var repository=new com.cardtalk.family.PostgresFamilyRepository(url,user,password);
        var families=new com.cardtalk.family.FamilyService(repository,true,3600);
        var owner=families.register("owner","test-password-123","owner");
        var outsider=families.register("outside","test-password-123","outside");
        var helper=families.register("helper","test-password-123","helper");
        String family=families.createFamily(owner.account(),"测试家庭").id();
        families.accept(helper.account(),families.invite(owner.account(),family,"helper",List.of("p1")).code());
        var billing=new com.cardtalk.billing.BillingService(repository,families,new com.cardtalk.billing.ProductsConfig(mapper,""),new com.cardtalk.billing.NoPaymentProvider(),mapper,"postgres",url,user,password,owner.account().id(),"");
        var gateway=org.mockito.Mockito.mock(ReportGateway.class);
        org.mockito.Mockito.when(gateway.generate(ReportTest.facts())).thenReturn("AI 统计说明：4 条已确认表达。");
        var service=new ReportService(null,gateway,mapper,store());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new FamilyReportController(families,billing,service,true))
            .setControllerAdvice(new com.cardtalk.api.Errors()).addFilters(new com.cardtalk.family.FamilyRequestFilter("http://localhost:5173")).build();
        String path="/api/v1/families/"+family+"/reports/narrative",body=mapper.writeValueAsString(new ReportContracts.Request("family-report",ReportTest.facts()));
        var post=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(body);
        mvc.perform(post).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(post.header("Authorization","Bearer "+owner.token())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        billing.grantManual(owner.account(),family,com.cardtalk.billing.ProductsConfig.Feature.analytics,Instant.now().minusSeconds(10),Instant.now().plusSeconds(3600));
        for(String denied:List.of(outsider.token(),helper.token())) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+denied).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(new jakarta.servlet.http.Cookie("cardtalk_family",owner.token())).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).cookie(new jakarta.servlet.http.Cookie("cardtalk_family",owner.token())).header("Origin","http://localhost:5173").contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.remaining").value(4));
        mvc.perform(post).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var restarted=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new FamilyReportController(families,billing,new ReportService(null,gateway,mapper,store()),true)).setControllerAdvice(new com.cardtalk.api.Errors()).build();
        restarted.perform(post).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("REPORT_ALREADY_COMPLETED"));
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(1)).generate(ReportTest.facts());
        try(var c=DriverManager.getConnection(url,user,password);var statement=c.createStatement()){statement.executeUpdate("UPDATE ct_billing_grants SET revoked_at=clock_timestamp()");}
        mvc.perform(post).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void providerFailureReleasesPersistentReservationForSameRequestRetry() {
        var gateway=org.mockito.Mockito.mock(ReportGateway.class);
        org.mockito.Mockito.when(gateway.generate(ReportTest.facts())).thenThrow(new ApiException(502,"LLM_UPSTREAM_ERROR","upstream unavailable")).thenReturn("AI 统计说明：4 条已确认表达。");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var request=new ReportContracts.Request("retry-provider",ReportTest.facts());
        var first=new ReportService(null,gateway,mapper,store());
        assertThrows(ApiException.class,()->first.generateIdentity(identity,request));
        var restarted=new ReportService(null,gateway,mapper,store());
        assertEquals(4,restarted.generateIdentity(identity,request).remaining());
        assertEquals(4,restarted.generateIdentity(identity,request).remaining());
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(2)).generate(ReportTest.facts());
    }

}
