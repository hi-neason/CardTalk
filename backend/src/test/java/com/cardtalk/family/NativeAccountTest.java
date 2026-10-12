package com.cardtalk.family;

import com.cardtalk.api.Errors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NativeAccountTest {
    @TempDir Path directory;
    @Test void explicitPasswordNativeSessionHasNoCookieAndCanBeRevoked()throws Exception {
        var mapper=new ObjectMapper();var families=new FamilyService(new JsonFamilyRepository(mapper,directory.resolve("accounts.json").toString()),true,3600);
        try(var validator=new LocalValidatorFactoryBean()){
            validator.afterPropertiesSet();var mvc=MockMvcBuilders.standaloneSetup(new NativeAccountController(families),new FamilyController(families,false)).setValidator(validator).setControllerAdvice(new Errors()).addFilters(new FamilyRequestFilter("http://localhost:5173")).build();
            var response=mvc.perform(post("/api/v1/accounts/native/register").contentType("application/json").content("{\"username\":\"native_owner\",\"password\":\"long-test-password\"}")).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
            String token=mapper.readTree(response.getContentAsString()).path("token").asText();assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
            mvc.perform(get("/api/v1/accounts/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.account.username").value("native_owner")).andExpect(jsonPath("$.token").doesNotExist());
            mvc.perform(post("/api/v1/accounts/logout").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/accounts/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/accounts/native/login").header("Origin","https://evil.invalid").contentType("application/json").content("{\"username\":\"native_owner\",\"password\":\"long-test-password\"}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/accounts/native/login").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        }
    }
}
