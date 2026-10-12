package com.cardtalk.cloud;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Explicit entity shapes; synchronization is not an arbitrary JSON storage endpoint. */
final class CloudPayload {
    static ApiException invalid() { return new ApiException(400,"INVALID_SYNC_PAYLOAD","同步资料格式、范围或同意设置不正确"); }
    static void require(boolean yes) { if(!yes) throw invalid(); }
    static void shape(JsonNode n,String fields) { require(n!=null&&n.isObject());var allowed=Set.of(fields.split(","));n.fieldNames().forEachRemaining(key->{if(!allowed.contains(key)) throw invalid();}); }
    static void text(JsonNode n,int max,boolean empty) { require(n!=null&&n.isTextual()&&n.textValue().length()<=max&&(empty||!n.textValue().isBlank())); }
    static String id(JsonNode n) { text(n,100,false);require(n.textValue().matches("[A-Za-z0-9_-]{1,100}"));return n.textValue(); }
    static void number(JsonNode n,double min,double max) { require(n!=null&&n.isNumber()&&Double.isFinite(n.doubleValue())&&n.doubleValue()>=min&&n.doubleValue()<=max); }
    static void bool(JsonNode n) { require(n!=null&&n.isBoolean()); }
    static void one(JsonNode n,String values) { text(n,100,false);require(Set.of(values.split(",")).contains(n.textValue())); }
    static void date(JsonNode n) { text(n,40,false);try { Instant.parse(n.textValue()); } catch(Exception e) { throw invalid(); } }
    static void array(JsonNode n,int max) { require(n!=null&&n.isArray()&&n.size()<=max); }
    static void optText(JsonNode n,String key,int max) { if(n.has(key)) text(n.get(key),max,true); }
    static void preferences(JsonNode n) {
        shape(n,"displayMode,density,fontSize,rate,volume,deviceVoiceURI,remoteVoiceKey,touchGuardMs");one(n.get("displayMode"),"both,images,text");one(n.get("density"),"comfortable,compact");one(n.get("fontSize"),"normal,large");number(n.get("rate"),0.5,1.5);number(n.get("volume"),0,1);text(n.get("deviceVoiceURI"),200,true);number(n.get("touchGuardMs"),0,1500);require(n.get("touchGuardMs").isIntegralNumber());
        if(n.has("remoteVoiceKey")) { text(n.get("remoteVoiceKey"),100,true);require(n.get("remoteVoiceKey").asText().isEmpty()||n.get("remoteVoiceKey").asText().matches("[A-Za-z0-9_-]{1,100}")); }
    }
    static void consent(JsonNode n,CloudDtos.Consent consent) {
        if(n.isTextual()&&n.textValue().contains("data:image/")) throw new ApiException(400,"INLINE_IMAGE_NOT_ALLOWED","图片必须使用经授权的资源引用，不能同步内嵌图片");
        if(n.isObject()) n.fields().forEachRemaining(entry->{
            String key=entry.getKey();JsonNode v=entry.getValue();
            if(key.equals("assetRefs")) throw invalid();
            if(key.equals("assetRef")) {if(!Boolean.TRUE.equals(consent.photos()))throw new ApiException(400,"PHOTO_CONSENT_REQUIRED","请先明确同意同步图片引用");shape(v,"id");id(v.get("id"));}
            if(key.equals("image")&&!v.isNull()&&!(v.isTextual()&&v.textValue().isEmpty())) throw new ApiException(400,"INLINE_IMAGE_NOT_ALLOWED","当前同步不接受图片内容或引用");
            if(Set.of("observations","observer","observedAt").contains(key)&&!Boolean.TRUE.equals(consent.observations())&&!v.isNull()&&!(v.isTextual()&&v.textValue().isEmpty())) throw new ApiException(400,"OBSERVATION_CONSENT_REQUIRED","请先明确同意同步观察记录");
            consent(v,consent);
        });
        if(n.isArray()) n.forEach(v->consent(v,consent));
    }
    static void provenance(JsonNode n) {
        shape(n,"packageId,title,source,license,revision");text(n.get("packageId"),40,false);require(n.get("packageId").textValue().matches("[a-z0-9][a-z0-9-]{0,39}"));text(n.get("title"),100,false);text(n.get("source"),500,false);text(n.get("license"),500,false);if(n.has("revision")){require(n.get("revision").isIntegralNumber()&&n.get("revision").canConvertToLong());number(n.get("revision"),0,9007199254740991L);}
    }
    static void card(JsonNode n,boolean custom) {
        shape(n,custom?"id,label,category,icon,kind,description,image,assetRef,provenance,sceneIds,archived,updatedAt":"id,label,category,icon,kind,description,image,assetRef,provenance");id(n.get("id"));text(n.get("label"),50,false);text(n.get("category"),100,false);text(n.get("icon"),100,false);one(n.get("kind"),"word,phrase");optText(n,"description",500);if(n.has("provenance"))provenance(n.get("provenance"));
        if(custom) { array(n.get("sceneIds"),50);n.get("sceneIds").forEach(CloudPayload::id);bool(n.get("archived"));date(n.get("updatedAt")); }
    }
    static void profile(JsonNode n,boolean snapshot) {
        shape(n,snapshot?"name,image,assetRef,preferences,age,language,interests,personality,communicationStyle,currentState":"id,name,image,assetRef,preferences,preset,age,language,interests,personality,communicationStyle,observations,observer,observedAt,archived,updatedAt");
        text(n.get("name"),100,false);require(n.has("age"));if(!n.get("age").isNull()) {number(n.get("age"),0,120);require(n.get("age").isIntegralNumber());}
        text(n.get("language"),80,true);for(String key:List.of("interests","personality","communicationStyle")) text(n.get(key),500,true);
        if(n.has("preferences")) preferences(n.get("preferences"));
        if(snapshot) text(n.get("currentState"),500,true);
        else {id(n.get("id"));one(n.get("preset"),"children,adult");text(n.get("observations"),1000,true);text(n.get("observer"),100,true);require(n.has("observedAt"));if(!n.get("observedAt").isNull()) date(n.get("observedAt"));bool(n.get("archived"));date(n.get("updatedAt"));}
    }
    static void scene(JsonNode n,boolean snapshot) {
        shape(n,snapshot?"id,name,image,assetRef,provenance,description":"id,name,image,assetRef,provenance,description,activities,objects,recommendedCardIds,archived,updatedAt");id(n.get("id"));text(n.get("name"),100,false);text(n.get("description"),snapshot?2200:1000,false);if(n.has("provenance"))provenance(n.get("provenance"));
        if(!snapshot) {text(n.get("activities"),500,true);text(n.get("objects"),500,true);array(n.get("recommendedCardIds"),70);n.get("recommendedCardIds").forEach(CloudPayload::id);bool(n.get("archived"));date(n.get("updatedAt"));}
    }
    static String context(JsonNode n) {
        shape(n,"preset,sceneId,participants,communicatorName,source,profileId,profileSnapshot,sceneSnapshot,currentState");one(n.get("preset"),"children,adult");one(n.get("source"),"independent,assisted,unknown");text(n.get("communicatorName"),100,false);require(n.has("sceneId"));if(!n.get("sceneId").isNull()) id(n.get("sceneId"));array(n.get("participants"),20);n.get("participants").forEach(p->{shape(p,"id,name");id(p.get("id"));text(p.get("name"),100,false);});optText(n,"currentState",500);
        if(n.has("profileSnapshot")) profile(n.get("profileSnapshot"),true);if(n.has("sceneSnapshot")) {scene(n.get("sceneSnapshot"),true);require(n.get("sceneSnapshot").get("id").equals(n.get("sceneId")));}
        return n.has("profileId")?id(n.get("profileId")):null;
    }
    static void process(JsonNode n,boolean snapshot) {
        shape(n,snapshot?"startedAt,confirmedAt,events":"startedAt,events");date(n.get("startedAt"));if(snapshot) date(n.get("confirmedAt"));array(n.get("events"),200);
        n.get("events").forEach(e->{shape(e,"id,at,type,requestId,inputVersion,errorCode");id(e.get("id"));date(e.get("at"));one(e.get("type"),"clarification-requested,generation-started,generation-succeeded,generation-failed,generation-cancelled,candidates-rejected,candidate-edit-started");integer(e.get("inputVersion"));if(e.has("requestId")) id(e.get("requestId"));if(e.has("errorCode")) {text(e.get("errorCode"),80,false);require(e.get("errorCode").textValue().matches("[A-Za-z0-9_.:-]+"));}});
    }
    static void integer(JsonNode n) { require(n!=null&&n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=0&&n.longValue()<=9007199254740991L); }
    static void expansion(JsonNode n) {
        shape(n,"requestId,inputVersion,candidates,needsClarification,clarification,promptVersion,remaining");id(n.get("requestId"));integer(n.get("inputVersion"));integer(n.get("remaining"));text(n.get("promptVersion"),100,false);bool(n.get("needsClarification"));require(n.has("clarification"));if(!n.get("clarification").isNull()) text(n.get("clarification"),500,true);array(n.get("candidates"),3);n.get("candidates").forEach(c->{shape(c,"id,text,sourceCardIds");id(c.get("id"));text(c.get("text"),500,false);array(c.get("sourceCardIds"),20);c.get("sourceCardIds").forEach(CloudPayload::id);});
    }
    static void turn(JsonNode n,String scope) {
        shape(n,"id,createdAt,cardSnapshot,contextSnapshot,expansion,finalText,selectedCandidateId,source,wasEdited,kind,sourceSavedPhraseId,playbackEvents,feedback,processSnapshot");id(n.get("id"));date(n.get("createdAt"));array(n.get("cardSnapshot"),20);require(!n.get("cardSnapshot").isEmpty());n.get("cardSnapshot").forEach(c->card(c,false));require(Objects.equals(scope,context(n.get("contextSnapshot"))));text(n.get("finalText"),1100,false);one(n.get("source"),"independent,assisted,unknown");require(n.get("source").equals(n.get("contextSnapshot").get("source")));bool(n.get("wasEdited"));one(n.get("kind"),"raw,generated,saved");
        String kind=n.get("kind").textValue();if(kind.equals("generated")) {expansion(n.get("expansion"));id(n.get("selectedCandidateId"));require(!n.has("sourceSavedPhraseId"));} else {require(!n.has("expansion")&&!n.has("selectedCandidateId")&&!n.get("wasEdited").booleanValue());if(kind.equals("saved"))id(n.get("sourceSavedPhraseId"));else require(!n.has("sourceSavedPhraseId"));}
        array(n.get("playbackEvents"),100);n.get("playbackEvents").forEach(e->{shape(e,"id,status,at");id(e.get("id"));date(e.get("at"));one(e.get("status"),"started,completed,interrupted,failed,unavailable");});require(n.has("feedback"));if(!n.get("feedback").isNull()){var f=n.get("feedback");shape(f,"value,by,at,note");one(f.get("value"),"understood,clarify,not-understood,unrecorded");text(f.get("by"),100,false);date(f.get("at"));optText(f,"note",500);}if(n.has("processSnapshot"))process(n.get("processSnapshot"),true);
    }
    static String validate(String type,String entityId,JsonNode n,CloudDtos.Consent consent) {
        require(n!=null&&n.isObject()&&n.toString().getBytes(StandardCharsets.UTF_8).length<=512*1024);consent(n,consent);require(entityId.equals(id(n.get("id"))));
        switch(type) {
            case "profile": profile(n,false);return entityId;
            case "card":card(n,true);return null;
            case "scene":scene(n,false);return null;
            case "room": {
                shape(n,"id,context,startedAt,endedAt,status,turns,draft");String scope=context(n.get("context"));date(n.get("startedAt"));one(n.get("status"),"active,ended");require(n.has("endedAt"));if(n.get("status").textValue().equals("ended"))date(n.get("endedAt"));else require(n.get("endedAt").isNull());array(n.get("turns"),1000);n.get("turns").forEach(t->turn(t,scope));var d=n.get("draft");shape(d,"cards,version,expansion,confirmedText,selectedCandidateId,confirmedTurnId,savedPhraseId,process");array(d.get("cards"),20);d.get("cards").forEach(c->card(c,false));integer(d.get("version"));require(d.has("expansion"));if(!d.get("expansion").isNull())expansion(d.get("expansion"));for(String key:List.of("confirmedText","selectedCandidateId","confirmedTurnId")){require(d.has(key));if(!d.get(key).isNull())text(d.get(key),key.equals("confirmedText")?1100:100,false);}if(d.has("savedPhraseId"))id(d.get("savedPhraseId"));if(d.has("process"))process(d.get("process"),false);return scope;
            }
            case "report": {
                shape(n,"id,createdAt,filters,metrics,evidenceIds,sourceSignature,metricVersion,narrative");date(n.get("createdAt"));if(n.has("narrative")){var narrative=n.get("narrative");shape(narrative,"text,createdAt,provider,promptVersion");text(narrative.get("text"),1500,false);date(narrative.get("createdAt"));one(narrative.get("provider"),"deepseek");text(narrative.get("promptVersion"),100,false);}require(n.path("metricVersion").isInt()&&n.get("metricVersion").intValue()==1);var f=n.get("filters");shape(f,"profileId,sceneId,from,to,source,category,status");f.fields().forEachRemaining(e->text(e.getValue(),100,true));array(n.get("evidenceIds"),500000);n.get("evidenceIds").forEach(e->{shape(e,"roomId,turnId");id(e.get("roomId"));id(e.get("turnId"));});text(n.get("sourceSignature"),512*1024,true);metrics(n.get("metrics"));String scope=f.has("profileId")?id(f.get("profileId")):null;scope=Set.of("all","trial").contains(scope==null?"all":scope)?null:scope;signature(n.get("sourceSignature"),scope,consent);return scope;
            }
            default: throw invalid();
        }
    }
    static void signature(JsonNode value,String scope,CloudDtos.Consent consent) {
        JsonNode entries;try {entries=new com.fasterxml.jackson.databind.ObjectMapper().readTree(value.textValue());}catch(Exception e){throw invalid();}
        array(entries,500000);consent(entries,consent);
        for(JsonNode n:entries) {
            shape(n,"roomId,status,id,createdAt,profileId,sceneId,sceneName,source,kind,wasEdited,cards,feedback,playback,process");id(n.get("roomId"));id(n.get("id"));one(n.get("status"),"active,ended");date(n.get("createdAt"));if(scope!=null)require(n.has("profileId")&&scope.equals(id(n.get("profileId"))));else if(n.has("profileId"))id(n.get("profileId"));
            require(n.has("sceneId"));if(!n.get("sceneId").isNull())id(n.get("sceneId"));optText(n,"sceneName",100);one(n.get("source"),"independent,assisted,unknown");one(n.get("kind"),"raw,generated,saved");bool(n.get("wasEdited"));array(n.get("cards"),20);n.get("cards").forEach(c->{shape(c,"id,label,category");id(c.get("id"));text(c.get("label"),50,false);text(c.get("category"),100,false);});
            require(n.has("feedback"));if(!n.get("feedback").isNull()){shape(n.get("feedback"),"value,at");one(n.get("feedback").get("value"),"understood,clarify,not-understood,unrecorded");date(n.get("feedback").get("at"));}
            array(n.get("playback"),100);n.get("playback").forEach(e->{shape(e,"id,status,at");id(e.get("id"));one(e.get("status"),"started,completed,interrupted,failed,unavailable");date(e.get("at"));});if(n.has("process"))process(n.get("process"),true);
        }
    }
    static void metrics(JsonNode n) {
        shape(n,"activeDays,sampleWarning,inputCards,categoryCounts,playbackCounts,process,confirmedCount,roomCount,feedback,feedbackCoverage,understoodRate,kind,source,topCards,sceneCounts,dailyCounts");
        for(String key:List.of("activeDays","confirmedCount","roomCount"))integer(n.get(key));bool(n.get("sampleWarning"));for(String key:List.of("feedbackCoverage","understoodRate")){require(n.has(key));if(!n.get(key).isNull())number(n.get(key),0,1);}
        group(n.get("inputCards"),"total,average,max");group(n.get("playbackCounts"),"completed,failed,interrupted,unavailable,startedOnly,neverPlayed");group(n.get("process"),"collectedCount,missingCount,usableTimingCount,averageSeconds,generationStarted,generationSucceeded,generationFailed,generationCancelled,candidatesRejected,candidateEditStarted,rejectionRate");group(n.get("feedback"),"understood,clarify,notUnderstood,unrecorded");group(n.get("kind"),"raw,ai,edited,saved");group(n.get("source"),"independent,assisted,unknown");for(String key:List.of("categoryCounts","topCards","sceneCounts")){array(n.get(key),10000);n.get(key).forEach(row->{shape(row,"id,label,count");text(row.get("id"),100,false);text(row.get("label"),100,false);integer(row.get("count"));});}array(n.get("dailyCounts"),10000);n.get("dailyCounts").forEach(row->{shape(row,"date,count");text(row.get("date"),10,false);require(row.get("date").textValue().matches("\\d{4}-\\d{2}-\\d{2}"));integer(row.get("count"));});
    }
    static void group(JsonNode n,String fields) {shape(n,fields);for(String key:fields.split(",")){require(n.has(key));if((key.equals("averageSeconds")||key.equals("rejectionRate"))&&n.get(key).isNull())continue;number(n.get(key),0,key.equals("rejectionRate")?1:9007199254740991L);}}
}
