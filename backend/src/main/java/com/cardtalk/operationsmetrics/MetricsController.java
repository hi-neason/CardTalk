package com.cardtalk.operationsmetrics;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.FamilyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/operations/metrics")
public class MetricsController {
    private final MetricsRegistry metrics;private final FamilyService families;private final Set<String> operators=new HashSet<>();
    public MetricsController(MetricsRegistry metrics,FamilyService families,@Value("${cardtalk.metrics-operator-account-ids:}")String operatorIds){this.metrics=metrics;this.families=families;for(String id:operatorIds.split(","))if(!id.isBlank())operators.add(id.trim());}
    @GetMapping public ResponseEntity<MetricsRegistry.Snapshot> get(HttpServletRequest request){
        if(!metrics.enabled())throw new ApiException(503,"METRICS_DISABLED","运行指标尚未启用");
        String token=null;if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("cardtalk_family"))token=c.getValue();
        if(token==null){String header=request.getHeader("Authorization");token=header!=null&&header.startsWith("Bearer ")?header.substring(7):null;}
        var actor=families.authenticate(token);if(!operators.contains(actor.id()))throw new ApiException(403,"METRICS_OPERATOR_REQUIRED","没有运行指标查看权限");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(metrics.snapshot());
    }
}
