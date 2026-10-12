package com.cardtalk.cloud;
import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CloudRemoteVoiceTest {
    @Test void optionalLogicalKeyIsStrictAndLegacyPreferencesRemainValid()throws Exception {
        ObjectNode prefs=(ObjectNode)new ObjectMapper().readTree("{\"displayMode\":\"both\",\"density\":\"comfortable\",\"fontSize\":\"normal\",\"rate\":1,\"volume\":1,\"deviceVoiceURI\":\"\",\"touchGuardMs\":0}");assertDoesNotThrow(()->CloudPayload.preferences(prefs));
        for(String key:new String[]{"","default","child_voice-1"}){prefs.put("remoteVoiceKey",key);assertDoesNotThrow(()->CloudPayload.preferences(prefs));}
        for(String key:new String[]{"https://provider/voice","has spaces","x".repeat(101)}){prefs.put("remoteVoiceKey",key);assertThrows(ApiException.class,()->CloudPayload.preferences(prefs));}
        prefs.putNull("remoteVoiceKey");assertThrows(ApiException.class,()->CloudPayload.preferences(prefs));
    }
}
