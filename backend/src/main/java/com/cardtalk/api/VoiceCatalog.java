package com.cardtalk.api;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** Server-owned logical voice allowlist. Provider identifiers are never exposed by public views. */
@Component
public class VoiceCatalog {
    public record Voice(String key,String label,String audience,String vcn,String resourceId) {}
    private final List<Voice> voices;
    public VoiceCatalog(ObjectMapper mapper,@Value("${cardtalk.voice-catalog-file:}") String path,@Value("${cardtalk.xfyun-engine:super-smart}") String engine) {
        if(path.isBlank()){voices=List.of();return;}
        try {
            if(!Set.of("super-smart","voice-clone").contains(engine)||Files.size(Path.of(path))>65536)throw new IllegalArgumentException();
            JsonNode root=mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(Files.readAllBytes(Path.of(path)));if(root==null||!root.isObject()||root.size()!=1||!root.path("voices").isArray()||root.path("voices").size()>50)throw new IllegalArgumentException();
            var items=new ArrayList<Voice>();Set<String> keys=new HashSet<>();String providerField=engine.equals("voice-clone")?"resourceId":"vcn";
            for(JsonNode n:root.path("voices")) {
                if(!n.isObject()||n.size()!=4)throw new IllegalArgumentException();for(String field:List.of("key","label","audience",providerField))if(!n.path(field).isTextual())throw new IllegalArgumentException();
                String key=n.path("key").asText(),label=n.path("label").asText(),audience=n.path("audience").asText(),provider=n.path(providerField).asText();
                if(!key.matches("[A-Za-z0-9_-]{1,100}")||key.equals("default")||!keys.add(key)||label.isBlank()||label.length()>100||!Set.of("children","adult","all").contains(audience)||!provider.matches("[A-Za-z0-9_.:-]{1,200}"))throw new IllegalArgumentException();
                items.add(new Voice(key,label,audience,engine.equals("super-smart")?provider:"x6_clone",engine.equals("voice-clone")?provider:""));
            }
            voices=List.copyOf(items);
        }catch(Exception ex){throw new IllegalStateException("Invalid voice catalog; no default fallback",ex);}
    }
    public List<Voice> voices(){return voices;}
    public Voice select(String key,String audience,Voice legacy) {
        if(key!=null&&!key.isBlank()) {
            if(key.equals("default"))return legacy;
            return voices.stream().filter(v->v.key().equals(key)).findFirst().orElseThrow(()->new ApiException(422,"TTS_VOICE_UNKNOWN","所选音色不在服务器目录中，请重新选择"));
        }
        if(audience!=null)for(var v:voices)if(v.audience().equals(audience))return v;
        for(var v:voices)if(v.audience().equals("all"))return v;
        return legacy;
    }
}
