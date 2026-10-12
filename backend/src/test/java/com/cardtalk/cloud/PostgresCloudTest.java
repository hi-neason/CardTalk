package com.cardtalk.cloud;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.cardtalk.cloud.CloudDtos.*;

class PostgresCloudTest {
    String base,url,user,password,schema,family;
    final ObjectMapper mapper=new ObjectMapper();
    PostgresFamilyRepository repository;
    FamilyService families;
    FamilyService.Login owner,helper,member;
    CloudStore cloud;
    @BeforeEach void setup()throws Exception{
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required via CARDTALK_TEST_PG_URL/USER/PASSWORD");user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_cloud_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
        repository=new PostgresFamilyRepository(url,user,password);families=new FamilyService(repository,true,3600);owner=register("owner");helper=register("helper");member=register("member");family=families.createFamily(owner.account(),"同步家庭").id();
        families.accept(helper.account(),families.invite(owner.account(),family,"helper",List.of("p1")).code());families.accept(member.account(),families.invite(owner.account(),family,"member",List.of()).code());
        cloud=store();
    }
    FamilyService.Login register(String name){return families.register(name,"test-password-123",name);}
    CloudStore store(){return new CloudStore(repository,mapper,null,null,false,true,"postgres",url,user,password);}
    @AfterEach void cleanup()throws Exception{if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    JsonNode profile(String id,String name){return mapper.valueToTree(Map.ofEntries(Map.entry("id",id),Map.entry("name",name),Map.entry("preset","children"),Map.entry("age",8),Map.entry("language","普通话"),Map.entry("interests",""),Map.entry("personality",""),Map.entry("communicationStyle",""),Map.entry("observations",""),Map.entry("observer",""),Map.entry("observedAt",mapper.nullNode()),Map.entry("archived",false),Map.entry("updatedAt","2026-10-12T00:00:00Z")));}
    Operation op(String nonce,String id,long rev,JsonNode payload){return new Operation(nonce,"profile",id,rev,false,payload);}
    Push push(Operation... operations){return new Push(new Consent(false,false),List.of(operations));}
    long revision(Push p){return cloud.push(owner.account(),family,p).results().get(0).revision();}
    @Test void revisionConflictsRetriesChangedNonceAndExplicitRestoration(){
        var create=push(op("op1","p1",0,profile("p1","乐乐")));long first=revision(create);
        assertEquals(first,store().push(owner.account(),family,create).results().get(0).revision());
        assertThrows(ApiException.class,()->cloud.push(member.account(),family,create));
        assertThrows(ApiException.class,()->revision(push(op("op1","p1",first,profile("p1","不同内容")))));
        long second=revision(push(op("op2","p1",first,profile("p1","新资料"))));
        var conflict=assertThrows(CloudConflict.class,()->store().push(owner.account(),family,push(op("stale","p1",first,profile("p1","旧副本")))));
        assertEquals(second,conflict.remote.revision());assertEquals("新资料",conflict.remote.payload().path("name").asText());
        long deleted=revision(push(new Operation("del","profile","p1",second,true,null)));
        assertThrows(CloudConflict.class,()->revision(push(op("resurrect","p1",second,profile("p1","旧副本")))));
        assertTrue(cloud.pull(owner.account(),family,"0",10).entities().get(0).deleted());
        revision(push(op("resolve","p1",deleted,profile("p1","明确恢复"))));assertFalse(cloud.pull(owner.account(),family,"0",10).entities().get(0).deleted());
    }
    @Test void authorizedPaginationDoesNotLeakSharedOrOtherProfilesAndRevocationIsLive(){
        revision(push(op("p1","p1",0,profile("p1","一")),op("p2","p2",0,profile("p2","二")),op("p3","p3",0,profile("p3","三"))));
        var helperPage=cloud.pull(helper.account(),family,"0",1);assertEquals(1,helperPage.entities().size());assertEquals("p1",helperPage.entities().get(0).entityId());assertFalse(helperPage.hasMore());
        assertThrows(ApiException.class,()->cloud.push(helper.account(),family,push(op("denied","p1",0,profile("p1","一")))));
        var first=cloud.pull(owner.account(),family,"0",1);assertTrue(first.hasMore());var second=cloud.pull(owner.account(),family,first.cursor(),1);assertEquals("p2",second.entities().get(0).entityId());
        var third=cloud.pull(owner.account(),family,second.cursor(),1);assertEquals("p3",third.entities().get(0).entityId());assertFalse(third.hasMore());
        families.changeMember(owner.account(),family,helper.account().id(),"helper",List.of());assertTrue(cloud.pull(helper.account(),family,"0",10).entities().isEmpty());
        families.remove(owner.account(),family,member.account().id());assertThrows(ApiException.class,()->cloud.pull(member.account(),family,"0",10));
    }
    @Test void consentSchemaFailureAndConflictRollbackEntireBatch(){
        var observed=profile("p2","二").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)observed).put("observations","私密观察");
        assertThrows(ApiException.class,()->revision(push(op("ok","p1",0,profile("p1","一")),op("no","p2",0,observed))));assertTrue(cloud.pull(owner.account(),family,"0",10).entities().isEmpty());
        revision(push(op("ok","p1",0,profile("p1","一"))));
        assertThrows(CloudConflict.class,()->revision(push(op("rollback","p2",0,profile("p2","二")),op("conflict","p1",0,profile("p1","一")))));assertEquals(1,cloud.pull(owner.account(),family,"0",10).entities().size());
        var passwordPayload=profile("p3","三").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)passwordPayload).put("password","secret");assertThrows(ApiException.class,()->revision(push(op("schema","p3",0,passwordPayload))));
        var image=profile("p3","三").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)image).put("image","data:image/png;base64,AAAA");assertThrows(ApiException.class,()->cloud.push(owner.account(),family,new Push(new Consent(true,true),List.of(op("image","p3",0,image)))));
        assertThrows(ApiException.class,()->revision(push(op("scope","p3",0,profile("other","三")))));
    }
    @Test void assetReferencesRequireConsentFamilyAndEntityScope() {
        var assets=org.mockito.Mockito.mock(com.cardtalk.assets.AssetService.class);
        var withAssets=new CloudStore(repository,mapper,assets,null,false,true,"postgres",url,user,password);
        var photo=profile("p1","照片").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)photo).set("assetRef",mapper.valueToTree(Map.of("id","asset1")));
        org.mockito.Mockito.when(assets.metadata(owner.account(),family,"asset1")).thenReturn(new com.cardtalk.assets.AssetCatalog.Metadata("asset1",family,"p1","image/png",10,1,1,"0".repeat(64),"2026-10-12T00:00:00Z"));
        assertThrows(ApiException.class,()->withAssets.push(owner.account(),family,push(op("nophoto","p1",0,photo))));
        long rev=withAssets.push(owner.account(),family,new Push(new Consent(true,false),List.of(op("photo","p1",0,photo)))).results().get(0).revision();
        assertEquals("asset1",withAssets.pull(owner.account(),family,"0",10).entities().get(0).payload().path("assetRef").path("id").asText());
        org.mockito.Mockito.when(assets.metadata(owner.account(),family,"asset1")).thenReturn(new com.cardtalk.assets.AssetCatalog.Metadata("asset1",family,"p2","image/png",10,1,1,"0".repeat(64),"2026-10-12T00:00:00Z"));
        assertThrows(ApiException.class,()->withAssets.push(owner.account(),family,new Push(new Consent(true,false),List.of(op("wrongscope","p1",rev,photo)))));
        org.mockito.Mockito.when(assets.metadata(owner.account(),family,"asset1")).thenReturn(new com.cardtalk.assets.AssetCatalog.Metadata("asset1","other-family","p1","image/png",10,1,1,"0".repeat(64),"2026-10-12T00:00:00Z"));
        assertThrows(ApiException.class,()->withAssets.push(owner.account(),family,new Push(new Consent(true,false),List.of(op("wrongfamily","p1",rev,photo)))));
    }
    @Test void publicCustomCardRoomRoundTripKeepsProvenance()throws Exception {
        var provenance=mapper.readTree("{\"packageId\":\"daily-v1\",\"title\":\"日常\",\"source\":\"本项目自编\",\"license\":\"CC0-1.0\",\"revision\":12}");
        var card=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree("{\"id\":\"custom-card-public-sip\",\"label\":\"喝水\",\"category\":\"actions\",\"icon\":\"drink\",\"kind\":\"word\",\"sceneIds\":[],\"archived\":false,\"updatedAt\":\"2026-10-12T00:00:00Z\"}");card.set("provenance",provenance);
        var snapshot=card.deepCopy();snapshot.remove(List.of("sceneIds","archived","updatedAt"));
        var scene=mapper.createObjectNode().put("id","custom-scene-public-break").put("name","休息").put("description","活动间歇");scene.set("provenance",provenance);
        var context=mapper.createObjectNode().put("preset","adult").put("sceneId","custom-scene-public-break").put("communicatorName","本人").put("source","unknown");context.set("participants",mapper.createArrayNode());context.set("sceneSnapshot",scene);
        var turn=mapper.createObjectNode().put("id","turn-public").put("createdAt","2026-10-12T00:00:00Z").put("finalText","喝水").put("source","unknown").put("wasEdited",false).put("kind","raw");turn.set("cardSnapshot",mapper.createArrayNode().add(snapshot));turn.set("contextSnapshot",context);turn.set("playbackEvents",mapper.createArrayNode());turn.putNull("feedback");
        var draft=mapper.createObjectNode().put("version",1);draft.set("cards",mapper.createArrayNode());for(String key:List.of("expansion","confirmedText","selectedCandidateId","confirmedTurnId"))draft.putNull(key);
        var room=mapper.createObjectNode().put("id","room-public").put("startedAt","2026-10-12T00:00:00Z").put("endedAt","2026-10-12T00:00:01Z").put("status","ended");room.set("context",context);room.set("turns",mapper.createArrayNode().add(turn));room.set("draft",draft);
        revision(push(new Operation("public-card","card",card.path("id").asText(),0L,false,card),new Operation("public-room","room","room-public",0L,false,room)));
        var loaded=store().pull(owner.account(),family,"0",10).entities().stream().filter(e->e.entityType().equals("room")).findFirst().orElseThrow().payload();
        assertEquals(provenance,loaded.path("turns").get(0).path("cardSnapshot").get(0).path("provenance"));assertEquals(provenance,loaded.path("context").path("sceneSnapshot").path("provenance"));assertFalse(loaded.path("turns").get(0).path("cardSnapshot").get(0).has("sceneIds"));
    }
    @Test void roomSnapshotsCannotSmuggleAnotherProfileAndSharedCardsAreHiddenFromHelpers()throws Exception {
        var card=mapper.readTree("{\"id\":\"card1\",\"label\":\"水\",\"category\":\"daily\",\"icon\":\"water\",\"kind\":\"word\",\"sceneIds\":[],\"archived\":false,\"updatedAt\":\"2026-10-12T00:00:00Z\"}");
        revision(push(new Operation("card","card","card1",0L,false,card)));
        assertTrue(cloud.pull(helper.account(),family,"0",10).entities().isEmpty());
        var ctx=mapper.readTree("{\"preset\":\"children\",\"sceneId\":null,\"participants\":[],\"communicatorName\":\"孩子\",\"source\":\"unknown\",\"profileId\":\"p1\"}");
        var badTurn=mapper.createObjectNode();badTurn.put("id","t1");badTurn.put("createdAt","2026-10-12T00:00:00Z");var cardSnapshot=card.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)cardSnapshot).remove(List.of("sceneIds","archived","updatedAt"));badTurn.set("cardSnapshot",mapper.createArrayNode().add(cardSnapshot));var badContext=ctx.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)badContext).put("profileId","p2");badTurn.set("contextSnapshot",badContext);
        var room=mapper.createObjectNode();room.put("id","room1");room.set("context",ctx);room.put("startedAt","2026-10-12T00:00:00Z");room.putNull("endedAt");room.put("status","active");room.set("turns",mapper.createArrayNode().add(badTurn));room.set("draft",mapper.createObjectNode());
        assertThrows(ApiException.class,()->revision(push(new Operation("room","room","room1",0L,false,room))));
    }
    @Test void httpCookieBearerOriginBodyLimitAndConflictContracts()throws Exception {
        var validator=new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();validator.afterPropertiesSet();
        try {
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CloudController(families,cloud)).setControllerAdvice(new com.cardtalk.api.Errors()).setValidator(validator).addFilters(new FamilyRequestFilter("http://127.0.0.1:5173")).build();
            String path="/api/v1/families/"+family+"/sync", body=mapper.writeValueAsString(push(op("http","p1",0,profile("p1","孩子"))));
            var cookie=new jakarta.servlet.http.Cookie("cardtalk_family",owner.token());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/status")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/status").header("Authorization","Bearer trial-fixture-token")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
            for(var account:List.of(member,helper))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/status").header("Authorization","Bearer "+account.token())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/push").cookie(cookie).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/push").cookie(cookie).header("Origin","https://other.example").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/push").cookie(cookie).header("Origin","http://127.0.0.1:5173").contentType("application/json").content(body+" ".repeat(40000))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/push").cookie(cookie).header("Origin","http://127.0.0.1:5173").contentType("application/json").content(" ".repeat(2*1024*1024+1))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/push").header("Authorization","Bearer "+owner.token()).contentType("application/json").content(mapper.writeValueAsString(push(op("http-stale","p1",0,profile("p1","旧")))))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SYNC_CONFLICT")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.remote.payload.name").value("孩子"));
        } finally {validator.close();}
    }
    @Test void adoptedReportNarrativeRoundTripsButRejectsUnknownFieldsAndOversize() {
        var report=mapper.createObjectNode();report.put("id","report-narrative");report.put("createdAt","2026-10-12T00:00:00Z");report.put("metricVersion",1);report.putObject("filters");report.putArray("evidenceIds");report.put("sourceSignature","[]");
        var metrics=report.putObject("metrics");for(String key:List.of("activeDays","confirmedCount","roomCount"))metrics.put(key,0);metrics.put("sampleWarning",true);metrics.putNull("feedbackCoverage");metrics.putNull("understoodRate");
        for(var entry:Map.of("inputCards","total,average,max","playbackCounts","completed,failed,interrupted,unavailable,startedOnly,neverPlayed","feedback","understood,clarify,notUnderstood,unrecorded","kind","raw,ai,edited,saved","source","independent,assisted,unknown").entrySet()){var group=metrics.putObject(entry.getKey());for(String field:entry.getValue().split(","))group.put(field,0);}
        var process=metrics.putObject("process");for(String field:"collectedCount,missingCount,usableTimingCount,generationStarted,generationSucceeded,generationFailed,generationCancelled,candidatesRejected,candidateEditStarted".split(","))process.put(field,0);process.putNull("averageSeconds");process.putNull("rejectionRate");for(String key:List.of("categoryCounts","topCards","sceneCounts","dailyCounts"))metrics.putArray(key);
        var narrative=report.putObject("narrative");narrative.put("text","本次没有已确认表达。");narrative.put("createdAt","2026-10-12T00:00:00Z");narrative.put("provider","deepseek");narrative.put("promptVersion","report-v1");
        long revision=revision(push(new Operation("narrative","report","report-narrative",0L,false,report)));
        assertEquals("本次没有已确认表达。",cloud.pull(owner.account(),family,"0",10).entities().get(0).payload().path("narrative").path("text").asText());
        narrative.put("password","forbidden");assertThrows(ApiException.class,()->revision(push(new Operation("bad-narrative","report","report-narrative",revision,false,report))));narrative.remove("password");narrative.put("text","长".repeat(1501));assertThrows(ApiException.class,()->revision(push(new Operation("long-narrative","report","report-narrative",revision,false,report))));
    }
    @Test void migrationIndependentAndDisabledStorageNeverPretendsSuccess()throws Exception{
        new PostgresFamilyRepository(url,user,password);store();
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT version FROM ct_family_schema_version")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        var disabled=new CloudStore(repository,mapper,null,null,false,false,"postgres",url,user,password);assertFalse(disabled.enabled());assertThrows(ApiException.class,()->disabled.pull(owner.account(),family,"0",10));
        var unconfigured=new CloudStore(repository,mapper,null,null,false,true,"json","","","");assertFalse(unconfigured.configured());assertThrows(ApiException.class,()->unconfigured.push(owner.account(),family,push(op("x","p1",0,profile("p1","一")))));
    }
}
