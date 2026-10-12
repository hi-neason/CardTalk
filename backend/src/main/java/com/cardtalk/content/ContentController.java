package com.cardtalk.content;
import com.cardtalk.family.FamilyService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class ContentController {
    private final ContentService content;private final FamilyService families;
    public ContentController(ContentService content,FamilyService families){this.content=content;this.families=families;}
    private String actor(HttpServletRequest r){String token=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cardtalk_family")){token=c.getValue();break;}if(token==null){String h=r.getHeader("Authorization");if(h!=null&&h.startsWith("Bearer "))token=h.substring(7);}return families.authenticate(token).id();}

    @GetMapping("/resources/current") public Object current(){return ResponseEntity.ok().header("Cache-Control","no-store").body(content.current());}
    @PostMapping("/operations/content/versions") public Object publish(@RequestBody JsonNode p,HttpServletRequest r){return content.publish(actor(r),p);}
    @GetMapping("/operations/content/versions") public Object versions(HttpServletRequest r){return content.versions(actor(r));}
    @PostMapping("/operations/content/release") public Object release(@RequestBody JsonNode p,HttpServletRequest r){String actor=actor(r);if(!p.isObject()||p.size()!=2||!p.path("versionId").isTextual()||!p.path("expectedRevision").isIntegralNumber()||!p.path("expectedRevision").canConvertToLong())throw new com.cardtalk.api.ApiException(400,"INVALID_CONTENT_RELEASE","必须提供版本和预期发布修订号");return content.release(actor,p.path("versionId").asText(),p.path("expectedRevision").asLong());}
    @GetMapping("/operations/content/audit") public Object audit(HttpServletRequest r){return content.audit(actor(r));}
}
