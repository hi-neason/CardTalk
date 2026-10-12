package com.cardtalk.family;

import com.cardtalk.api.*;
import com.cardtalk.billing.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import static com.cardtalk.api.Contracts.*;

/** Family authorization is distinct from the anonymous expansion allowance. No history is persisted. */
@RestController
@RequestMapping("/api/v1/families/{familyId}")
public class FamilyExpansionController {
    private final FamilyService families;
    private final BillingService billing;
    private final TrialService trials;
    private final boolean enforced;
    public FamilyExpansionController(FamilyService families,BillingService billing,TrialService trials,
            @Value("${cardtalk.billing-enforced:false}")boolean enforced){this.families=families;this.billing=billing;this.trials=trials;this.enforced=enforced;}
    private FamilyService.Identity account(HttpServletRequest request){
        if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("cardtalk_family"))return families.authenticate(c.getValue());
        String h=request.getHeader("Authorization");return families.authenticate(h!=null&&h.startsWith("Bearer ")?h.substring(7):null);
    }
    @PostMapping("/expansions")
    public ExpansionResponse expand(@PathVariable String familyId,@RequestHeader("X-CardTalk-Trial-Token")String trialToken,
            @Valid @RequestBody ExpansionRequest input,HttpServletRequest request){
        var actor=account(request);var family=families.families(actor).stream().filter(f->f.id().equals(familyId)).findFirst()
                .orElseThrow(()->new ApiException(403,"FAMILY_ACCESS_DENIED","没有这个家庭的访问权限"));
        if(family.role().equals("helper"))throw new ApiException(403,"FAMILY_READ_ONLY","协助者只可查看已授权资料");
        if(enforced){
            if(input.context().profile()!=null)billing.requireAdvancedWrite(actor,familyId,ProductsConfig.Feature.profiles);
            if(input.context().scene()!=null&&input.context().scene().id().startsWith("custom-scene-"))billing.requireAdvancedWrite(actor,familyId,ProductsConfig.Feature.scenarios);
        }
        return trials.expandAuthorized(trialToken,input,true,true,true,"family-"+familyId+"-"+actor.id());
    }
}
