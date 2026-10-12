package com.cardtalk.operationsmetrics;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import java.io.IOException;
import static com.cardtalk.operationsmetrics.MetricsRegistry.Api;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MetricsFilter extends OncePerRequestFilter {
    private final MetricsRegistry metrics;
    public MetricsFilter(MetricsRegistry metrics){this.metrics=metrics;}
    protected boolean shouldNotFilter(HttpServletRequest request){return !metrics.enabled()||!request.getRequestURI().startsWith(request.getContextPath()+"/api/v1/");}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        var call=metrics.api(category(UrlPathHelper.defaultInstance.getPathWithinApplication(request).replaceAll(";[^/]*","")));
        try {chain.doFilter(request,response);call.httpStatus(response.getStatus());}
        catch(IOException|ServletException|RuntimeException ex){call.httpStatus(500);throw ex;}
        finally {call.close();}
    }
    static Api category(String path){
        if(path.equals("/api/v1/health"))return Api.health;
        if(path.equals("/api/v1/trial-session"))return Api.trial_session;
        if(path.equals("/api/v1/expansions")||path.matches("/api/v1/families/[^/]+/expansions"))return Api.expansion;
        if(path.equals("/api/v1/reports/narrative")||path.matches("/api/v1/families/[^/]+/reports/narrative"))return Api.report_narrative;
        if(path.equals("/api/v1/speech"))return Api.speech;
        if(path.startsWith("/api/v1/speech/"))return Api.speech_cancel;
        if(path.startsWith("/api/v1/accounts/"))return Api.account;
        if(path.startsWith("/api/v1/support/"))return Api.support;
        if(path.startsWith("/api/v1/operations/"))return Api.operations;
        if(path.startsWith("/api/v1/resources/"))return Api.content;
        if(path.startsWith("/api/v1/billing/")||path.matches("/api/v1/families/[^/]+/(billing|entitlements)(/.*)?"))return Api.billing;
        if(path.matches("/api/v1/families/[^/]+/sync(/.*)?"))return Api.sync;
        if(path.matches("/api/v1/families/[^/]+/assets(/.*)?"))return Api.asset;
        if(path.equals("/api/v1/families")||path.startsWith("/api/v1/families/"))return Api.family;
        return Api.other_api;
    }
}
