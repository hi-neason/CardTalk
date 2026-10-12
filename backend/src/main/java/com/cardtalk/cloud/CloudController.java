package com.cardtalk.cloud;

import com.cardtalk.family.FamilyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/families/{familyId}/sync")
public class CloudController {
    private final FamilyService families;
    private final CloudStore cloud;
    public CloudController(FamilyService families,CloudStore cloud) { this.families=families;this.cloud=cloud; }
    private FamilyService.Identity account(HttpServletRequest request) {
        String token=null;
        if(request.getCookies()!=null) for(var c:request.getCookies()) if(c.getName().equals("cardtalk_family")) return families.authenticate(c.getValue());
        String header=request.getHeader("Authorization");if(header!=null&&header.startsWith("Bearer ")) token=header.substring(7);
        return families.authenticate(token);
    }
    @GetMapping("/status") public Object status(@PathVariable String familyId,HttpServletRequest request) {
        var actor=account(request);if(families.families(actor).stream().noneMatch(family->family.id().equals(familyId))) throw new com.cardtalk.api.ApiException(403,"FAMILY_ACCESS_DENIED","没有当前家庭的访问权限");
        return Map.of("configured",cloud.configured(),"enabled",cloud.enabled(),"workspace","cloud","familyId",familyId,"billingEnforced",cloud.billingEnforced(),"assetReferencesSupported",cloud.assetReferencesSupported());
    }
    @PostMapping("/push") public Object push(@PathVariable String familyId,@Valid @RequestBody CloudDtos.Push input,HttpServletRequest request) { return cloud.push(account(request),familyId,input); }
    @GetMapping("/pull") public Object pull(@PathVariable String familyId,@RequestParam(defaultValue="0") String cursor,@RequestParam(defaultValue="50") int limit,HttpServletRequest request) { return cloud.pull(account(request),familyId,cursor,limit); }
    @ExceptionHandler(CloudConflict.class) public ResponseEntity<?> conflict(CloudConflict error) {
        var body=new LinkedHashMap<String,Object>();body.put("code","SYNC_CONFLICT");body.put("message",error.getMessage());body.put("operationId",error.operation.operationId());body.put("entityType",error.operation.entityType());body.put("entityId",error.operation.entityId());body.put("currentRevision",error.remote==null?0:error.remote.revision());body.put("remote",error.remote);return ResponseEntity.status(409).body(body);
    }
}
