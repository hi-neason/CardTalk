package com.cardtalk.assets;

import com.cardtalk.family.FamilyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/families/{familyId}/assets")
public class AssetController {
    private final FamilyService families;
    private final AssetService assets;
    public AssetController(FamilyService families,AssetService assets) { this.families=families;this.assets=assets; }
    private FamilyService.Identity account(HttpServletRequest request) {
        if(request.getCookies()!=null) for(var cookie:request.getCookies()) if(cookie.getName().equals("cardtalk_family")) return families.authenticate(cookie.getValue());
        String auth=request.getHeader("Authorization");return families.authenticate(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null);
    }
    @PostMapping public ResponseEntity<AssetCatalog.Metadata> upload(@PathVariable String familyId,@RequestParam String profileId,@RequestBody byte[] bytes,HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(assets.upload(account(request),familyId,profileId,request.getContentType(),bytes));
    }
    @GetMapping("/{assetId}") public ResponseEntity<byte[]> download(@PathVariable String familyId,@PathVariable String assetId,HttpServletRequest request) {
        var content=assets.download(account(request),familyId,assetId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(content.metadata().mediaType())).contentLength(content.bytes().length).body(content.bytes());
    }
}
