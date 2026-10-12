package com.cardtalk.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VoiceCatalogTest {
    @TempDir Path directory;final ObjectMapper mapper=new ObjectMapper();
    VoiceCatalog catalog(String engine)throws Exception {
        String field=engine.equals("voice-clone")?"resourceId":"vcn";
        Path path=directory.resolve(engine+".json");mapper.writeValue(path.toFile(),Map.of("voices",List.of(Map.of("key","child","label","儿童候选音色","audience","children",field,"PRIVATE_CHILD_RESOURCE"),Map.of("key","adult","label","成人候选音色","audience","adult",field,"PRIVATE_ADULT_RESOURCE"))));return new VoiceCatalog(mapper,path.toString(),engine);
    }
    SpeechGateway gateway(SpeechGatewayTest.FakeSocket socket,String engine,VoiceCatalog catalog){var gateway=new SpeechGateway(mapper,"ws://127.0.0.1:"+socket.server.getLocalPort()+"/tts","app","PRIVATE_KEY","PRIVATE_SECRET","legacy-vcn",5,engine,"legacy-resource");gateway.setVoiceCatalog(catalog);return gateway;}
    String audio()throws Exception{return mapper.writeValueAsString(Map.of("header",Map.of("code",0,"status",2),"payload",Map.of("audio",Map.of("encoding","lame","status",2,"seq",0,"audio","AQ=="))));}
    @Test void twoPresetsAndExplicitSelectionUseServerAllowlistedPayloadsForBothEngines()throws Exception {
        for(String engine:List.of("super-smart","voice-clone"))for(String preset:List.of("children","adult")) {
            var catalog=catalog(engine);try(var socket=new SpeechGatewayTest.FakeSocket(peer->peer.send(audio(),true,1))){var gateway=gateway(socket,engine,catalog);assertArrayEquals(new byte[]{1},gateway.synthesize("owner","r","确认的表达",1d,engine.equals("voice-clone")?"happy":null,null,preset));var sent=mapper.readTree(socket.request.get(2,TimeUnit.SECONDS));String expected=preset.equals("children")?"PRIVATE_CHILD_RESOURCE":"PRIVATE_ADULT_RESOURCE";assertEquals(expected,sent.at(engine.equals("voice-clone")?"/header/res_id":"/parameter/tts/vcn").asText());if(engine.equals("voice-clone"))assertEquals("glad",sent.at("/parameter/tts/style").asText());}
        }
        try(var socket=new SpeechGatewayTest.FakeSocket(peer->peer.send(audio(),true,1))){var gateway=gateway(socket,"super-smart",catalog("super-smart"));gateway.synthesize("owner","override","表达",null,null,"adult","children");assertEquals("PRIVATE_ADULT_RESOURCE",mapper.readTree(socket.request.get()).at("/parameter/tts/vcn").asText());}
    }
    @Test void unknownChoiceDoesNotFallbackAndCancellationIdentityDoesNotDependOnVoice()throws Exception {
        try(var socket=new SpeechGatewayTest.FakeSocket(peer->peer.in.read());var executor=Executors.newSingleThreadExecutor()){
            var gateway=gateway(socket,"super-smart",catalog("super-smart"));assertEquals("TTS_VOICE_UNKNOWN",assertThrows(ApiException.class,()->gateway.synthesize("id","unknown","表达",null,null,"missing","adult")).code);assertFalse(socket.request.isDone());
            var running=executor.submit(()->gateway.synthesize("id","same-request","表达",null,null,"child","children"));socket.request.get(2,TimeUnit.SECONDS);
            assertEquals("TTS_ALREADY_RUNNING",assertThrows(ApiException.class,()->gateway.synthesize("id","same-request","表达",null,null,"adult","adult")).code);
            assertFalse(gateway.cancel("other-id","same-request"));assertTrue(gateway.cancel("id","same-request"));var error=assertThrows(ExecutionException.class,()->running.get(2,TimeUnit.SECONDS));assertEquals("TTS_CANCELLED",((ApiException)error.getCause()).code);
            assertEquals("TTS_CANCELLED",assertThrows(ApiException.class,()->gateway.synthesize("id","same-request","表达",null,null,"adult","adult")).code);
        }
    }
    @Test void publicListContainsOnlyLogicalMetadataAndConfigurationIsNotAuthorization()throws Exception {
        var gateway=new SpeechGateway(mapper,"","app","PRIVATE_KEY","PRIVATE_SECRET","legacy-private",2,"voice-clone","");gateway.setVoiceCatalog(catalog("voice-clone"));assertTrue(gateway.configured());assertTrue(gateway.emotionSupported());
        var mvc=MockMvcBuilders.standaloneSetup(new ApiController(null,null,gateway)).build();var result=mvc.perform(get("/api/v1/voices")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();assertFalse(result.contains("PRIVATE"));assertFalse(result.contains("resourceId"));assertFalse(result.contains("vcn"));
        for(JsonNode voice:mapper.readTree(result)){Set<String> fields=new HashSet<>();voice.fieldNames().forEachRemaining(fields::add);assertEquals(Set.of("key","label","audience","emotionSupported","configured"),fields);}
        assertFalse(gateway.voices().getFirst().configured());assertTrue(gateway.voices().get(1).configured());
    }
    @Test void malformedMixedEngineAndDuplicateCatalogsFailClosed()throws Exception {
        for(String json:List.of("not-json","{}","{\"voices\":[]} {}","{\"voices\":[],\"voices\":[]}","{\"voices\":[{\"key\":\"a\",\"label\":\"音色\",\"audience\":\"adult\",\"resourceId\":\"res\"}]}","{\"voices\":[{\"key\":\"default\",\"label\":\"音色\",\"audience\":\"adult\",\"vcn\":\"valid\"}]}","{\"voices\":[{\"key\":\"a\",\"label\":\"音色\",\"audience\":\"adult\",\"vcn\":\"https://evil\"}]}")){
            Path path=directory.resolve("invalid.json");Files.writeString(path,json);assertThrows(IllegalStateException.class,()->new VoiceCatalog(mapper,path.toString(),"super-smart"));
        }
        var voice=Map.of("key","a","label","音色","audience","adult","vcn","valid");Path duplicate=directory.resolve("duplicate.json");mapper.writeValue(duplicate.toFile(),Map.of("voices",List.of(voice,voice)));assertThrows(IllegalStateException.class,()->new VoiceCatalog(mapper,duplicate.toString(),"super-smart"));
        var empty=new VoiceCatalog(mapper,"","super-smart");assertTrue(empty.voices().isEmpty());var legacy=new VoiceCatalog.Voice("default","默认","all","legacy","");assertSame(legacy,empty.select(null,"children",legacy));assertSame(legacy,empty.select("default","adult",legacy));
    }
}
