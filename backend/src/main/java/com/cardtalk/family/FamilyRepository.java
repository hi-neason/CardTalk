package com.cardtalk.family;

import java.util.*;
import java.util.function.Function;

/** Single transaction boundary; implementations must not publish failed writes. */
public interface FamilyRepository {
    record Account(String id,String username,String salt,String passwordHash) {}
    record Session(String accountId,String tokenHash,long expiresAt) {}
    record Family(String id,String name) {}
    record Member(String familyId,String accountId,String role,List<String> profileAccess) {}
    record Invitation(String familyId,String issuerId,String tokenHash,String role,List<String> profileAccess,long expiresAt) {}
    record Data(int version,List<Account> accounts,List<Session> sessions,List<Family> families,List<Member> members,List<Invitation> invitations) {
        public static Data empty() { return new Data(1,new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>()); }
    }
    <T> T read(Function<Data,T> action);
    <T> T transact(Function<Data,T> action);
}
