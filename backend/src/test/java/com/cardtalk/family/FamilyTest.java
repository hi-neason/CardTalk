package com.cardtalk.family;

import com.cardtalk.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.cardtalk.family.FamilyRepository.*;

class FamilyTest {
    @TempDir Path dir;
    ObjectMapper mapper=new ObjectMapper();
    JsonFamilyRepository repo;
    FamilyService service;
    LocalValidatorFactoryBean validator;
    MockMvc mvc;
    final String password="valid-password-123";
    @BeforeEach void setup() throws Exception {
        repo=new JsonFamilyRepository(mapper,dir.resolve("families.json").toString());service=new FamilyService(repo,true,3600);
        validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(new FamilyController(service,false)).setControllerAdvice(new Errors()).setValidator(validator).addFilters(new FamilyRequestFilter("http://127.0.0.1:5173")).build();
    }
    @AfterEach void close() { validator.close(); }
    FamilyService.Login register(String name) { return service.register(name,password,name); }
    @Test void saltedPasswordsHashedTokensRestartAndLogout() throws Exception {
        var first=register("FIRST");var second=register("second");
        assertEquals("first",first.account().username());assertEquals(43,first.token().length());
        var snapshot=repo.read(d->d);assertNotEquals(snapshot.accounts().get(0).salt(),snapshot.accounts().get(1).salt());
        assertNotEquals(snapshot.accounts().get(0).passwordHash(),snapshot.accounts().get(1).passwordHash());
        String raw=Files.readString(dir.resolve("families.json"));assertFalse(raw.contains(password));assertFalse(raw.contains(first.token()));assertFalse(raw.contains(second.token()));
        var restarted=new FamilyService(new JsonFamilyRepository(mapper,dir.resolve("families.json").toString()),true,3600);
        assertEquals(first.account(),restarted.authenticate(first.token()));
        restarted.logout(first.token());assertThrows(ApiException.class,()->restarted.authenticate(first.token()));
        assertThrows(ApiException.class,()->service.authenticate("trial-fixture-token"));
    }
    @Test void sessionsExpireAndRegistrationCanClose() {
        var login=register("expiry");repo.transact(d->{var s=d.sessions().get(0);d.sessions().set(0,new Session(s.accountId(),s.tokenHash(),1));return null;});
        assertThrows(ApiException.class,()->service.authenticate(login.token()));
        var disabled=new FamilyService(repo,false,3600);assertThrows(ApiException.class,()->disabled.register("closed",password,"ip"));
    }
    @Test void credentialsErrorsAreGenericAndRateLimited() {
        register("exists");
        String known=assertThrows(ApiException.class,()->service.login("exists","wrong-password","a")).getMessage();
        String unknown=assertThrows(ApiException.class,()->service.login("missing","wrong-password","b")).getMessage();assertEquals(known,unknown);
        for(int i=1;i<10;i++) assertEquals(known,assertThrows(ApiException.class,()->service.login("missing","wrong-password","b")).getMessage());
        assertTrue(assertThrows(ApiException.class,()->service.login("missing","wrong-password","b")).getMessage().contains("15"));
    }
    @Test void invitationsAreOneTimeScopedAndRemovalTakesEffectWithExistingSession() {
        var owner=register("owner");var helper=register("helper");var outsider=register("outsider");var family=service.createFamily(owner.account(),"家庭");
        var invite=service.invite(owner.account(),family.id(),"helper",List.of("profile-1"));service.accept(helper.account(),invite.code());
        assertThrows(ApiException.class,()->service.accept(outsider.account(),invite.code()));
        service.authorizeProfile(service.authenticate(helper.token()),family.id(),"profile-1",false);
        assertThrows(ApiException.class,()->service.authorizeProfile(helper.account(),family.id(),"profile-2",false));
        assertThrows(ApiException.class,()->service.authorizeProfile(helper.account(),family.id(),"profile-1",true));
        assertThrows(ApiException.class,()->service.members(outsider.account(),family.id()));
        var other=service.createFamily(outsider.account(),"另外家庭");assertThrows(ApiException.class,()->service.authorizeProfile(helper.account(),other.id(),"profile-1",false));
        service.remove(owner.account(),family.id(),helper.account().id());
        assertThrows(ApiException.class,()->service.authorizeProfile(service.authenticate(helper.token()),family.id(),"profile-1",false));
        assertTrue(service.families(helper.account()).isEmpty());
    }
    @Test void expiredInvitesAndOwnerAdminBoundaries() {
        var owner=register("owner");var admin=register("admin");var member=register("member");var family=service.createFamily(owner.account(),"家庭");
        assertThrows(ApiException.class,()->service.invite(owner.account(),family.id(),"owner",List.of()));
        var expired=service.invite(owner.account(),family.id(),"member",List.of());
        repo.transact(d->{var i=d.invitations().get(0);d.invitations().set(0,new Invitation(i.familyId(),i.issuerId(),i.tokenHash(),i.role(),i.profileAccess(),1));return null;});
        assertThrows(ApiException.class,()->service.accept(member.account(),expired.code()));
        service.accept(admin.account(),service.invite(owner.account(),family.id(),"admin",List.of()).code());
        assertThrows(ApiException.class,()->service.remove(admin.account(),family.id(),owner.account().id()));
        assertThrows(ApiException.class,()->service.changeMember(admin.account(),family.id(),admin.account().id(),"owner",List.of()));
        assertThrows(ApiException.class,()->service.remove(owner.account(),family.id(),owner.account().id()));
        var revoked=service.invite(admin.account(),family.id(),"member",List.of());
        service.changeMember(owner.account(),family.id(),admin.account().id(),"owner",List.of());assertThrows(ApiException.class,()->service.accept(member.account(),revoked.code()));service.remove(owner.account(),family.id(),owner.account().id());assertEquals(1,service.members(admin.account(),family.id()).size());
    }
    @Test void writeFailuresDoNotPublishAndCorruptionDoesNotReset() throws Exception {
        var owner=register("owner");String before=Files.readString(dir.resolve("families.json"));
        var failing=new JsonFamilyRepository(mapper,dir.resolve("families.json").toString()) { protected void persist(Data data) throws IOException { throw new IOException("fixture failure"); } };
        var failedService=new FamilyService(failing,true,3600);
        assertThrows(ApiException.class,()->failedService.createFamily(owner.account(),"不可保存"));assertTrue(failedService.families(owner.account()).isEmpty());assertEquals(before,Files.readString(dir.resolve("families.json")));
        Files.writeString(dir.resolve("broken.json"),"{}");assertThrows(IOException.class,()->new JsonFamilyRepository(mapper,dir.resolve("broken.json").toString()));assertEquals("{}",Files.readString(dir.resolve("broken.json")));
    }
    @Test void httpCookieContractOriginBodyLimitsAndRevocation() throws Exception {
        var registered=mvc.perform(post("/api/v1/accounts/register").contentType("application/json").content("{\"username\":\"cookie_user\",\"password\":\"valid-password-123\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.account.username").value("cookie_user")).andExpect(jsonPath("$.token").doesNotExist()).andReturn();
        String setCookie=registered.getResponse().getHeader("Set-Cookie");assertTrue(setCookie.contains("HttpOnly"));assertTrue(setCookie.contains("SameSite=Strict"));
        String token=setCookie.split(";",2)[0].split("=",2)[1];Cookie cookie=new Cookie("cardtalk_family",token);
        mvc.perform(get("/api/v1/accounts/me").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/families").cookie(cookie).contentType("application/json").content("{\"name\":\"家庭\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/families").cookie(cookie).header("Origin","http://evil.test").contentType("application/json").content("{\"name\":\"家庭\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/families").cookie(cookie).header("Origin","http://127.0.0.1:5173").contentType("application/json").content("{\"name\":\"家庭\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("owner"));
        mvc.perform(post("/api/v1/accounts/register").contentType("application/json").content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/v1/accounts/register").contentType("application/json").content("{\"username\":\"short\",\"password\":\"bad\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/accounts/logout").cookie(cookie).header("Origin","http://127.0.0.1:5173")).andExpect(status().isOk()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(get("/api/v1/accounts/me").cookie(cookie)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void invitationAndMemberHttpContractSupportsPutAndPatch() throws Exception {
        var owner=register("http_owner");var helper=register("http_helper");var family=service.createFamily(owner.account(),"契约家庭");
        var response=mvc.perform(post("/api/v1/families/"+family.id()+"/invitations").header("Authorization","Bearer "+owner.token()).contentType("application/json").content("{\"role\":\"helper\",\"profileAccess\":[\"profile-1\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").isNumber()).andReturn();
        String code=mapper.readTree(response.getResponse().getContentAsString()).path("code").asText();
        mvc.perform(post("/api/v1/families/invitations/accept").header("Authorization","Bearer "+helper.token()).contentType("application/json").content(mapper.writeValueAsString(Map.of("code",code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("helper")).andExpect(jsonPath("$.profileAccess[0]").value("profile-1"));
        String path="/api/v1/families/"+family.id()+"/members/"+helper.account().id();
        for(var request:List.of(put(path),patch(path))) mvc.perform(request.header("Authorization","Bearer "+owner.token()).contentType("application/json").content("{\"role\":\"member\",\"profileAccess\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(true));
        mvc.perform(delete(path).header("Authorization","Bearer "+owner.token())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/families/"+family.id()+"/members").header("Authorization","Bearer "+helper.token())).andExpect(status().isForbidden());
    }
    @Test void bearerSessionWorksButTrialTokenDoesNot() throws Exception {
        var user=register("native_user");mvc.perform(post("/api/v1/families").header("Authorization","Bearer "+user.token()).contentType("application/json").content("{\"name\":\"本机家庭\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/families").header("Authorization","Bearer trial-token")).andExpect(status().isUnauthorized());
    }
    @Test void normalizedRoutesCannotBypassOriginOrBodyGuards() throws Exception {
        var user=register("normalized_user");Cookie cookie=new Cookie("cardtalk_family",user.token());
        for(String path:List.of("/api/v1/accounts;x/logout","/api/v1/accounts%3Bx/logout","/api/v1/%61ccounts/logout")) {
            mvc.perform(post(java.net.URI.create(path)).cookie(cookie).header("Origin","http://127.0.0.1:9999").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
            assertEquals(user.account(),service.authenticate(user.token()));
        }
        mvc.perform(post(java.net.URI.create("/api/v1/accounts;x/register")).contentType("application/json").content("x".repeat(32769)))
            .andExpect(status().isPayloadTooLarge());
    }

}
