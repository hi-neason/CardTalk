package com.cardtalk.reports;

import com.cardtalk.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.*;
import static com.cardtalk.reports.ReportContracts.*;

/** Text is cached only in memory; durable quota does not persist facts or report content. */
@Service
public class ReportService {
    private final TrialService trials;
    private final ReportGateway gateway;
    private final ObjectMapper mapper;
    private final ReportQuota quota;
    private final LinkedHashMap<String,Response> results=new LinkedHashMap<>();
    @Autowired
    public ReportService(TrialService trials,ReportGateway gateway,ObjectMapper mapper,ReportQuota quota) {
        this.trials=trials;this.gateway=gateway;this.mapper=mapper;this.quota=quota;
    }
    public ReportService(TrialService trials,ReportGateway gateway,ObjectMapper mapper,int dailyLimit) { this(trials,gateway,mapper,dailyLimit,Clock.system(ZoneId.of("Asia/Shanghai"))); }
    ReportService(TrialService trials,ReportGateway gateway,ObjectMapper mapper,int dailyLimit,Clock clock) { this(trials,gateway,mapper,new ReportQuota("memory","","","",dailyLimit,clock)); }
    public Response generate(String token,Request request) {
        if(!Boolean.TRUE.equals(trials.session(token).capabilities().get("analytics"))) throw new ApiException(403,"FEATURE_DISABLED","分析功能尚未开启");
        return generateIdentity(ReportQuota.hash(token),request);
    }
    Response generateIdentity(String identity,Request request) {
        if(request==null||request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_-]{1,100}")||request.facts()==null) throw ReportContracts.invalid();request.facts().validate();
        String digest;
        try { digest=ReportQuota.hash(mapper.writeValueAsString(request.facts())); } catch(java.io.IOException ex) { throw ReportContracts.invalid(); }
        var reservation=quota.reserve(identity,ReportQuota.hash(request.requestId()),digest);
        String key=reservation.day()+":"+reservation.identity()+":"+reservation.nonce();
        if(reservation.completed()) {
            synchronized(results) { Response cached=results.get(key);if(cached!=null) return cached; }
            throw new ApiException(409,"REPORT_ALREADY_COMPLETED","此说明已生成且临时缓存已过期，请使用新的请求编号");
        }
        String text;
        try { text=gateway.generate(request.facts()); }
        catch(RuntimeException ex) { try { quota.release(reservation); } catch(RuntimeException releaseFailure) { ex.addSuppressed(releaseFailure); }throw ex; }
        // Once the provider succeeds, an ambiguous storage failure must not release a possibly committed charge.
        int remaining=quota.complete(reservation);
        var response=new Response(request.requestId(),text,ReportGateway.PROMPT_VERSION,"deepseek",remaining);
        synchronized(results) { results.put(key,response);while(results.size()>256) results.remove(results.keySet().iterator().next()); }
        return response;
    }
}
