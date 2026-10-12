package com.cardtalk.family;

import com.cardtalk.api.ApiException;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.cardtalk.family.FamilyRepository.*;

/** Uses a real, explicitly supplied PostgreSQL server. Each test owns one disposable schema. */
class PostgresFamilyTest {
    String baseUrl,url,user,password,schema;
    PostgresFamilyRepository repository;
    FamilyService service;
    @BeforeEach void setup() throws Exception {
        baseUrl=System.getenv("CARDTALK_TEST_PG_URL");
        assumeTrue(baseUrl!=null&&!baseUrl.isBlank(),"Real PostgreSQL tests skipped: set CARDTALK_TEST_PG_URL/USER/PASSWORD");
        user=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"),"");password=Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"),"");
        schema="ct_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(baseUrl,user,password);var s=c.createStatement()) { s.execute("CREATE SCHEMA "+schema); }
        url=baseUrl+(baseUrl.contains("?")?"&":"?")+"currentSchema="+schema;
        repository=new PostgresFamilyRepository(url,user,password);service=new FamilyService(repository,true,3600);
    }
    @AfterEach void cleanup() throws Exception {
        if(schema==null) return;
        try(var c=DriverManager.getConnection(baseUrl,user,password);var s=c.createStatement()) { s.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
    FamilyService.Login register(String name) { return service.register(name,"test-password-123",name); }
    Connection connection() throws SQLException { return DriverManager.getConnection(url,user,password); }
    @Test void restartPreservesCredentialsInvitationsSessionsScopesAndRevocation() throws Exception {
        var owner=register("pg_owner");var helper=register("pg_helper");var family=service.createFamily(owner.account(),"PG家庭");
        var invitation=service.invite(owner.account(),family.id(),"helper",List.of("profile-two","profile-one"));
        var reopened=new FamilyService(new PostgresFamilyRepository(url,user,password),true,3600);
        assertEquals(owner.account(),reopened.authenticate(owner.token()));
        assertEquals(owner.account(),reopened.login("pg_owner","test-password-123","login").account());
        reopened.accept(helper.account(),invitation.code());reopened.authorizeProfile(helper.account(),family.id(),"profile-two",false);
        assertThrows(ApiException.class,()->reopened.authorizeProfile(helper.account(),family.id(),"profile-two",true));
        assertThrows(ApiException.class,()->reopened.authorizeProfile(helper.account(),family.id(),"ungranted",false));
        assertThrows(ApiException.class,()->reopened.accept(helper.account(),invitation.code()));
        service.remove(owner.account(),family.id(),helper.account().id());
        assertThrows(ApiException.class,()->reopened.authorizeProfile(reopened.authenticate(helper.token()),family.id(),"profile-one",false));
        reopened.logout(owner.token());assertThrows(ApiException.class,()->service.authenticate(owner.token()));
        try(var c=connection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT salt,password_hash FROM ct_family_accounts")) {
            while(rows.next()) { assertTrue(Base64.getDecoder().decode(rows.getString(1)).length>=16);assertEquals(32,Base64.getDecoder().decode(rows.getString(2)).length);assertNotEquals("test-password-123",rows.getString(2)); }
        }
    }
    @Test void simultaneousRepositoryInstancesCannotLoseUpdates() throws Exception {
        var owner=register("concurrent_owner");var other=new FamilyService(new PostgresFamilyRepository(url,user,password),true,3600);
        var start=new CountDownLatch(1);List<Future<?>> futures=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(4)) {
            for(int index=0;index<16;index++) { int i=index;futures.add(pool.submit(()->{start.await();(i%2==0?service:other).createFamily(owner.account(),"家庭 "+i);return null;})); }
            start.countDown();for(var future:futures) future.get(20,TimeUnit.SECONDS);
        }
        assertEquals(16,service.families(owner.account()).size());assertEquals(16,other.families(owner.account()).size());
        assertEquals(16,repository.<Integer>read(d->d.members().size()));
    }
    @Test void transactionFailureRollsBackEarlierStatementsAndLeavesOtherInstancesUnchanged() {
        var owner=register("rollback_owner");int original=repository.<Integer>read(d->d.accounts().size());
        assertThrows(ApiException.class,()->repository.transact(data->{
            data.families().add(new Family("failed-family","事务失败"));
            data.members().add(new Member("failed-family","missing-account","owner",List.of()));return null;
        }));
        assertTrue(service.families(owner.account()).isEmpty());assertTrue(repository.<Boolean>read(d->d.families().isEmpty()));
        assertThrows(ApiException.class,()->repository.transact(data->{var a=data.accounts().get(0);data.accounts().add(new Account("duplicate-name",a.username(),a.salt(),a.passwordHash()));return null;}));
        assertEquals(original,repository.<Integer>read(d->d.accounts().size()));
        assertThrows(IllegalStateException.class,()->repository.transact(data->{data.families().add(new Family("callback-failed","未提交"));throw new IllegalStateException("callback failed");}));
        assertTrue(new PostgresFamilyRepository(url,user,password).<Boolean>read(d->d.families().isEmpty()));
    }
    @Test void databaseConstraintsRejectDanglingDuplicateAndInvalidScopeRows() throws Exception {
        var owner=register("constraint_owner");var family=service.createFamily(owner.account(),"约束家庭");
        try(var c=connection();var statement=c.createStatement()) {
            assertEquals("23503",assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO ct_family_members VALUES ('missing-family','"+owner.account().id()+"','member')")).getSQLState());
            assertEquals("23505",assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO ct_family_members VALUES ('"+family.id()+"','"+owner.account().id()+"','member')")).getSQLState());
            assertEquals("23514",assertThrows(SQLException.class,()->statement.executeUpdate("INSERT INTO ct_family_profile_access VALUES ('"+family.id()+"','"+owner.account().id()+"','invalid scope')")).getSQLState());
        }
        assertThrows(ApiException.class,()->repository.transact(data->{data.members().clear();return null;}));
        assertEquals("owner",service.members(owner.account(),family.id()).get(0).role());
    }
    @Test void migrationsAreConcurrentIdempotentAndRejectChecksumDrift() throws Exception {
        var owner=register("migration_owner");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->new PostgresFamilyRepository(url,user,password));var b=pool.submit(()->new PostgresFamilyRepository(url,user,password));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        try(var c=connection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM ct_family_schema_version")) { assertTrue(rows.next());assertEquals(1,rows.getInt(1)); }
        assertEquals(owner.account(),service.authenticate(owner.token()));
        try(var c=connection();var statement=c.createStatement()) { statement.executeUpdate("UPDATE ct_family_schema_version SET checksum='"+"0".repeat(64)+"'"); }
        assertNotNull(assertThrows(IllegalStateException.class,()->new PostgresFamilyRepository(url,user,password)).getCause());
        assertEquals(owner.account(),service.authenticate(owner.token()));
    }
    @Test void unknownRepositoryModeFailsBeforeBeanCreation() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixture",Map.of("cardtalk.family-repository-mode","postgress")));
            context.register(FamilyRepositoryConfiguration.class);
            var failure=assertThrows(IllegalStateException.class,context::refresh);
            assertTrue(failure.getMessage().contains("must be exactly json or postgres"));
        }
    }
    @Test void normalizedUpdatesPruneOnlyRemovedRowsAndPersistHelperScopeChanges() throws Exception {
        var owner=register("scope_owner");var helper=register("scope_helper");var family=service.createFamily(owner.account(),"范围家庭");
        service.accept(helper.account(),service.invite(owner.account(),family.id(),"helper",List.of("a","b")).code());
        service.changeMember(owner.account(),family.id(),helper.account().id(),"helper",List.of("b","c"));
        var other=new FamilyService(new PostgresFamilyRepository(url,user,password),true,3600);
        other.authorizeProfile(helper.account(),family.id(),"c",false);assertThrows(ApiException.class,()->other.authorizeProfile(helper.account(),family.id(),"a",false));
        service.invite(owner.account(),family.id(),"member",List.of());
        assertEquals(1,repository.<Integer>read(d->d.invitations().size()));assertEquals(2,repository.<Integer>read(d->d.accounts().size()));
        try(var c=connection();var statement=c.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM ct_family_invitation_access")) { assertTrue(rows.next());assertEquals(0,rows.getInt(1)); }
    }
}
