package com.cardtalk.reports;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service) { this.service=service; }
    @PostMapping("/narrative") public ResponseEntity<ReportContracts.Response> generate(@RequestHeader(value="Authorization",required=false) String authorization,@Valid @RequestBody ReportContracts.Request request) {
        String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generate(token,request));
    }
}
