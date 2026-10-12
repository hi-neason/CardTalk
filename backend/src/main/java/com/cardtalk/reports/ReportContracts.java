package com.cardtalk.reports;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;

public final class ReportContracts {
    private ReportContracts() {}
    @JsonDeserialize(using=FactsDeserializer.class)
    public record Facts(int confirmedCount,int roomCount,int activeDays,int understood,int clarify,int notUnderstood,int unrecorded,int raw,int ai,int edited,int saved,int processCollected,int processMissing) {
        public void validate() {
            for(int value:List.of(confirmedCount,roomCount,activeDays,understood,clarify,notUnderstood,unrecorded,raw,ai,edited,saved,processCollected,processMissing)) if(value<0||value>500000) throw invalid();
            if(understood+clarify+notUnderstood+unrecorded!=confirmedCount||raw+ai+edited+saved!=confirmedCount||processCollected+processMissing!=confirmedCount||roomCount>confirmedCount||activeDays>confirmedCount||confirmedCount>0&&(roomCount==0||activeDays==0)) throw invalid();
        }
    }
    public record Request(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String requestId,@NotNull @Valid Facts facts) {}
    public record Response(String requestId,String text,String promptVersion,String provider,int remaining) {}
    static ApiException invalid() { return new ApiException(400,"INVALID_REPORT_FACTS","统计数量不符合要求，请刷新分析后重试"); }
    public static class FactsDeserializer extends JsonDeserializer<Facts> {
        public Facts deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            JsonNode node=parser.getCodec().readTree(parser);
            String[] fields={"confirmedCount","roomCount","activeDays","understood","clarify","notUnderstood","unrecorded","raw","ai","edited","saved","processCollected","processMissing"};int[] values=new int[fields.length];
            for(int index=0;index<fields.length;index++) { JsonNode count=node.get(fields[index]);if(count==null||!count.isIntegralNumber()||!count.canConvertToInt()||count.intValue()<0||count.intValue()>500000) throw context.weirdStringException("",Facts.class,"Invalid report count");values[index]=count.intValue(); }
            return new Facts(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9],values[10],values[11],values[12]);
        }
    }
}
