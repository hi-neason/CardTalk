package com.cardtalk.assets;

import com.cardtalk.api.Application;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssetHttpTest {
    static final Path root;
    static { try { root=Files.createTempDirectory("cardtalk-asset-http-"); } catch(Exception ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("cardtalk.family-repository-mode",()->"json");registry.add("cardtalk.family-data-file",()->root.resolve("family.json").toString());registry.add("cardtalk.data-file",()->root.resolve("trial.json").toString());registry.add("cardtalk.asset-directory",()->root.resolve("assets").toString());registry.add("cardtalk.family-registration-enabled",()->true);registry.add("cardtalk.family-cookie-secure",()->false);
    }
    @LocalServerPort int port;
    @Test void realHttpCookieUploadDownloadAndRevocation() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();var mapper=new ObjectMapper();
        String base="http://127.0.0.1:"+port+"/api/v1";
        var registration=client.send(HttpRequest.newBuilder(URI.create(base+"/accounts/register")).header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"asset_http_user\",\"password\":\"test-password-123\"}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,registration.statusCode());
        var created=client.send(HttpRequest.newBuilder(URI.create(base+"/families")).header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"图片家庭\"}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,created.statusCode());String family=mapper.readTree(created.body()).path("id").asText();
        byte[] png=AssetTest.raster("png",320);String endpoint=base+"/families/"+family+"/assets";
        var upload=client.send(HttpRequest.newBuilder(URI.create(endpoint+"?profileId=profile-1")).header("Origin","http://127.0.0.1:5173").header("Content-Type","image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(201,upload.statusCode());String id=mapper.readTree(upload.body()).path("id").asText();
        var download=client.send(HttpRequest.newBuilder(URI.create(endpoint+"/"+id)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,download.statusCode());assertArrayEquals(png,download.body());assertEquals("nosniff",download.headers().firstValue("X-Content-Type-Options").orElseThrow());
        var logout=client.send(HttpRequest.newBuilder(URI.create(base+"/accounts/logout")).header("Origin","http://127.0.0.1:5173").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,logout.statusCode());
        assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(endpoint+"/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @AfterAll static void cleanup() throws Exception { try(var files=Files.walk(root)) { for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
}
