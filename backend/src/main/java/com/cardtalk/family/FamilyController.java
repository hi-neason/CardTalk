package com.cardtalk.family;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class FamilyController {
    private static final String COOKIE="cardtalk_family";
    private final FamilyService service;
    private final boolean secure;
    public record Credentials(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,40}") String username,@NotNull @Size(min=12,max=128) String password) {}
    public record FamilyInput(@NotBlank @Size(max=100) String name) {}
    public record MemberInput(@NotBlank @Pattern(regexp="owner|admin|member|helper") String role,@NotNull @Size(max=100) List<@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String> profileAccess) {}
    public record AcceptInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String code) {}
    public FamilyController(FamilyService service,@Value("${cardtalk.family-cookie-secure:false}") boolean secure) { this.service=service;this.secure=secure; }
    private String token(HttpServletRequest request) {
        // A supplied cookie takes precedence; do not silently downgrade an invalid cookie to another identity.
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals(COOKIE)) return cookie.getValue();
        String value=request.getHeader("Authorization");return value!=null&&value.startsWith("Bearer ")?value.substring(7):null;
    }
    private FamilyService.Identity account(HttpServletRequest request) { return service.authenticate(token(request)); }
    private String cookie(String value,long seconds) { return ResponseCookie.from(COOKIE,value).httpOnly(true).secure(secure).sameSite("Strict").path("/api/v1").maxAge(seconds).build().toString(); }
    private ResponseEntity<?> login(FamilyService.Login login) { return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(login.token(),login.expiresAt()-Instant.now().getEpochSecond())).body(Map.of("account",login.account(),"expiresAt",Instant.ofEpochSecond(login.expiresAt()).toString())); }
    @PostMapping("/accounts/register") public ResponseEntity<?> register(@Valid @RequestBody Credentials input,HttpServletRequest request) { return login(service.register(input.username(),input.password(),request.getRemoteAddr())); }
    @PostMapping("/accounts/login") public ResponseEntity<?> login(@Valid @RequestBody Credentials input,HttpServletRequest request) { return login(service.login(input.username(),input.password(),request.getRemoteAddr())); }
    @GetMapping("/accounts/me") public Object me(HttpServletRequest request) { return Map.of("account",account(request)); }
    @PostMapping("/accounts/logout") public ResponseEntity<?> logout(HttpServletRequest request) { service.logout(token(request));return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie("",0)).body(Map.of("loggedOut",true)); }
    @GetMapping("/families") public Object families(HttpServletRequest request) { return service.families(account(request)); }
    @PostMapping("/families") public Object create(@Valid @RequestBody FamilyInput input,HttpServletRequest request) { return service.createFamily(account(request),input.name()); }
    @GetMapping("/families/{familyId}/members") public Object members(@PathVariable String familyId,HttpServletRequest request) { return service.members(account(request),familyId); }
    @PostMapping("/families/{familyId}/invitations") public Object invite(@PathVariable String familyId,@Valid @RequestBody MemberInput input,HttpServletRequest request) { return service.invite(account(request),familyId,input.role(),input.profileAccess()); }
    @PostMapping("/families/invitations/accept") public Object accept(@Valid @RequestBody AcceptInput input,HttpServletRequest request) { return service.accept(account(request),input.code()); }
    @RequestMapping(value="/families/{familyId}/members/{accountId}",method={RequestMethod.PATCH,RequestMethod.PUT}) public Object update(@PathVariable String familyId,@PathVariable String accountId,@Valid @RequestBody MemberInput input,HttpServletRequest request) { service.changeMember(account(request),familyId,accountId,input.role(),input.profileAccess());return Map.of("updated",true); }
    @DeleteMapping("/families/{familyId}/members/{accountId}") public Object remove(@PathVariable String familyId,@PathVariable String accountId,HttpServletRequest request) { service.remove(account(request),familyId,accountId);return Map.of("removed",true); }
}
