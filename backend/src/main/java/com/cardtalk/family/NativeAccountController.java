package com.cardtalk.family;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

/** Explicit password-authenticated native bearer login; never returns a token from an existing cookie. */
@RestController
@RequestMapping("/api/v1/accounts/native")
public class NativeAccountController {
    private final FamilyService families;
    public NativeAccountController(FamilyService families){this.families=families;}
    private Object response(FamilyService.Login login){return Map.of("account",login.account(),"token",login.token(),"expiresAt",Instant.ofEpochSecond(login.expiresAt()).toString());}
    @PostMapping("/login")public Object login(@Valid @RequestBody FamilyController.Credentials input,HttpServletRequest request){return response(families.login(input.username(),input.password(),request.getRemoteAddr()));}
    @PostMapping("/register")public Object register(@Valid @RequestBody FamilyController.Credentials input,HttpServletRequest request){return response(families.register(input.username(),input.password(),request.getRemoteAddr()));}
}
