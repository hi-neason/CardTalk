package com.cardtalk.operationsmetrics;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Fixed-cardinality, process-local numeric metrics. No request/response payload or identity storage. */
@Component
public class MetricsRegistry {
    public enum Api { health,trial_session,expansion,report_narrative,speech,speech_cancel,account,family,sync,asset,billing,support,operations,content,other_api }
    public enum Provider { expansion_llm,report_llm,speech_tts }
    private static final List<String> BUCKETS=List.of("lt_100ms","100ms_to_500ms","500ms_to_2s","2s_to_10s","10s_to_30s","gte_30s");
    private static final long[] BOUNDS={100_000_000L,500_000_000L,2_000_000_000L,10_000_000_000L,30_000_000_000L};
    private static class Counts {long started,completed,inFlight,errors,reported,prompt,completion,total;final long[] buckets=new long[6],statuses=new long[6];}
    public record Usage(String status,String source,long reportedCalls,Long promptTokens,Long completionTokens,Long totalTokens) {}
    public record CountsView(String category,long started,long completed,long inFlight,long errors,Map<String,Long> statusClasses,Map<String,Long> durationBuckets,Usage usage) {}
    public record Cost(String status,String reason,Double amount,String currency) {}
    public record Snapshot(String storage,String startedAt,long uptimeSeconds,List<CountsView> api,List<CountsView> providers,Cost cost) {}
    private final boolean enabled;
    private final long startNanos=System.nanoTime();
    private final String startedAt=Instant.now().toString();
    private final EnumMap<Api,Counts> api=new EnumMap<>(Api.class);
    private final EnumMap<Provider,Counts> providers=new EnumMap<>(Provider.class);
    public MetricsRegistry(@Value("${cardtalk.metrics-enabled:false}") boolean enabled){this.enabled=enabled;for(var kind:Api.values())api.put(kind,new Counts());for(var kind:Provider.values())providers.put(kind,new Counts());}
    public boolean enabled(){return enabled;}
    public static final class Call implements AutoCloseable {
        private final MetricsRegistry registry;private final Counts counts;private final long start=System.nanoTime();private boolean success,closed;private int status;
        private Call(MetricsRegistry registry,Counts counts){this.registry=registry;this.counts=counts;}
        public void success(){success=true;}
        public void httpStatus(int status){this.status=status;success=status<400;}
        public void usage(JsonNode value){if(registry!=null)registry.usage(counts,value);}
        public void close(){if(!closed){closed=true;if(registry!=null)registry.finish(counts,System.nanoTime()-start,success,status);}}
    }
    private synchronized Call begin(Counts counts){if(!enabled)return new Call(null,null);counts.started++;counts.inFlight++;return new Call(this,counts);}
    public Call api(Api kind){return begin(api.get(kind));}
    public static Call provider(MetricsRegistry registry,Provider kind){return registry==null?new Call(null,null):registry.begin(registry.providers.get(kind));}
    private synchronized void finish(Counts counts,long elapsed,boolean success,int status){counts.inFlight--;counts.completed++;if(!success)counts.errors++;int bucket=0;while(bucket<BOUNDS.length&&elapsed>=BOUNDS[bucket])bucket++;counts.buckets[bucket]++;if(status>0)counts.statuses[Math.min(5,Math.max(0,status/100))]++;}
    private synchronized void usage(Counts counts,JsonNode value){
        if(value==null||!value.isObject())return;String[] keys={"prompt_tokens","completion_tokens","total_tokens"};long[] values=new long[3];
        for(int i=0;i<keys.length;i++){JsonNode n=value.get(keys[i]);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0||n.longValue()>1_000_000_000L)return;values[i]=n.longValue();}
        if(values[0]+values[1]!=values[2])return;
        counts.reported++;counts.prompt+=values[0];counts.completion+=values[1];counts.total+=values[2];
    }
    private CountsView view(String name,Counts c,boolean provider){
        var buckets=new LinkedHashMap<String,Long>();for(int i=0;i<BUCKETS.size();i++)buckets.put(BUCKETS.get(i),c.buckets[i]);
        var statuses=new LinkedHashMap<String,Long>();if(!provider)for(int i=1;i<=5;i++)statuses.put(i+"xx",c.statuses[i]);
        Usage usage=null;if(provider)usage=new Usage(c.reported==0?"unknown":c.reported<c.completed?"partial":"provider_reported",c.reported==0?"unavailable":"provider_response_usage",c.reported,c.reported==0?null:c.prompt,c.reported==0?null:c.completion,c.reported==0?null:c.total);
        return new CountsView(name,c.started,c.completed,c.inFlight,c.errors,statuses,buckets,usage);
    }
    public synchronized Snapshot snapshot(){return new Snapshot("process_memory",startedAt,Math.max(0,(System.nanoTime()-startNanos)/1_000_000_000L),api.entrySet().stream().map(e->view(e.getKey().name(),e.getValue(),false)).toList(),providers.entrySet().stream().map(e->view(e.getKey().name(),e.getValue(),true)).toList(),new Cost("unknown","provider_billing_not_measured",null,null));}
}
