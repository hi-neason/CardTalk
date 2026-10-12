package com.cardtalk.operations;

import jakarta.validation.constraints.*;
import java.util.List;

public final class SupportContracts {
    private SupportContracts() {}
    public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String requestId,
                         @NotBlank @Pattern(regexp="support|privacy_deletion") String kind,
                         @NotBlank @Size(max=4000) String message,@Size(max=200) String contact,
                         @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String familyId) {}
    public record Update(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String requestId,
                         @NotBlank @Pattern(regexp="in_progress|resolved|rejected") String status,
                         @NotBlank @Size(max=4000) String reply,@Size(max=2000) String deletionEvidence) {}
    public record Ticket(String id,String kind,String message,String contact,String familyId,String status,String reply,String deletionEvidence,String createdAt,String updatedAt,String retainUntil) {}
    public record Page(List<Ticket> tickets,String nextCursor,boolean hasMore) {}
    public record Audit(String id,String actor,String action,String reply,String deletionEvidence,String createdAt) {}
    public record Actor(String key,String accountId) {}
}
