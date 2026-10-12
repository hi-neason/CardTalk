package com.cardtalk.reports;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.FamilyService;
import com.cardtalk.billing.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/families/{familyId}/reports")
public class FamilyReportController {
    private final FamilyService families;
    private final BillingService billing;
    private final ReportService reports;
    private final boolean enforced;
    public FamilyReportController(FamilyService families,BillingService billing,ReportService reports,@Value("${cardtalk.billing-enforced:false}") boolean enforced) {
        this.families=families;this.billing=billing;this.reports=reports;this.enforced=enforced;
    }
    private FamilyService.Identity account(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals("cardtalk_family")) return families.authenticate(cookie.getValue());
        String header=request.getHeader("Authorization");return families.authenticate(header!=null&&header.startsWith("Bearer ")?header.substring(7):null);
    }
    @PostMapping("/narrative") public ResponseEntity<ReportContracts.Response> generate(@PathVariable String familyId,@Valid @RequestBody ReportContracts.Request input,HttpServletRequest request) {
        var actor=account(request);
        if(enforced) billing.requireAdvancedWrite(actor,familyId,ProductsConfig.Feature.analytics);
        else if(families.families(actor).stream().noneMatch(f->f.id().equals(familyId)&&Set.of("owner","admin","member").contains(f.role()))) throw new ApiException(403,"FAMILY_ACCESS_DENIED","没有此家庭的报告生成权限");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.generateIdentity(ReportQuota.hash("family:"+familyId+":"+actor.id()),input));
    }
}
