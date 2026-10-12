package com.cardtalk.integration;

import com.cardtalk.api.Application;
import com.cardtalk.family.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real servlet wiring and real isolated PostgreSQL; no mocked business services or external providers. */
class IntegratedFamilyHttpTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();
    String database, user, password, schema, base, operator, reader, owner, cookie, family;
    ServletWebServerApplicationContext app;
    static final String ORIGIN = "http://127.0.0.1:5173";
    static final String PASSWORD = "synthetic-password-123";

    @BeforeEach void start() throws Exception {
        database = System.getenv("CARDTALK_TEST_PG_URL");
        assumeTrue(database != null && !database.isBlank(), "Real PostgreSQL required via CARDTALK_TEST_PG_URL/USER/PASSWORD");
        user = Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_USER"), "");
        password = Objects.requireNonNullElse(System.getenv("CARDTALK_TEST_PG_PASSWORD"), "");
        schema = "ct_http_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(database, user, password); var s = c.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
        String url = database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var accounts = new FamilyService(new PostgresFamilyRepository(url, user, password), true, 3600);
        var ops = accounts.register("operator", PASSWORD, "synthetic");
        var read = accounts.register("readonly", PASSWORD, "synthetic");
        operator = ops.token(); reader = read.token();
        app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(Application.class).run(
            "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
            "--cardtalk.family-repository-mode=postgres", "--cardtalk.family-jdbc-url=" + url,
            "--cardtalk.family-jdbc-user=" + user, "--cardtalk.family-jdbc-password=" + password,
            "--cardtalk.family-registration-enabled=true", "--cardtalk.family-cookie-secure=false",
            "--cardtalk.family-allowed-origins=" + ORIGIN,
            "--cardtalk.trial-repository-mode=postgres", "--cardtalk.report-quota-mode=postgres",
            "--cardtalk.asset-store-mode=local", "--cardtalk.asset-metadata-mode=postgres", "--cardtalk.asset-directory=" + directory.resolve("assets"),
            "--cardtalk.cloud-enabled=true", "--cardtalk.billing-enforced=true", "--cardtalk.billing-provider=none", "--cardtalk.billing-products-file=",
            "--cardtalk.billing-operator-account-ids=" + ops.account().id(),
            "--cardtalk.support-enabled=true", "--cardtalk.support-operator-account-ids=" + ops.account().id(),
            "--cardtalk.content-enabled=true", "--cardtalk.content-operator-account-ids=" + ops.account().id(),
            "--cardtalk.metrics-enabled=true", "--cardtalk.metrics-operator-account-ids=" + read.account().id(),
            "--cardtalk.analytics-enabled=true", "--cardtalk.llm-api-key=", "--cardtalk.xfyun-api-key=", "--cardtalk.xfyun-api-secret=", "--cardtalk.xfyun-app-id=");
        base = "http://127.0.0.1:" + app.getWebServer().getPort();
        owner = json(200, send("/accounts/native/register", null, Map.of("username", "owner", "password", PASSWORD))).path("token").asText();
        assertFalse(owner.isBlank());
        var login = send("/accounts/login", null, Map.of("username", "owner", "password", PASSWORD));
        json(200, login);
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly")); assertTrue(setCookie.contains("SameSite=Strict"));
        cookie = setCookie.split(";", 2)[0];
        family = json(200, send("/families", owner, Map.of("name", "合成测试家庭"))).path("id").asText();
    }

    @AfterEach void cleanup() throws Exception {
        if (app != null) app.close();
        if (schema != null) try (var c = DriverManager.getConnection(database, user, password); var s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    HttpResponse<String> send(String path, String token, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + "/api/v1" + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (path.startsWith("/support/")) b.header("X-CardTalk-Support-Scope", "account");
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(int expected, HttpResponse<String> response) throws Exception {
        assertEquals(expected, response.statusCode(), () -> "Unexpected HTTP response: " + response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return mapper.readTree(response.body());
    }
    String familyPath(String suffix) { return "/families/" + family + suffix; }
    Map<String,Object> grant(String feature) { return Map.of("feature", feature, "startsAt", Instant.now().minusSeconds(60).toString(), "expiresAt", Instant.now().plusSeconds(3600).toString()); }
    void enable(String feature) throws Exception { assertEquals("manual-test", json(200, send(familyPath("/billing/manual-grants"), operator, grant(feature))).path("source").asText()); }
    ObjectNode node(String json) throws Exception { return (ObjectNode) mapper.readTree(json); }
    Map<String,Object> operation(String type, JsonNode payload) { return Map.of("operationId", type + "-create", "entityType", type, "entityId", payload.path("id").asText(), "baseRevision", 0, "deleted", false, "payload", payload); }
    Object push(List<?> operations) { return Map.of("consent", Map.of("photos", false, "observations", false), "operations", operations); }

    @Test void entitlementGatesAtomicCloudRoundTripAndCookieOrigin() throws Exception {
        var profile = node("""
            {"id":"p1","name":"合成使用人","preset":"adult","age":70,"language":"普通话","interests":"","personality":"","communicationStyle":"","observations":"","observer":"","observedAt":null,"archived":false,"updatedAt":"2026-10-12T00:00:00Z"}
            """);
        Object input = push(List.of(operation("profile", profile)));
        json(403, send(familyPath("/sync/push"), owner, input));
        assertTrue(json(200, send(familyPath("/sync/pull"), owner, null)).path("entities").isEmpty());
        enable("profiles"); enable("scenarios");
        var card = node("""
            {"id":"custom-card-sip","label":"喝水","category":"actions","icon":"drink","kind":"word","sceneIds":["custom-scene-rest"],"archived":false,"updatedAt":"2026-10-12T00:00:00Z"}
            """);
        var scene = node("""
            {"id":"custom-scene-rest","name":"休息","description":"休息饮水","activities":"","objects":"","recommendedCardIds":["custom-card-sip"],"archived":false,"updatedAt":"2026-10-12T00:00:00Z"}
            """);
        var context = node("""
            {"preset":"adult","sceneId":"custom-scene-rest","participants":[],"communicatorName":"合成使用人","source":"independent","profileId":"p1"}
            """);
        var snapshot = card.deepCopy(); snapshot.remove(List.of("sceneIds", "archived", "updatedAt"));
        var turn = node("""
            {"id":"turn1","createdAt":"2026-10-12T00:00:00Z","finalText":"喝水","source":"independent","wasEdited":false,"kind":"raw","playbackEvents":[],"feedback":null}
            """);
        turn.set("cardSnapshot", mapper.createArrayNode().add(snapshot)); turn.set("contextSnapshot", context);
        var room = node("""
            {"id":"room1","startedAt":"2026-10-12T00:00:00Z","endedAt":"2026-10-12T00:00:01Z","status":"ended","draft":{"version":1,"cards":[],"expansion":null,"confirmedText":null,"selectedCandidateId":null,"confirmedTurnId":null}}
            """);
        room.set("context", context); room.set("turns", mapper.createArrayNode().add(turn));
        Object batch = push(List.of(operation("profile", profile), operation("card", card), operation("scene", scene), operation("room", room)));
        String path = base + "/api/v1" + familyPath("/sync/push");
        var b = HttpRequest.newBuilder(URI.create(path)).header("Cookie", cookie).header("Content-Type", "application/json");
        json(403, client.send(b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(batch))).build(), HttpResponse.BodyHandlers.ofString()));
        json(200, client.send(b.header("Origin", ORIGIN).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(batch))).build(), HttpResponse.BodyHandlers.ofString()));
        var entities = json(200, send(familyPath("/sync/pull"), owner, null)).path("entities");
        assertEquals(4, entities.size());
        JsonNode restored = null;
        for (var entity : entities) if (entity.path("entityType").asText().equals("room")) restored = entity.path("payload");
        assertEquals(room, restored, "An ended room must retain the selected custom-card snapshot");
    }

    @Test void assetsRoundTripOnlyWithinAuthorizedFamily() throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        byte[] png = bytes.toByteArray();
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1" + familyPath("/assets?profileId=p1"))).header("Authorization", "Bearer " + owner).header("Content-Type", "image/png").POST(HttpRequest.BodyPublishers.ofByteArray(png)).build();
        String id = json(201, client.send(request, HttpResponse.BodyHandlers.ofString())).path("id").asText();
        json(403, send(familyPath("/assets/" + id), reader, null));
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1" + familyPath("/assets/" + id))).header("Authorization", "Bearer " + owner).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode()); assertArrayEquals(png, response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test void supportContentAndMetricsKeepIndependentOperatorPermissions() throws Exception {
        String id = json(200, send("/support/tickets", owner, Map.of("requestId", "ticket1", "kind", "support", "message", "合成问题", "familyId", family))).path("id").asText();
        assertTrue(json(200, send("/support/tickets", reader, null)).path("tickets").isEmpty());
        json(403, send("/operations/tickets", reader, null));
        json(403, send(familyPath("/billing/manual-grants"), reader, grant("profiles")));
        json(200, send("/operations/metrics", reader, null));
        json(403, send("/operations/metrics", operator, null));
        json(200, send("/operations/tickets/" + id + "/updates", operator, Map.of("requestId", "reply1", "status", "in_progress", "reply", "已收到合成问题")));
        assertEquals("已收到合成问题", json(200, send("/support/tickets", owner, null)).path("tickets").get(0).path("reply").asText());
        assertEquals(2, json(200, send("/operations/tickets/" + id + "/audit", operator, null)).size());
        for (String version : List.of("v1", "v2")) {
            var pack = node("""
                {"id":"%s","title":"合成资源","source":"测试自编","license":"CC0-1.0","cards":[{"id":"sip","label":"喝水","category":"actions","icon":"drink","kind":"word","description":"合成卡片","sceneIds":[]}],"scenes":[]}
                """.formatted(version));
            json(403, send("/operations/content/versions", reader, pack));
            json(200, send("/operations/content/versions", operator, pack));
        }
        json(200, send("/operations/content/release", operator, Map.of("versionId", "v1", "expectedRevision", 0)));
        json(200, send("/operations/content/release", operator, Map.of("versionId", "v2", "expectedRevision", 1)));
        json(200, send("/operations/content/release", operator, Map.of("versionId", "v1", "expectedRevision", 2)));
        var current = json(200, send("/resources/current", null, null));
        assertEquals("v1", current.path("package").path("id").asText()); assertEquals(3, current.path("revision").asInt());
    }

    @Test void entitledFamilyAiFailsExplicitlyWithoutProviderConfiguration() throws Exception {
        enable("profiles"); enable("scenarios"); enable("analytics");
        String trial = mapper.readTree(send("/trial-session", null, Map.of("clientId", "synthetic-http-client")).body()).path("token").asText();
        String body = """
            {"requestId":"expansion1","inputVersion":1,"roomId":"room1","cards":[{"id":"apple","label":"苹果"}],"context":{"preset":"adult","participants":[],"profile":{"name":"合成使用人","age":70},"scene":{"id":"custom-scene-rest","name":"休息","description":"休息饮水"}}}
            """;
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1" + familyPath("/expansions"))).header("Authorization", "Bearer " + owner).header("X-CardTalk-Trial-Token", trial).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals("LLM_NOT_CONFIGURED", json(503, client.send(request, HttpResponse.BodyHandlers.ofString())).path("code").asText());
        Map<String,Integer> facts = new LinkedHashMap<>();
        for (String key : List.of("confirmedCount", "roomCount", "activeDays", "understood", "clarify", "notUnderstood", "unrecorded", "raw", "ai", "edited", "saved", "processCollected", "processMissing")) facts.put(key, 0);
        assertEquals("LLM_NOT_CONFIGURED", json(503, send(familyPath("/reports/narrative"), owner, Map.of("requestId", "report1", "facts", facts))).path("code").asText());
    }
}
