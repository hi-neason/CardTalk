package com.cardtalk.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class CloudDtos {
    private CloudDtos() {}
    public record Consent(@NotNull Boolean photos,@NotNull Boolean observations) {}
    public record Operation(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String operationId,
        @NotBlank @Pattern(regexp="profile|card|scene|room|report") String entityType,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String entityId,
        @NotNull @Min(0) Long baseRevision,@NotNull Boolean deleted,JsonNode payload) {}
    public record Push(@NotNull @Valid Consent consent,@NotNull @Size(min=1,max=50) List<@Valid Operation> operations) {}
    public record Entity(String entityType,String entityId,long revision,String profileId,boolean deleted,JsonNode payload) {}
    public record Acknowledgement(String operationId,String entityType,String entityId,long revision) {}
    public record PushResult(List<Acknowledgement> results,String cursor) {}
    public record PullResult(List<Entity> entities,String cursor,boolean hasMore) {}
}
