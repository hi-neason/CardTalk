package com.cardtalk.cloud;
import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CloudPublicProvenanceTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode provenance()throws Exception{return (ObjectNode)mapper.readTree("""
        {"packageId":"daily-v1","title":"日常资源","source":"本项目自编文字","license":"CC0-1.0","revision":12}
        """);}
    @Test void cardSceneAndSnapshotAcceptStrictPublicProvenance()throws Exception{
        ObjectNode card=(ObjectNode)mapper.readTree("""
        {"id":"custom-card-public-sip","label":"喝水","category":"actions","icon":"drink","kind":"word","sceneIds":[],"archived":false,"updatedAt":"2026-10-12T00:00:00Z"}
        """);card.set("provenance",provenance());assertDoesNotThrow(()->CloudPayload.card(card,true));card.remove(java.util.List.of("sceneIds","archived","updatedAt"));assertDoesNotThrow(()->CloudPayload.card(card,false));
        ObjectNode scene=(ObjectNode)mapper.readTree("""
        {"id":"custom-scene-public-break","name":"休息","description":"日常休息","activities":"","objects":"","recommendedCardIds":[],"archived":false,"updatedAt":"2026-10-12T00:00:00Z"}
        """);scene.set("provenance",provenance());assertDoesNotThrow(()->CloudPayload.scene(scene,false));scene.remove(java.util.List.of("activities","objects","recommendedCardIds","archived","updatedAt"));assertDoesNotThrow(()->CloudPayload.scene(scene,true));
    }
    @Test void privateFieldsUnknownFieldsAndUnsafeRevisionsRejected()throws Exception{
        for(String key:java.util.List.of("observations","image","accountId","assetRef")){var p=provenance();p.put(key,"private");assertThrows(ApiException.class,()->CloudPayload.provenance(p));}
        for(double revision:new double[]{-1,0.5,9007199254740992d}){var p=provenance();p.put("revision",revision);assertThrows(ApiException.class,()->CloudPayload.provenance(p));}
        var p=provenance();p.remove("revision");assertDoesNotThrow(()->CloudPayload.provenance(p));p.put("license","");assertThrows(ApiException.class,()->CloudPayload.provenance(p));
    }
}
