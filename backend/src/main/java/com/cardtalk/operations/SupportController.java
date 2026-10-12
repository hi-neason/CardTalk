package com.cardtalk.operations;

import com.cardtalk.api.*;
import com.cardtalk.family.FamilyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.cardtalk.operations.SupportContracts.*;

@RestController
@RequestMapping("/api/v1")
public class SupportController {
    private final SupportService service;
    private final TrialService trials;
    private final FamilyService families;
    public SupportController(SupportService service,TrialService trials,FamilyService families){this.service=service;this.trials=trials;this.families=families;}
    private static String bearer(HttpServletRequest r){String h=r.getHeader("Authorization");return h!=null&&h.startsWith("Bearer ")?h.substring(7):null;}
    private static String cookie(HttpServletRequest r){if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cardtalk_family"))return c.getValue();return null;}
    private Actor actor(HttpServletRequest request,boolean operations){
        service.requireEnabled();String token=cookie(request);
        String scope=request.getHeader("X-CardTalk-Support-Scope");
        if(scope!=null&&!java.util.Set.of("trial","account").contains(scope))throw new ApiException(400,"INVALID_SUPPORT_SCOPE","支持身份范围不正确");
        boolean accountScope=operations||"account".equals(scope)||scope==null&&token!=null;
        if(accountScope){var account=families.authenticate(token!=null?token:bearer(request));return new Actor(SupportService.hash("account:"+account.id()),account.id());}
        String trialToken=request.getHeader("X-CardTalk-Trial-Token");
        return new Actor(SupportService.hash("trial:"+trials.supportIdentity(trialToken!=null?trialToken:bearer(request))),null);
    }
    @GetMapping("/support/status") public Object status(HttpServletRequest request){var actor=actor(request,false);var result=new java.util.LinkedHashMap<String,Object>(service.status());result.put("actorScope",actor.accountId()==null?"trial":"account");result.put("canOperate",service.canOperate(actor));return result;}
    @PostMapping("/support/tickets") public Ticket create(@Valid @RequestBody Create input,HttpServletRequest request){
        var owner=actor(request,false);
        if(input.familyId()!=null){if(owner.accountId()==null)throw new ApiException(403,"FAMILY_ACCESS_DENIED","家庭申请需要登录对应家庭账户");var identity=families.authenticate(cookie(request)!=null?cookie(request):bearer(request));if(families.families(identity).stream().noneMatch(f->f.id().equals(input.familyId())))throw new ApiException(403,"FAMILY_ACCESS_DENIED","没有此家庭的访问权限");}
        return service.create(owner,input);
    }
    @GetMapping("/support/tickets") public Page mine(@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){return service.list(actor(request,false),false,cursor,limit);}
    @PostMapping("/support/tickets/{id}/withdraw") public Ticket withdraw(@PathVariable String id,HttpServletRequest request){return service.withdraw(actor(request,false),id);}
    @GetMapping("/operations/tickets") public Page all(@RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){return service.list(actor(request,true),true,cursor,limit);}
    @PostMapping("/operations/tickets/{id}/updates") public Ticket update(@PathVariable String id,@Valid @RequestBody Update input,HttpServletRequest request){return service.update(actor(request,true),id,input);}
    @GetMapping("/operations/tickets/{id}/audit") public Object audit(@PathVariable String id,HttpServletRequest request){return service.audits(actor(request,true),id);}
    @PostMapping("/operations/retention/purge") public Object purge(HttpServletRequest request){return Map.of("removed",service.purge(actor(request,true)));}
}
