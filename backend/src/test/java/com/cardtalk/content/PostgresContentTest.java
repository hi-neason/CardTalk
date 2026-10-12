package com.cardtalk.content;
import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
class PostgresContentTest {
    String base,url,user,password,schema;ObjectMapper mapper=new ObjectMapper();ContentService service;
    @BeforeEach void setup()throws Exception{base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required");user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_content_test_"+UUID.randomUUID().toString().replace("-","");try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;service=store();}
    ContentService store(){return new ContentService(mapper,true,"postgres",url,user,password,"operator");}
    @AfterEach void cleanup()throws Exception{if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    ObjectNode pack(String id)throws Exception{return (ObjectNode)mapper.readTree("""
      {"id":"%s","title":"测试资源","source":"自编文字","license":"CC0-1.0","cards":[{"id":"sip","label":"小口喝水","category":"actions","icon":"drink","kind":"phrase","description":"饮水需求","sceneIds":["rehab"]}],"scenes":[{"id":"rehab","name":"康复活动","description":"活动与休息","recommendedCardIds":["sip"]}]}
      """.formatted(id));}
    @Test void permissionImmutablePublishReleaseRollbackAndRestart()throws Exception{assertThrows(ApiException.class,()->service.publish("member",pack("v1")));assertThrows(ApiException.class,()->service.versions("member"));service.publish("operator",pack("v1"));assertThrows(ApiException.class,()->service.publish("operator",pack("v1")));assertTrue(((JsonNode)service.current()).path("package").isNull());service.release("operator","v1",0);service.publish("operator",pack("v2"));service.release("operator","v2",1);assertThrows(ApiException.class,()->service.release("operator","v1",1));service.release("operator","v1",2);var current=(JsonNode)store().current();assertEquals(3,current.path("revision").asInt());assertEquals("v1",current.path("package").path("id").asText());assertEquals(3,((List<?>)service.audit("operator")).size());}
    @Test void concurrentRevisionHasSingleWinner()throws Exception{service.publish("operator",pack("v1"));service.publish("operator",pack("v2"));try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();for(String version:List.of("v1","v2"))tasks.add(pool.submit(()->{gate.await();try{service.release("operator",version,0);return true;}catch(ApiException e){return false;}}));gate.countDown();assertEquals(1,(tasks.get(0).get()?1:0)+(tasks.get(1).get()?1:0));assertEquals(1,((List<?>)service.audit("operator")).size());}}
    @Test void privatePayloadAndUnlicensedContentRejected()throws Exception{var p=pack("v1");p.put("image","private-photo");assertThrows(ApiException.class,()->service.publish("operator",p));p.remove("image");((ObjectNode)p.path("cards").get(0)).put("observations","private");assertThrows(ApiException.class,()->service.publish("operator",p));((ObjectNode)p.path("cards").get(0)).remove("observations");p.put("license","");assertThrows(ApiException.class,()->service.publish("operator",p));assertTrue(((JsonNode)service.current()).path("package").isNull());}
    @Test void httpPublicationRequiresAuthenticationAndAllowedOrigin()throws Exception{
        var families=org.mockito.Mockito.mock(com.cardtalk.family.FamilyService.class);
        org.mockito.Mockito.when(families.authenticate(org.mockito.ArgumentMatchers.any())).thenThrow(new ApiException(401,"SESSION_REQUIRED","登录失效"));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ContentController(service,families)).setControllerAdvice(new com.cardtalk.api.Errors()).addFilters(new com.cardtalk.family.FamilyRequestFilter("http://localhost:5173")).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/resources/current")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/operations/content/versions").header("Origin","http://localhost:5173").contentType("application/json").content(pack("v1").toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/operations/content/versions").header("Origin","https://other.invalid").contentType("application/json").content(pack("v1").toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    @Test void legalLargeChineseVersionAndOversizeRequestBound()throws Exception{
        var families=org.mockito.Mockito.mock(com.cardtalk.family.FamilyService.class);
        org.mockito.Mockito.when(families.authenticate("valid")).thenReturn(new com.cardtalk.family.FamilyService.Identity("operator","operator"));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ContentController(service,families)).setControllerAdvice(new com.cardtalk.api.Errors()).addFilters(new com.cardtalk.family.FamilyRequestFilter("http://localhost:5173")).build();
        var p=pack("large-v1");var cards=mapper.createArrayNode();for(int i=0;i<70;i++){var card=((ObjectNode)p.path("cards").get(0)).deepCopy();card.put("id","card-"+i);card.put("label","词语"+i);card.put("description","说".repeat(500));card.set("sceneIds",mapper.createArrayNode());cards.add(card);}p.set("cards",cards);p.set("scenes",mapper.createArrayNode());String body=p.toString();assertTrue(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/operations/content/versions").cookie(new jakarta.servlet.http.Cookie("cardtalk_family","valid")).header("Origin","http://localhost:5173").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/operations/content/versions").cookie(new jakarta.servlet.http.Cookie("cardtalk_family","valid")).header("Origin","http://localhost:5173").contentType("application/json").content(body+" ".repeat(512*1024))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge());
    }
    @Test void disabledAndMissingPostgresFailClosed(){var disabled=new ContentService(mapper,false,"json","","","","");assertThrows(ApiException.class,disabled::current);assertThrows(IllegalArgumentException.class,()->new ContentService(mapper,true,"json","","","","") );}
}
