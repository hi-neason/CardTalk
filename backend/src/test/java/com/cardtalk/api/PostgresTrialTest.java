package com.cardtalk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static com.cardtalk.api.Contracts.*;

class PostgresTrialTest {
    String base,url,user,password,schema;
    @TempDir Path directory;
    AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-12T15:59:00Z"));
    Clock clock=new Clock(){public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}};
    String token=TrialRepository.hash("private-token"),nonce=TrialRepository.hash("request"),digest=TrialRepository.hash("private expression");
    @BeforeEach void setup()throws Exception{
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required");user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_trial_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
    }
    @AfterEach void cleanup()throws Exception{if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    TrialRepository store(){return new TrialRepository(url,user,password,2,clock);}
    ExpansionRequest input(String id){return new ExpansionRequest(id,1L,"private-room",List.of(new Card("apple","苹果",null,null)),new Context("children",null,List.of(),null,null));}
    TrialService service(ModelGateway gateway)throws Exception{return new TrialService(new ObjectMapper(),gateway,directory.resolve("unused.json").toString(),2,false,false,true,false,"postgres",url,user,password,clock);}
    @Test void rotationRestartAndSuccessNonceRemainBoundAcrossMidnight(){
        var repo=store();assertEquals(2,repo.create("client-id",token).remaining());
        var first=repo.reserve(token,nonce,digest);assertEquals(1,repo.complete(first));assertEquals(1,repo.complete(first));
        var restarted=store();String rotated=TrialRepository.hash("rotated");assertEquals(1,restarted.create("client-id",rotated).remaining());
        assertThrows(ApiException.class,()->repo.session(token));assertTrue(restarted.reserve(rotated,nonce,digest).completed());
        assertThrows(ApiException.class,()->restarted.reserve(rotated,nonce,TrialRepository.hash("other")));
        now.set(Instant.parse("2026-10-12T16:00:01Z"));assertEquals(2,restarted.session(rotated).remaining());assertTrue(restarted.reserve(rotated,nonce,digest).completed());
        assertEquals(1,restarted.complete(restarted.reserve(rotated,TrialRepository.hash("new-day"),digest)));
    }
    @Test void twentyConcurrentRequestsAcrossInstancesReserveOnlyDailyLimit()throws Exception {
        var a=store();var b=store();a.create("client-id",token);List<TrialRepository.Reservation> reservations=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(10)){
            var start=new CountDownLatch(1);List<Future<TrialRepository.Reservation>> futures=new ArrayList<>();
            for(int i=0;i<20;i++){int n=i;futures.add(pool.submit(()->{start.await();try{return(n%2==0?a:b).reserve(token,TrialRepository.hash("parallel-"+n),digest);}catch(ApiException ex){assertEquals("QUOTA_EXHAUSTED",ex.code);return null;}}));}
            start.countDown();for(var future:futures){var reservation=future.get(10,TimeUnit.SECONDS);if(reservation!=null)reservations.add(reservation);}
        }
        assertEquals(2,reservations.size());assertEquals(0,a.session(token).remaining());
        assertEquals("REQUEST_IN_PROGRESS",assertThrows(ApiException.class,()->b.reserve(token,reservations.getFirst().nonce(),digest)).code);
        for(var reservation:reservations)a.complete(reservation);assertEquals(0,b.session(token).remaining());
    }
    @Test void failureAndLeaseRecoveryCannotBeOverwrittenByOldAttempt(){
        var repo=store();repo.create("client-id",token);var first=repo.reserve(token,nonce,digest);repo.release(first);assertEquals(2,repo.session(token).remaining());
        assertEquals("REQUEST_ID_CONFLICT",assertThrows(ApiException.class,()->repo.reserve(token,nonce,TrialRepository.hash("changed"))).code);
        var second=store().reserve(token,nonce,digest);repo.release(first);assertEquals(1,repo.session(token).remaining());
        now.set(now.get().plusSeconds(301));var third=store().reserve(token,nonce,digest);assertNotEquals(second.attempt(),third.attempt());repo.release(second);assertThrows(ApiException.class,()->repo.complete(second));assertEquals(1,repo.complete(third));repo.release(third);assertEquals(1,repo.session(token).remaining());
    }
    @Test void inFlightMidnightCompletionChargesReservedDayAndKeepsNewDayCounter(){
        var repo=store();repo.create("client-id",token);var yesterday=repo.reserve(token,nonce,digest);
        now.set(Instant.parse("2026-10-12T16:00:01Z"));var today=repo.reserve(token,TrialRepository.hash("today"),digest);assertEquals(1,repo.complete(today));assertEquals(1,repo.complete(yesterday));assertEquals(1,repo.session(token).remaining());
    }
    @Test void serviceFailureRetryRestartAndScopedDedupDoNotPersistContent()throws Exception {
        var gateway=mock(ModelGateway.class);when(gateway.expand(any())).thenThrow(new ApiException(502,"LLM_UPSTREAM_ERROR","fixture failure")).thenReturn(new ModelGateway.Result(List.of(new Candidate("a","PRIVATE_CANDIDATE",List.of("apple"))),false,null));
        var first=service(gateway);String session=first.create("service-client").token();var request=input("request");assertThrows(ApiException.class,()->first.expand(session,request));assertEquals(2,first.session(session).remaining());
        var restarted=service(gateway);assertEquals(1,restarted.expand(session,request).remaining());assertEquals(1,restarted.expand(session,request).remaining());
        var again=service(gateway);assertEquals("REQUEST_ALREADY_COMPLETED",assertThrows(ApiException.class,()->again.expand(session,request)).code);
        assertEquals(0,again.expandAuthorized(session,request,true,true,true,"family-actor").remaining());verify(gateway,times(3)).expand(any());assertFalse(Files.exists(directory.resolve("unused.json")));
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var rows=s.executeQuery("SELECT row_to_json(r)::text FROM ct_trial_requests r")){while(rows.next()){String row=rows.getString(1);assertFalse(row.contains("PRIVATE_CANDIDATE"));assertFalse(row.contains("苹果"));assertFalse(row.contains("private-room"));}}
    }
    @Test void tokenRotationDuringCallPreservesAccountingAndMaximumClientIdWorks(){
        var repo=store();String client="x".repeat(128);repo.create(client,token);var reserved=repo.reserve(token,nonce,digest);String next=TrialRepository.hash("next");repo.create(client,next);assertEquals(1,repo.complete(reserved));assertEquals(1,store().session(next).remaining());assertThrows(ApiException.class,()->repo.reserve(token,nonce,digest));
    }
    @Test void explicitPostgresConfigurationNeverFallsBackToJson()throws Exception {
        Files.writeString(directory.resolve("unused.json"),"sensitive invalid json fixture");var gateway=mock(ModelGateway.class);assertNotNull(service(gateway));
        assertThrows(IllegalArgumentException.class,()->new TrialService(new ObjectMapper(),gateway,directory.resolve("unused.json").toString(),2,false,false,true,false,"postgres","","","",clock));
    }
}
