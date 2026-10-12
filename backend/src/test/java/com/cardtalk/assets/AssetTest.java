package com.cardtalk.assets;

import com.cardtalk.api.*;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssetTest {
    @TempDir Path directory;
    ObjectMapper mapper=new ObjectMapper();
    FamilyService families;
    FamilyService.Login owner,helper,outsider;
    String family;
    LocalAssetStore store;
    AssetCatalog catalog;
    AssetService service;
    MockMvc mvc;
    static byte[] raster(String format,int size) throws IOException {
        var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);var random=new Random(42+size);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) image.setRGB(x,y,random.nextInt(0x1000000));
        var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,output));return output.toByteArray();
    }
    @BeforeEach void setup() throws Exception {
        families=new FamilyService(new JsonFamilyRepository(mapper,directory.resolve("family.json").toString()),true,3600);
        owner=families.register("owner","test-password-123","owner");helper=families.register("helper","test-password-123","helper");outsider=families.register("outsider","test-password-123","outsider");
        family=families.createFamily(owner.account(),"图片家庭").id();families.accept(helper.account(),families.invite(owner.account(),family,"helper",List.of("profile-1")).code());
        store=new LocalAssetStore(directory.resolve("assets").toString(),16777216);catalog=new AssetCatalog(mapper,store,directory.resolve("assets").toString());service=new AssetService(families,catalog,16777216,16384,40000000);
        mvc=MockMvcBuilders.standaloneSetup(new AssetController(families,service)).setControllerAdvice(new Errors()).addFilters(new FamilyRequestFilter("http://127.0.0.1:5173")).build();
    }
    @Test void uploadsLargerThan200KBRemainImmutableAndSurviveRestart() throws Exception {
        byte[] original=raster("png",320),replacement=raster("jpeg",64);assertTrue(original.length>200*1024);
        var first=service.upload(owner.account(),family,"profile-1","image/png",original);var second=service.upload(owner.account(),family,"profile-1","image/jpeg",replacement);
        assertNotEquals(first.id(),second.id());assertEquals(original.length,first.bytes());assertEquals(320,first.width());assertEquals(AssetCatalog.sha256(original),first.sha256());
        assertArrayEquals(original,service.download(owner.account(),family,first.id()).bytes());
        var reopened=new AssetService(families,new AssetCatalog(mapper,new LocalAssetStore(directory.resolve("assets").toString(),16777216),directory.resolve("assets").toString()),16777216,16384,40000000);
        assertArrayEquals(original,reopened.download(owner.account(),family,first.id()).bytes());assertArrayEquals(replacement,reopened.download(owner.account(),family,second.id()).bytes());
        assertThrows(ApiException.class,()->catalog.publish(first,replacement));assertThrows(IOException.class,()->store.put(first.id(),replacement));assertArrayEquals(original,store.read(first.id()));
    }
    @Test void helperScopeCrossFamilyAndRevokedMembershipAreEnforced() throws Exception {
        byte[] png=raster("png",8);var allowed=service.upload(owner.account(),family,"profile-1","image/png",png);var blocked=service.upload(owner.account(),family,"profile-2","image/png",png);
        assertArrayEquals(png,service.download(helper.account(),family,allowed.id()).bytes());
        assertThrows(ApiException.class,()->service.download(helper.account(),family,blocked.id()));assertThrows(ApiException.class,()->service.upload(helper.account(),family,"profile-1","image/png",png));
        assertThrows(ApiException.class,()->service.download(outsider.account(),family,allowed.id()));String other=families.createFamily(outsider.account(),"别的家庭").id();assertThrows(ApiException.class,()->service.download(outsider.account(),other,allowed.id()));
        families.remove(owner.account(),family,helper.account().id());assertThrows(ApiException.class,()->service.download(families.authenticate(helper.token()),family,allowed.id()));
    }
    @Test void metadataChecksScopeWithoutReadingImageBytes() throws Exception {
        var allowed=service.upload(owner.account(),family,"profile-1","image/png",raster("png",8));
        var blocked=service.upload(owner.account(),family,"profile-2","image/png",raster("png",8));
        assertEquals(allowed,service.metadata(helper.account(),family,allowed.id()));
        assertThrows(ApiException.class,()->service.metadata(helper.account(),family,blocked.id()));
        assertThrows(ApiException.class,()->service.metadata(outsider.account(),family,allowed.id()));
        String other=families.createFamily(outsider.account(),"别的家庭").id();
        assertThrows(ApiException.class,()->service.metadata(outsider.account(),other,allowed.id()));
        assertThrows(ApiException.class,()->service.metadata(owner.account(),family,UUID.randomUUID().toString()));
        // A metadata lookup must not load blob bytes; download still verifies them.
        Files.delete(directory.resolve("assets/objects/"+allowed.id()+".blob"));
        assertEquals(allowed,service.metadata(owner.account(),family,allowed.id()));
        assertThrows(ApiException.class,()->service.download(owner.account(),family,allowed.id()));
        families.remove(owner.account(),family,helper.account().id());
        assertThrows(ApiException.class,()->service.metadata(helper.account(),family,allowed.id()));
    }
    @Test void invalidFormatsTrailersDimensionsAndBudgetsAreRejected() throws Exception {
        byte[] png=raster("png",16),jpeg=raster("jpeg",16);byte[] appended=Arrays.copyOf(png,png.length+8);byte[] broken=png.clone();broken[50]^=1;
        for(byte[] bytes:List.of("<svg onload='alert(1)'/>".getBytes(),jpeg,appended,broken,Arrays.copyOf(png,png.length-5))) assertThrows(ApiException.class,()->service.upload(owner.account(),family,"profile-1","image/png",bytes));
        assertThrows(ApiException.class,()->service.upload(owner.account(),family,"profile-1","image/svg+xml",png));
        assertThrows(ApiException.class,()->new AssetService(families,catalog,32,16384,40000000).upload(owner.account(),family,"profile-1","image/png",png));
        assertThrows(ApiException.class,()->new AssetService(families,catalog,16777216,8,40000000).upload(owner.account(),family,"profile-1","image/png",png));
        assertThrows(ApiException.class,()->new AssetService(families,catalog,16777216,16384,100).upload(owner.account(),family,"profile-1","image/png",png));
        byte[] trailer=Arrays.copyOf(jpeg,jpeg.length+2);assertThrows(ApiException.class,()->service.upload(owner.account(),family,"profile-1","image/jpeg",trailer));
    }
    @Test void webpContainerIsCheckedWithoutClaimingACompleteDecoder() throws Exception {
        byte[] valid=Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
        var metadata=service.upload(owner.account(),family,"profile-1","image/webp",valid);assertEquals(1,metadata.width());assertArrayEquals(valid,service.download(owner.account(),family,metadata.id()).bytes());
        byte[] bad=valid.clone();bad[4]++;assertThrows(ApiException.class,()->service.upload(owner.account(),family,"profile-1","image/webp",bad));
        assertThrows(ApiException.class,()->service.upload(owner.account(),family,"profile-1","image/png",valid));
    }
    @Test void metadataWriteFailureDoesNotPublishAndOrphansAreCleanedAtRestart() throws Exception {
        var failureCatalog=new AssetCatalog(mapper,store,directory.resolve("assets").toString()) { protected void persist(Snapshot snapshot) throws IOException { throw new IOException("fixture full disk"); } };
        var failing=new AssetService(families,failureCatalog,16777216,16384,40000000);byte[] png=raster("png",8);
        assertThrows(ApiException.class,()->failing.upload(owner.account(),family,"profile-1","image/png",png));
        try(var files=Files.list(directory.resolve("assets/objects"))) { assertEquals(0,files.count()); }
        assertFalse(Files.exists(directory.resolve("assets/catalog.json")));
        String orphan=UUID.randomUUID().toString();store.put(orphan,png);Path temporary=Files.createTempFile(directory.resolve("assets"),"asset-catalog-",".tmp");new AssetCatalog(mapper,store,directory.resolve("assets").toString());assertFalse(Files.exists(directory.resolve("assets/objects/"+orphan+".blob")));assertFalse(Files.exists(temporary));
        var saved=service.upload(owner.account(),family,"profile-1","image/png",png);
        Files.writeString(directory.resolve("assets/catalog.json"),"{}");assertThrows(IOException.class,()->new AssetCatalog(mapper,store,directory.resolve("assets").toString()));assertTrue(Files.exists(directory.resolve("assets/objects/"+saved.id()+".blob")));
    }
    @Test void modifiedObjectIntegrityAndPathInjectionFailClosed() throws Exception {
        byte[] png=raster("png",8);var metadata=service.upload(owner.account(),family,"profile-1","image/png",png);
        assertThrows(IOException.class,()->store.put("../outside",png));assertThrows(IOException.class,()->store.read("../catalog"));
        assertThrows(ApiException.class,()->service.upload(owner.account(),family,"evil\r\nHeader:value","image/png",png));
        Files.writeString(directory.resolve("assets/objects/"+metadata.id()+".blob"),"modified");assertThrows(ApiException.class,()->service.download(owner.account(),family,metadata.id()));assertThrows(IOException.class,()->new AssetCatalog(mapper,store,directory.resolve("assets").toString()));
    }
    @Test void httpContentTypeHeadersOriginAndJSONLimitStayProtected() throws Exception {
        String endpoint="/api/v1/families/"+family+"/assets";byte[] png=raster("png",320);
        var result=mvc.perform(post(endpoint).param("profileId","profile-1").header("Authorization","Bearer "+owner.token()).contentType("image/png").content(png)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        String id=mapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get(endpoint+"/"+id).header("Authorization","Bearer "+helper.token())).andExpect(status().isOk()).andExpect(content().bytes(png)).andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(post(endpoint).param("profileId","profile-1").cookie(new Cookie("cardtalk_family",owner.token())).contentType("image/png").content(png)).andExpect(status().isForbidden());
        mvc.perform(post(endpoint).param("profileId","profile-1").header("Authorization","Bearer "+owner.token()).contentType("image/svg+xml").content(png)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(endpoint).param("profileId","profile-1").header("Authorization","Bearer trial-token").contentType("image/png").content(png)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/families/"+family+"/members").contentType("application/json").content(new byte[32769])).andExpect(status().isPayloadTooLarge());
        var limited=MockMvcBuilders.standaloneSetup(new AssetController(families,service)).setControllerAdvice(new Errors()).addFilters(new FamilyRequestFilter("http://127.0.0.1:5173",32768)).build();
        limited.perform(post(endpoint).param("profileId","profile-1").header("Authorization","Bearer "+owner.token()).contentType("image/png").content(png)).andExpect(status().isPayloadTooLarge());
    }
}
