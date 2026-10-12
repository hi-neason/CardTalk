package com.cardtalk.operationsmetrics;

import com.cardtalk.api.Errors;
import com.cardtalk.content.*;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContentPublicationBodyTest {
    String base,url,user,password,schema;
    @BeforeEach void setup()throws Exception {
        base=System.getenv("CARDTALK_TEST_PG_URL");assumeTrue(base!=null&&!base.isBlank(),"Real PostgreSQL required");user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");schema="ct_content_body_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
    }
    @AfterEach void cleanup()throws Exception {if(schema!=null)try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    @Test void legalLargeChinesePublicationIsAllowedOnlyOnExactPostPath()throws Exception {
        var mapper=new ObjectMapper();var families=new FamilyService(new PostgresFamilyRepository(url,user,password),true,3600);var operator=families.register("operator","test-password-123","fixture");
        var content=new ContentService(mapper,true,"postgres",url,user,password,operator.account().id());
        var mvc=MockMvcBuilders.standaloneSetup(new ContentController(content,families)).setControllerAdvice(new Errors()).addFilters(new FamilyRequestFilter("http://localhost:5173")).build();
        var pack=mapper.createObjectNode();pack.put("id","large-chinese");pack.put("title","完整中文资源包");pack.put("source","自编文字");pack.put("license","CC0-1.0");var cards=pack.putArray("cards");var scenes=pack.putArray("scenes");
        for(int i=0;i<70;i++){var card=cards.addObject();card.put("id","card-"+i);card.put("label","卡片"+i);card.put("category","actions");card.put("icon","drink");card.put("kind","phrase");card.put("description","中".repeat(500));card.putArray("sceneIds");}
        for(int i=0;i<50;i++){var scene=scenes.addObject();scene.put("id","scene-"+i);scene.put("name","场景"+i);scene.put("description","中".repeat(1000));scene.putArray("recommendedCardIds");}
        byte[] bytes=mapper.writeValueAsBytes(pack);assertTrue(bytes.length>250000);assertTrue(bytes.length<512*1024);
        String path="/api/v1/operations/content/versions";var cookie=new Cookie("cardtalk_family",operator.token());
        mvc.perform(post(path).cookie(cookie).contentType("application/json").content(bytes)).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).header("Origin","https://other.invalid").contentType("application/json").content(bytes)).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(cookie).header("Origin","http://localhost:5173").contentType("application/json").content(bytes)).andExpect(status().isOk());
        assertEquals(1,((List<?>)content.versions(operator.account().id())).size());
        mvc.perform(post(path).cookie(cookie).header("Origin","http://localhost:5173").contentType("application/json").content(" ".repeat(512*1024+1).getBytes(StandardCharsets.UTF_8))).andExpect(status().isPayloadTooLarge());
        for(String other:List.of("/api/v1/operations/content/release",path+"/extra","/api/v1/operations/tickets/x/updates"))mvc.perform(post(other).cookie(cookie).header("Origin","http://localhost:5173").contentType("application/json").content(" ".repeat(32769))).andExpect(status().isPayloadTooLarge());
    }
}
