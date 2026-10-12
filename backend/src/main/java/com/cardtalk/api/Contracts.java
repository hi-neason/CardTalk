package com.cardtalk.api;

import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class Contracts {
    private Contracts() {}
    public record SpeechRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String requestId,
                                @NotBlank @Size(max=1100) String text, @DecimalMin("0.5") @DecimalMax("2.0") Double rate,
                                @Pattern(regexp="neutral|happy|sad|angry|fearful") String emotion,
                                @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String voiceKey,
                                @Pattern(regexp="children|adult") String audience) {
        public SpeechRequest(String requestId,String text,Double rate,String emotion) {this(requestId,text,rate,emotion,null,null);}
    }
    public record TrialRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,128}") String clientId) {}
    public record Card(@NotBlank @Size(max=128) String id, @NotBlank @Size(max=80) String label,
                       @Size(max=500) String description, @Size(max=128) String personId) {}
    public record Scene(@NotBlank @Size(max=128) String id, @NotBlank @Size(max=100) String name,
                        @Size(max=2200) String description) {}
    public record Participant(@NotBlank @Size(max=128) String id, @NotBlank @Size(max=80) String name) {}
    public record Profile(@Size(max=100) String name, @Min(0) @Max(130) Integer age,
                          @Size(max=500) String interests, @Size(max=500) String communicationStyle,
                          @Size(max=500) String currentState,
                          @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=80) String language,
                          @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=500) String personality) {}
    public record Context(@NotNull @Pattern(regexp="children|adult") String preset, @Valid Scene scene,
                          @NotNull @Size(max=20) List<@NotNull @Valid Participant> participants, @Valid Profile profile,
                          @Pattern(regexp="neutral|happy|sad|angry|fearful") String emotion) {}
    public record ExpansionRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String requestId,
                                   @NotNull @Min(0) Long inputVersion, @NotBlank @Size(max=128) String roomId,
                                   @NotNull @Size(min=1,max=20) List<@NotNull @Valid Card> cards,
                                   @NotNull @Valid Context context) {}
    public record Candidate(String id, String text, List<String> sourceCardIds) {}
    public record ExpansionResponse(String requestId, long inputVersion, List<Candidate> candidates,
                                    boolean needsClarification, String clarification, String promptVersion, int remaining) {}
    public record SessionResponse(String token, int remaining, int limit, String resetAt, Map<String,Boolean> capabilities) {}
}
