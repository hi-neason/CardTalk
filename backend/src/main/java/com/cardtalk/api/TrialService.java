package com.cardtalk.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.cardtalk.api.Contracts.*;

/** JSON development or PostgreSQL production quota. No expression content is persisted. */
@Service
public class TrialService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final ObjectMapper mapper;
    private final Path file;
    private final int limit;
    private final TrialRepository postgres;
    private final Clock clock;
    private final ModelGateway model;
    private final boolean profileManagement,scenarioManagement,customCards,analytics;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final ConcurrentHashMap<String,Object> locks=new ConcurrentHashMap<>();
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>();
    private static final int CACHE_LIMIT=256;
    private static final Duration CACHE_TTL=Duration.ofMinutes(5);
    public record Entry(String clientId,String tokenHash,String day,int count,Map<String,String> completed) {}
    public record Snapshot(List<Entry> sessions) {}
    private record Cached(Instant expiresAt,String fingerprint,ExpansionResponse response) {}
    public TrialService(ObjectMapper mapper,ModelGateway model,String path,int limit,
                        boolean profileManagement,boolean scenarioManagement,boolean customCards) throws Exception {
        this(mapper,model,path,limit,profileManagement,scenarioManagement,customCards,false);
    }
    public TrialService(ObjectMapper mapper,ModelGateway model,String path,int limit,boolean profileManagement,boolean scenarioManagement,boolean customCards,boolean analytics) throws Exception {
        this(mapper,model,path,limit,profileManagement,scenarioManagement,customCards,analytics,"json","","","",Clock.system(ZONE));
    }
    @Autowired
    public TrialService(ObjectMapper mapper,ModelGateway model,@Value("${cardtalk.data-file}") String path,
                        @Value("${cardtalk.daily-limit}") int limit,
                        @Value("${cardtalk.profile-management-enabled:false}") boolean profileManagement,
                        @Value("${cardtalk.scenario-management-enabled:false}") boolean scenarioManagement,
                        @Value("${cardtalk.custom-cards-enabled:true}") boolean customCards,
                        @Value("${cardtalk.analytics-enabled:false}") boolean analytics,
                        @Value("${cardtalk.trial-repository-mode:json}") String mode,
                        @Value("${cardtalk.family-jdbc-url:}") String url,
                        @Value("${cardtalk.family-jdbc-user:}") String user,
                        @Value("${cardtalk.family-jdbc-password:}") String password) throws Exception {
        this(mapper,model,path,limit,profileManagement,scenarioManagement,customCards,analytics,mode,url,user,password,Clock.system(ZONE));
    }
    TrialService(ObjectMapper mapper,ModelGateway model,String path,int limit,boolean profileManagement,boolean scenarioManagement,boolean customCards,boolean analytics,String mode,String url,String user,String password,Clock clock) throws Exception {
        this.profileManagement=profileManagement; this.scenarioManagement=scenarioManagement; this.customCards=customCards; this.analytics=analytics;
        this.mapper=mapper; this.model=model; this.file=Path.of(path).toAbsolutePath(); this.limit=limit; this.clock=clock;
        if(limit<1||!Set.of("json","postgres").contains(mode)) throw new IllegalArgumentException("Invalid trial storage configuration");
        postgres=mode.equals("postgres")?new TrialRepository(url,user,password,limit,clock):null;
        if(postgres==null&&Files.exists(file)) {
            for(var entry:mapper.readValue(file.toFile(),Snapshot.class).sessions()) entries.put(entry.clientId(),entry);
        }
    }
    public SessionResponse create(String clientId) {
        if(postgres!=null) {
            String token=UUID.randomUUID()+"-"+UUID.randomUUID();
            return persistentSession(token,postgres.create(clientId,hash(token)));
        }
        synchronized(lock(clientId)) {
            Entry old=get(clientId); String token=UUID.randomUUID()+"-"+UUID.randomUUID();
            Entry next=old==null?new Entry(clientId,hash(token),today(),0,Map.of()):
                new Entry(clientId,hash(token),old.day(),old.count(),old.completed());
            save(next);
            return session(token,next);
        }
    }
    public SessionResponse session(String token) {
        if(postgres!=null) return persistentSession(token,postgres.session(tokenHash(token)));
        Entry entry=authenticate(token);
        synchronized(lock(entry.clientId())) { return session(token,authenticate(token)); }
    }
    /** Trusted service-layer identity for support ownership; never expose as a public API response. */
    public String supportIdentity(String token) {
        return postgres!=null?postgres.session(tokenHash(token)).clientId():authenticate(token).clientId();
    }
    public ExpansionResponse expand(String token,ExpansionRequest input) {
        return expandAuthorized(token,input,profileManagement,scenarioManagement,customCards,"");
    }
    /** Only trusted family controllers may supply these server-verified permissions. */
    public ExpansionResponse expandAuthorized(String token,ExpansionRequest input,boolean profileAllowed,boolean scenarioAllowed,boolean cardsAllowed,String scope) {
        if(postgres!=null) return expandPostgres(token,input,profileAllowed,scenarioAllowed,cardsAllowed,scope);
        Entry authenticated=authenticate(token);
        synchronized(lock(authenticated.clientId())) {
            Entry entry=authenticate(token);
            if(input.context().profile()!=null && !profileAllowed)
                throw new ApiException(403,"FEATURE_DISABLED","使用人资料管理尚未开启");
            if(input.context().scene()!=null && input.context().scene().id().startsWith("custom-scene-") && !scenarioAllowed)
                throw new ApiException(403,"FEATURE_DISABLED","自定义场景管理尚未开启");
            if(!cardsAllowed && input.cards().stream().anyMatch(card->card.id().startsWith("custom-")))
                throw new ApiException(403,"FEATURE_DISABLED","自定义卡片尚未开启");
            String fingerprint;
            try { fingerprint=hash(mapper.writeValueAsString(input)); } catch(Exception ex) { throw new ApiException(400,"INVALID_REQUEST","请求无法读取"); }
            String requestKey=scope.isEmpty()?input.requestId():scope+":"+input.requestId();
            String previous=entry.completed().get(requestKey);
            if(previous!=null) {
                if(!previous.equals(fingerprint)) throw new ApiException(409,"REQUEST_ID_CONFLICT","请求编号已用于另一份表达");
                Cached cached=cached(entry.clientId()+":"+requestKey);
                if(cached!=null) {
                    var value=cached.response();
                    return new ExpansionResponse(value.requestId(),value.inputVersion(),value.candidates(),value.needsClarification(),value.clarification(),value.promptVersion(),remaining(entry));
                }
                throw new ApiException(409,"REQUEST_ALREADY_COMPLETED","此请求已完成，请使用本机保存的结果或创建新请求");
            }
            if(remaining(entry)<=0) throw new ApiException(429,"QUOTA_EXHAUSTED","今日体验次数已用完，明天可继续体验");
            var result=model.expand(input);
            var completed=new LinkedHashMap<>(entry.completed()); completed.put(requestKey,fingerprint);
            // Retain deduplication metadata for the life of this local installation, including across midnight.
            int count=(entry.day().equals(today())?entry.count():0)+1;
            Entry next=new Entry(entry.clientId(),entry.tokenHash(),today(),count,Map.copyOf(completed));
            save(next);
            var response=new ExpansionResponse(input.requestId(),input.inputVersion(),result.candidates(),result.needsClarification(),result.clarification(),ModelGateway.PROMPT_VERSION,limit-count);
            putCache(entry.clientId()+":"+requestKey,new Cached(Instant.now().plus(CACHE_TTL),fingerprint,response));
            return response;
        }
    }
    private ExpansionResponse expandPostgres(String token,ExpansionRequest input,boolean profileAllowed,boolean scenarioAllowed,boolean cardsAllowed,String scope) {
        String tokenHash=tokenHash(token);postgres.session(tokenHash);
        if(input.context().profile()!=null&&!profileAllowed) throw new ApiException(403,"FEATURE_DISABLED","使用人资料管理尚未开启");
        if(input.context().scene()!=null&&input.context().scene().id().startsWith("custom-scene-")&&!scenarioAllowed) throw new ApiException(403,"FEATURE_DISABLED","自定义场景管理尚未开启");
        if(!cardsAllowed&&input.cards().stream().anyMatch(card->card.id().startsWith("custom-"))) throw new ApiException(403,"FEATURE_DISABLED","自定义卡片尚未开启");
        String fingerprint;
        try { fingerprint=hash(mapper.writeValueAsString(input)); } catch(Exception ex) { throw new ApiException(400,"INVALID_REQUEST","请求无法读取",ex); }
        String requestKey=scope.isEmpty()?input.requestId():scope+":"+input.requestId();
        var reservation=postgres.reserve(tokenHash,hash(requestKey),fingerprint);
        String cacheKey=reservation.clientId()+":"+requestKey;
        if(reservation.completed()) {
            Cached cached=cached(cacheKey);
            if(cached!=null) { var value=cached.response();return new ExpansionResponse(value.requestId(),value.inputVersion(),value.candidates(),value.needsClarification(),value.clarification(),value.promptVersion(),reservation.remaining()); }
            throw new ApiException(409,"REQUEST_ALREADY_COMPLETED","此请求已完成，请使用本机保存的结果或创建新请求");
        }
        ModelGateway.Result result;
        try { result=model.expand(input); }
        catch(RuntimeException ex) {try {postgres.release(reservation);}catch(RuntimeException releaseFailure){ex.addSuppressed(releaseFailure);}throw ex;}
        // Do not release after provider success: a commit failure may have an unknown outcome.
        int remaining=postgres.complete(reservation);
        var response=new ExpansionResponse(input.requestId(),input.inputVersion(),result.candidates(),result.needsClarification(),result.clarification(),ModelGateway.PROMPT_VERSION,remaining);
        putCache(cacheKey,new Cached(Instant.now().plus(CACHE_TTL),fingerprint,response));return response;
    }
    private static String tokenHash(String token) {if(token==null||token.length()>200)throw unauthorized();return hash(token);}
    private SessionResponse persistentSession(String token,TrialRepository.Identity identity) {
        return new SessionResponse(token,identity.remaining(),limit,LocalDate.now(clock).plusDays(1).atStartOfDay(ZONE).toInstant().toString(),
            Map.of("profileManagement",profileManagement,"scenarioManagement",scenarioManagement,"customCards",customCards,"analytics",analytics));
    }
    private Object lock(String id) { return locks.computeIfAbsent(id,k->new Object()); }
    private synchronized Entry get(String id) { return entries.get(id); }
    private synchronized Entry authenticate(String token) {
        if(token==null || token.length()>200) throw unauthorized();
        String hashed=hash(token);
        return entries.values().stream().filter(e->MessageDigest.isEqual(e.tokenHash().getBytes(StandardCharsets.US_ASCII),hashed.getBytes(StandardCharsets.US_ASCII)))
            .findFirst().orElseThrow(TrialService::unauthorized);
    }
    private synchronized void save(Entry entry) {
        var next=new LinkedHashMap<>(entries); next.put(entry.clientId(),entry);
        Path tmp=null;
        try {
            Files.createDirectories(file.getParent()); tmp=Files.createTempFile(file.getParent(),"trial-",".tmp");
            mapper.writeValue(tmp.toFile(),new Snapshot(List.copyOf(next.values())));
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            entries.clear(); entries.putAll(next);
        } catch(Exception ex) { throw new ApiException(503,"STORAGE_UNAVAILABLE","体验额度暂时无法保存，请稍后重试");
        } finally { if(tmp!=null) try { Files.deleteIfExists(tmp); } catch(Exception ignored) {} }
    }
    private synchronized Cached cached(String key) { evict(); return cache.get(key); }
    private synchronized void putCache(String key,Cached value) {
        evict(); cache.put(key,value);
        while(cache.size()>CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
    }
    private void evict() { cache.values().removeIf(c->!c.expiresAt().isAfter(Instant.now())); }
    private int remaining(Entry e) { return Math.max(0,limit-(e.day().equals(today())?e.count():0)); }
    private SessionResponse session(String token,Entry e) {
        return new SessionResponse(token,remaining(e),limit,LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toInstant().toString(),
            Map.of("profileManagement",profileManagement,"scenarioManagement",scenarioManagement,"customCards",customCards,"analytics",analytics));
    }
    private static String today() { return LocalDate.now(ZONE).toString(); }
    private static ApiException unauthorized() { return new ApiException(401,"UNAUTHORIZED","体验身份已失效，请重新开始体验"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
