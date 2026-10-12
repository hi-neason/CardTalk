package com.cardtalk.family;

import com.cardtalk.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.cardtalk.family.FamilyRepository.*;

@Service
public class FamilyService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int ITERATIONS=210000;
    private final FamilyRepository repository;
    private final boolean registration;
    private final long sessionSeconds;
    private final Map<String,Attempt> attempts=new HashMap<>();
    private record Attempt(int count,long reset) {}
    public record Identity(String id,String username) {}
    public record Login(Identity account,String token,long expiresAt) {}
    public record Invite(String code,long expiresAt) {}
    public record FamilyView(String id,String name,String role,List<String> profileAccess) {}
    public record MemberView(String accountId,String username,String role,List<String> profileAccess) {}
    public FamilyService(FamilyRepository repository,@Value("${cardtalk.family-registration-enabled:true}") boolean registration,
                         @Value("${cardtalk.family-session-seconds:604800}") long sessionSeconds) {
        if(sessionSeconds<60||sessionSeconds>2592000) throw new IllegalArgumentException("Family session duration must be 60..2592000 seconds");
        this.repository=repository;this.registration=registration;this.sessionSeconds=sessionSeconds;
    }
    private static ApiException denied() { return new ApiException(403,"FAMILY_ACCESS_DENIED","没有此家庭或资料的访问权限"); }
    private static ApiException unauthorized() { return new ApiException(401,"ACCOUNT_UNAUTHORIZED","请重新登录家庭账户"); }
    private static ApiException invalid(String message) { return new ApiException(400,"INVALID_REQUEST",message); }
    private static String random() { byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    private static String password(String password,String salt) {
        var spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),ITERATIONS,256);
        try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        catch(GeneralSecurityException ex) { throw new IllegalStateException(ex); }
        finally { spec.clearPassword(); }
    }
    private static String username(String name) { if(name==null||!name.matches("[A-Za-z0-9_]{3,40}")) throw invalid("用户名需为 3 至 40 位字母、数字或下划线");return name.toLowerCase(Locale.ROOT); }
    private static void credentials(String password) { if(password==null||password.length()<12||password.length()>128) throw invalid("密码需为 12 至 128 个字符"); }
    private synchronized void throttle(String bucket) {
        long now=System.currentTimeMillis(); attempts.entrySet().removeIf(e->e.getValue().reset()<now);
        Attempt previous=attempts.get(bucket);
        if(previous!=null&&previous.count()>=10) throw new ApiException(429,"ACCOUNT_RATE_LIMITED","登录尝试过多，请 15 分钟后重试");
        if(attempts.size()>=10000&&!attempts.containsKey(bucket)) throw new ApiException(429,"ACCOUNT_RATE_LIMITED","登录服务繁忙，请稍后重试");
        attempts.put(bucket,new Attempt(previous==null?1:previous.count()+1,previous==null?now+900000:previous.reset()));
    }
    public Login register(String username,String password,String address) {
        if(!registration) throw new ApiException(403,"REGISTRATION_DISABLED","注册暂未开放");
        String name=username(username);credentials(password);throttle("register:"+address);
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);String encoded=Base64.getEncoder().encodeToString(salt),digest=password(password,encoded);
        return repository.transact(data->{
            if(data.accounts().stream().anyMatch(a->a.username().equals(name))) throw new ApiException(409,"ACCOUNT_EXISTS","此用户名不可用");
            Account account=new Account(UUID.randomUUID().toString(),name,encoded,digest);data.accounts().add(account);return createSession(data,account);
        });
    }
    public Login login(String username,String password,String address) {
        String name=username(username);credentials(password);throttle("address:"+address);throttle("account:"+name);
        Account account=repository.read(data->data.accounts().stream().filter(a->a.username().equals(name)).findFirst().orElse(null));
        String actual=password(password,account==null?Base64.getEncoder().encodeToString(new byte[16]):account.salt());
        if(account==null||!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),account.passwordHash().getBytes(StandardCharsets.US_ASCII))) throw new ApiException(401,"INVALID_CREDENTIALS","用户名或密码不正确");
        return repository.transact(data->createSession(data,account));
    }
    private Login createSession(Data data,Account account) {
        long now=Instant.now().getEpochSecond(),expires=now+sessionSeconds;String token=random();
        data.sessions().removeIf(s->s.expiresAt()<=now);
        var own=data.sessions().stream().filter(s->s.accountId().equals(account.id())).toList();
        if(own.size()>=20) data.sessions().removeAll(own.subList(0,own.size()-19));
        data.sessions().add(new Session(account.id(),hash(token),expires));return new Login(new Identity(account.id(),account.username()),token,expires);
    }
    public Identity authenticate(String token) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        String digest=hash(token);long now=Instant.now().getEpochSecond();
        return repository.read(data->{Session session=data.sessions().stream().filter(s->s.tokenHash().equals(digest)&&s.expiresAt()>now).findFirst().orElseThrow(FamilyService::unauthorized);
            Account a=data.accounts().stream().filter(x->x.id().equals(session.accountId())).findFirst().orElseThrow(FamilyService::unauthorized);return new Identity(a.id(),a.username());});
    }
    public void logout(String token) { authenticate(token);repository.transact(data->{data.sessions().removeIf(s->s.tokenHash().equals(hash(token)));return null;}); }
    private static Member member(Data data,String account,String family) { return data.members().stream().filter(m->m.familyId().equals(family)&&m.accountId().equals(account)).findFirst().orElseThrow(FamilyService::denied); }
    private static Member manager(Data data,String account,String family) { Member m=member(data,account,family);if(!Set.of("owner","admin").contains(m.role())) throw denied();return m; }
    private static FamilyView view(Data data,Member member) { Family f=data.families().stream().filter(x->x.id().equals(member.familyId())).findFirst().orElseThrow(FamilyService::denied);return new FamilyView(f.id(),f.name(),member.role(),List.copyOf(member.profileAccess())); }
    public FamilyView createFamily(Identity account,String name) {
        if(name==null||name.isBlank()||name.length()>100) throw invalid("家庭名称需为 1 至 100 个字符");
        return repository.transact(data->{if(data.members().stream().filter(m->m.accountId().equals(account.id())).count()>=50) throw invalid("最多加入 50 个家庭");Family f=new Family(UUID.randomUUID().toString(),name.trim());data.families().add(f);Member m=new Member(f.id(),account.id(),"owner",List.of());data.members().add(m);return view(data,m);});
    }
    public List<FamilyView> families(Identity account) { return repository.read(data->data.members().stream().filter(m->m.accountId().equals(account.id())).map(m->view(data,m)).toList()); }
    public List<MemberView> members(Identity account,String family) { return repository.read(data->{Member requester=member(data,account.id(),family);return data.members().stream().filter(m->m.familyId().equals(family)).filter(m->!requester.role().equals("helper")||m.accountId().equals(account.id())).map(m->new MemberView(m.accountId(),data.accounts().stream().filter(a->a.id().equals(m.accountId())).findFirst().orElseThrow().username(),m.role(),List.copyOf(m.profileAccess()))).toList();}); }
    private static List<String> scope(String role,List<String> profiles,boolean allowOwner) {
        if(!Set.of("owner","admin","member","helper").contains(role)||(!allowOwner&&role.equals("owner"))) throw invalid("成员角色无效");
        if(profiles==null||profiles.size()>100||profiles.stream().anyMatch(x->x==null||!x.matches("[A-Za-z0-9_-]{1,100}"))||new HashSet<>(profiles).size()!=profiles.size()) throw invalid("资料访问范围无效");
        return role.equals("helper")?List.copyOf(profiles):List.of();
    }
    public Invite invite(Identity account,String family,String role,List<String> profileAccess) {
        List<String> access=scope(role,profileAccess,false);String code=random();long expires=Instant.now().getEpochSecond()+86400;
        return repository.transact(data->{Member issuer=manager(data,account.id(),family);if(issuer.role().equals("admin")&&role.equals("admin")) throw denied();
            data.invitations().removeIf(i->i.expiresAt()<=Instant.now().getEpochSecond());if(data.invitations().stream().filter(i->i.familyId().equals(family)).count()>=100) throw invalid("待使用邀请过多");
            data.invitations().add(new Invitation(family,account.id(),hash(code),role,access,expires));return new Invite(code,expires);});
    }
    public FamilyView accept(Identity account,String code) {
        if(code==null||!code.matches("[A-Za-z0-9_-]{43}")) throw invalid("邀请无效或已过期");
        return repository.transact(data->{Invitation invite=data.invitations().stream().filter(i->i.tokenHash().equals(hash(code))&&i.expiresAt()>Instant.now().getEpochSecond()).findFirst().orElseThrow(()->invalid("邀请无效或已过期"));
            if(data.members().stream().anyMatch(m->m.familyId().equals(invite.familyId())&&m.accountId().equals(account.id()))) throw invalid("你已加入此家庭");
            if(data.members().stream().filter(m->m.familyId().equals(invite.familyId())).count()>=100||data.members().stream().filter(m->m.accountId().equals(account.id())).count()>=50) throw invalid("家庭成员数量已达上限");
            Member m=new Member(invite.familyId(),account.id(),invite.role(),invite.profileAccess());data.members().add(m);data.invitations().remove(invite);return view(data,m);});
    }
    public void changeMember(Identity account,String family,String target,String role,List<String> profileAccess) {
        List<String> access=scope(role,profileAccess,true);
        repository.transact(data->{Member actor=manager(data,account.id(),family),old=member(data,target,family);checkChange(data,actor,old,role);data.members().remove(old);data.members().add(new Member(family,target,role,access));data.invitations().removeIf(i->i.familyId().equals(family)&&i.issuerId().equals(target));return null;});
    }
    public void remove(Identity account,String family,String target) {
        repository.transact(data->{Member actor=member(data,account.id(),family),old=member(data,target,family);if(!account.id().equals(target)) manager(data,account.id(),family);checkChange(data,actor,old,null);data.members().remove(old);data.invitations().removeIf(i->i.familyId().equals(family)&&i.issuerId().equals(target));return null;});
    }
    private static void checkChange(Data data,Member actor,Member old,String nextRole) {
        if(!actor.role().equals("owner")&&(old.role().equals("owner")||"owner".equals(nextRole)||"admin".equals(nextRole)||old.role().equals("admin")&&!actor.accountId().equals(old.accountId()))) throw denied();
        if(old.role().equals("owner")&&!"owner".equals(nextRole)&&data.members().stream().filter(m->m.familyId().equals(old.familyId())&&m.role().equals("owner")).count()<=1) throw invalid("不能移除或降级最后一位家庭所有者");
    }
    /** Always resolve live membership; sessions never cache family access. Helpers can only read explicitly granted profiles. */
    public void authorizeProfile(Identity account,String familyId,String profileId,boolean write) {
        repository.read(data->{Member m=member(data,account.id(),familyId);if(m.role().equals("helper")&&(write||profileId==null||!m.profileAccess().contains(profileId))) throw denied();return null;});
    }
}
