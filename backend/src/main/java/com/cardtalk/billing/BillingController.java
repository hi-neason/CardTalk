package com.cardtalk.billing;

import com.cardtalk.api.ApiException;
import com.cardtalk.family.FamilyService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class BillingController {
    private final FamilyService families;private final BillingService billing;
    public BillingController(FamilyService families,BillingService billing){this.families=families;this.billing=billing;}
    public record OrderInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String productId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String requestId) {}
    public record ManualInput(@NotNull ProductsConfig.Feature feature,@NotNull Instant startsAt,@NotNull Instant expiresAt) {}
    public record RefundInput(@NotBlank @Size(max=80)String reason) {}
    private FamilyService.Identity account(HttpServletRequest r){if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("cardtalk_family"))return families.authenticate(c.getValue());String h=r.getHeader("Authorization");return families.authenticate(h!=null&&h.startsWith("Bearer ")?h.substring(7):null);}
    @GetMapping("/families/{familyId}/entitlements")public Object entitlements(@PathVariable String familyId,HttpServletRequest r){return billing.entitlements(account(r),familyId);}
    @GetMapping("/families/{familyId}/billing/products")public Object products(@PathVariable String familyId,HttpServletRequest r){return billing.catalog(account(r),familyId);}
    @GetMapping("/families/{familyId}/billing/orders")public Object orders(@PathVariable String familyId,HttpServletRequest r){return billing.orders(account(r),familyId);}
    @GetMapping("/families/{familyId}/billing/orders/{orderId}")public Object order(@PathVariable String familyId,@PathVariable String orderId,HttpServletRequest r){return billing.order(account(r),familyId,orderId);}
    @PostMapping("/families/{familyId}/billing/orders")public Object create(@PathVariable String familyId,@Valid @RequestBody OrderInput input,HttpServletRequest r){return billing.createOrder(account(r),familyId,input.productId(),input.requestId(),r.getRemoteAddr());}
    @PostMapping("/families/{familyId}/billing/orders/{orderId}/query")public Object query(@PathVariable String familyId,@PathVariable String orderId,HttpServletRequest r){return billing.query(account(r),familyId,orderId);}
    @PostMapping("/families/{familyId}/billing/orders/{orderId}/close")public Object close(@PathVariable String familyId,@PathVariable String orderId,HttpServletRequest r){return billing.close(account(r),familyId,orderId);}
    @PostMapping("/families/{familyId}/billing/orders/{orderId}/refund")public Object refund(@PathVariable String familyId,@PathVariable String orderId,@Valid @RequestBody RefundInput input,HttpServletRequest r){return billing.refund(account(r),familyId,orderId,input.reason());}
    @PostMapping("/families/{familyId}/billing/manual-grants")public Object manual(@PathVariable String familyId,@Valid @RequestBody ManualInput input,HttpServletRequest r){return billing.grantManual(account(r),familyId,input.feature(),input.startsAt(),input.expiresAt());}
    /** Provider-signed public callback: no browser identity can mark an order paid. */
    @PostMapping("/billing/notifications/{provider}")public Object notification(@PathVariable String provider,HttpServletRequest request,HttpServletResponse response)throws java.io.IOException{
        response.setHeader("Cache-Control","no-store");if(request.getContentLengthLong()>65536)throw new ApiException(413,"BODY_TOO_LARGE","回调请求过大");byte[] body=request.getInputStream().readNBytes(65537);if(body.length>65536)throw new ApiException(413,"BODY_TOO_LARGE","回调请求过大");Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);for(String name:Collections.list(request.getHeaderNames()))headers.put(name,request.getHeader(name));billing.notification(provider,headers,body);return Map.of("code","SUCCESS","message","成功");
    }
}
