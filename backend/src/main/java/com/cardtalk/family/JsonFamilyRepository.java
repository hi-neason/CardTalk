package com.cardtalk.family;

import com.cardtalk.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import java.io.IOException;

/** Local development, one process only. Corrupt data fails startup closed. */
@Repository
@ConditionalOnProperty(name="cardtalk.family-repository-mode",havingValue="json",matchIfMissing=true)
public class JsonFamilyRepository implements FamilyRepository {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(JsonFamilyRepository.class);
    private final ObjectMapper mapper;
    private final Path file;
    private Data current;
    public JsonFamilyRepository(ObjectMapper mapper,@Value("${cardtalk.family-data-file:./data/families.json}") String path) throws IOException {
        this.mapper=mapper; this.file=Path.of(path).toAbsolutePath();
        current=Files.exists(file)?mapper.readValue(file.toFile(),Data.class):Data.empty();
        validate(current);
    }
    private static void validate(Data data) throws IOException {
        try {
            if(data.version()!=1) throw new IllegalArgumentException();
            Set<String> accounts=new HashSet<>(),names=new HashSet<>(),families=new HashSet<>(),members=new HashSet<>();
            for(var a:data.accounts()) if(a.id()==null||a.username()==null||a.salt()==null||a.passwordHash()==null||!a.username().matches("[a-z0-9_]{3,40}")||Base64.getDecoder().decode(a.salt()).length<16||Base64.getDecoder().decode(a.passwordHash()).length!=32||!accounts.add(a.id())||!names.add(a.username())) throw new IllegalArgumentException();
            for(var f:data.families()) if(f.id()==null||f.name()==null||!families.add(f.id())) throw new IllegalArgumentException();
            for(var m:data.members()) if(!accounts.contains(m.accountId())||!families.contains(m.familyId())||!Set.of("owner","admin","member","helper").contains(m.role())||m.profileAccess()==null||!members.add(m.familyId()+":"+m.accountId())) throw new IllegalArgumentException();
            for(var f:data.families()) if(data.members().stream().noneMatch(m->m.familyId().equals(f.id())&&m.role().equals("owner"))) throw new IllegalArgumentException();
            for(var s:data.sessions()) if(!accounts.contains(s.accountId())||s.tokenHash()==null||!s.tokenHash().matches("[a-f0-9]{64}")||s.expiresAt()<=0) throw new IllegalArgumentException();
            for(var i:data.invitations()) if(!families.contains(i.familyId())||!accounts.contains(i.issuerId())||i.tokenHash()==null||!i.tokenHash().matches("[a-f0-9]{64}")||!Set.of("admin","member","helper").contains(i.role())||i.profileAccess()==null||i.expiresAt()<=0) throw new IllegalArgumentException();
        } catch(RuntimeException ex) { throw new IOException("Family data is invalid; original file preserved",ex); }
    }
    private Data copy() { return mapper.convertValue(current,Data.class); }
    public synchronized <T> T read(Function<Data,T> action) { return action.apply(copy()); }
    public synchronized <T> T transact(Function<Data,T> action) {
        Data next=copy(); T result=action.apply(next);
        try { validate(next); persist(next); } catch(IOException ex) { LOG.error("Family transaction not persisted: exceptionType={}",ex.getClass().getSimpleName()); throw new ApiException(503,"FAMILY_STORAGE_UNAVAILABLE","家庭数据暂时无法保存，请重试",ex); }
        current=next; return result;
    }
    protected void persist(Data data) throws IOException {
        Files.createDirectories(file.getParent()); Path tmp=Files.createTempFile(file.getParent(),"family-",".tmp");
        try {
            byte[] bytes=mapper.writeValueAsBytes(data);
            try(var channel=FileChannel.open(tmp,StandardOpenOption.WRITE)) { ByteBuffer buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
}
