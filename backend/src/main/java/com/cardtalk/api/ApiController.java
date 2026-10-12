package com.cardtalk.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import static com.cardtalk.api.Contracts.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final TrialService trials;
    private final ModelGateway model;
    private final SpeechGateway speech;
    public ApiController(TrialService trials,ModelGateway model,SpeechGateway speech) { this.trials=trials; this.model=model; this.speech=speech; }
    @GetMapping("/health") public Map<String,Object> health() {
        return Map.of("status","ok","llmConfigured",model.configured(),"ttsConfigured",speech.configured(),"ttsEmotionSupported",speech.emotionSupported(),"ttsEngine",speech.engine(),"promptVersion",ModelGateway.PROMPT_VERSION);
    }
    @PostMapping("/trial-session") public SessionResponse create(@Valid @RequestBody TrialRequest input) { return trials.create(input.clientId()); }
    @GetMapping("/trial-session") public SessionResponse session(@RequestHeader(value="Authorization",required=false) String authorization) { return trials.session(token(authorization)); }
    @PostMapping("/expansions") public ExpansionResponse expand(@RequestHeader(value="Authorization",required=false) String authorization,
                                                               @Valid @RequestBody ExpansionRequest input) { return trials.expand(token(authorization),input); }
    @GetMapping("/voices") public ResponseEntity<?> voices() { return ResponseEntity.ok().header("Cache-Control","no-store").body(speech.voices()); }
    @PostMapping("/speech") public ResponseEntity<byte[]> speak(@RequestHeader(value="Authorization",required=false) String authorization,
                                                               @Valid @RequestBody SpeechRequest input) {
        trials.session(token(authorization));
        return ResponseEntity.ok().header("Content-Type","audio/mpeg").header("Cache-Control","no-store")
            .body(speech.synthesize(token(authorization),input.requestId(),input.text(),input.rate(),input.emotion(),input.voiceKey(),input.audience()));
    }
    @PostMapping("/speech/{requestId}/cancel") public Map<String,Boolean> cancelSpeech(
            @RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String requestId) {
        trials.session(token(authorization));
        if(!requestId.matches("[A-Za-z0-9_-]{1,128}")) throw new ApiException(400,"INVALID_REQUEST","语音请求编号无效");
        return Map.of("cancelled",speech.cancel(token(authorization),requestId));
    }
    private String token(String authorization) {
        return authorization!=null && authorization.startsWith("Bearer ")?authorization.substring(7):null;
    }
}
