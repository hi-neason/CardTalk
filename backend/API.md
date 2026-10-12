# CardTalk P1 API

Java 21 target / Spring Boot 3.5.13. Run from the repository root:

```sh
npm run test:backend
npm run dev:backend
```

Default listener is `127.0.0.1:8080` (`CARDTALK_BIND_ADDRESS` / `PORT`). The frontend development server should proxy `/api` here. CORS is intentionally not globally opened. This is a single-process local development backend; production PostgreSQL storage, verified identities and abuse controls remain later delivery work.

## Configuration

| Environment | Default / purpose |
|---|---|
| `CARDTALK_BIND_ADDRESS` | `127.0.0.1`; loopback only, explicitly override for a trusted LAN test |
| `PORT` | `8080` |
| `CARDTALK_LLM_BASE_URL` | `https://api.deepseek.com`; API prefix, appended `/chat/completions` |
| `CARDTALK_LLM_MODEL` | `deepseek-flash` |
| `CARDTALK_LLM_API_KEY` | empty; required to enable real expansion |
| `CARDTALK_LLM_TIMEOUT_SECONDS` | `30` |
| `CARDTALK_DAILY_LIMIT` | `20`, resets midnight Asia/Shanghai |
| `CARDTALK_PROFILE_MANAGEMENT_ENABLED` | `false`; local development profile-context switch |
| `CARDTALK_SCENARIO_MANAGEMENT_ENABLED` | `false`; local development custom-scene switch |
| `CARDTALK_CUSTOM_CARDS_ENABLED` | `true`; independent local custom-card switch, pricing undecided |
| `CARDTALK_ANALYTICS_ENABLED` | `false`; independent local analytics development switch |
| `CARDTALK_DATA_FILE` | `./data/trial-metadata.json` relative to launch directory |
| `CARDTALK_XFYUN_APP_ID` / `CARDTALK_XFYUN_API_KEY` / `CARDTALK_XFYUN_API_SECRET` | empty; server-only credentials for the selected Xfyun product |
| `CARDTALK_XFYUN_ENGINE` | `super-smart`; optional `voice-clone` consumes an existing authorized multi-style resource |
| `CARDTALK_XFYUN_VCN` | `x6_lingxiaoxuan_pro`; super-smart voice must be enabled in the console |
| `CARDTALK_XFYUN_RESOURCE_ID` | empty; required for voice-clone; must be a multi-style `omni_v1` resource |
| `CARDTALK_XFYUN_ENDPOINT` | empty selects the official endpoint for the engine; override only for controlled testing |
| `CARDTALK_XFYUN_TIMEOUT_SECONDS` | `30`, configurable 1–120 seconds including handshake and complete audio |

The host `api.deepseek.com` receives `thinking:{type:'disabled'}`; other compatible providers are not given this provider-specific option. Keys remain server environment settings, never client parameters. There are no simulated AI results when a provider is unconfigured. The `.env` filename is ignored by Git. `npm run dev:backend` safely loads `backend/.env` as data without shell evaluation. Direct Maven/IDE launches require externally supplied environment variables; Spring Boot itself does not automatically read `.env`.

### Local secrets and launch

Copy `backend/.env.example` to `backend/.env`, edit it locally, then run from the repository root:

```sh
npm run dev:backend
```

The launcher parses dotenv assignments without executing the file as shell code. Do not `source` the file. `.env` is Git-ignored; `.env.example` contains no secrets. Keep keys out of command-line arguments and shell tracing (`set -x`), and do not print environment variables. An IDE can supply the same variables directly; with that external environment configured, `mvn spring-boot:run` from `backend/` also works.

For the Mac browser, keep `CARDTALK_BIND_ADDRESS=127.0.0.1` and use the frontend `/api` proxy. If testing a phone through the frontend LAN URL, the frontend proxy can still reach the loopback backend; direct backend exposure is unnecessary. Explicit `CARDTALK_BIND_ADDRESS=0.0.0.0` opens the backend on network interfaces and is only for a controlled test, not production deployment. The anonymous trial endpoints do not provide production anti-abuse protection.

The DeepSeek defaults follow the official pricing/model page checked during this implementation: https://api-docs.deepseek.com/quick_start/pricing/ . Model availability can change; override `CARDTALK_LLM_MODEL` with a provider-confirmed model for your account rather than guessing a replacement.

## Routes

All routes are under `/api/v1`. JSON errors use `{ "code": "…", "message": "…" }`. No input/provider response bodies are logged by these application handlers.

### GET `/health`

```json
{"status":"ok","llmConfigured":false,"ttsConfigured":false,"ttsEmotionSupported":false,"ttsEngine":"super-smart","promptVersion":"aac-2026-10-12-v3"}
```

### POST `/trial-session`

Body `{ "clientId": "random-installation-identifier" }`. Client ID matches `[A-Za-z0-9_-]{8,128}`. Use a high-entropy installation ID generated once and retained on device. This is an anonymous demo identity, not a verified user or production anti-abuse mechanism.

```json
{"token":"opaque-bearer-token","remaining":20,"limit":20,"resetAt":"2026-10-12T16:00:00Z","capabilities":{"profileManagement":false,"scenarioManagement":false,"customCards":true,"analytics":false}}
```

Creating a session with the same client ID rotates its token and invalidates the old token, but preserves usage and completed-request metadata. On normal app startup, first read the saved token through GET instead of repeatedly rotating it.

### GET `/trial-session`

`Authorization: Bearer <token>`. Same response contract. Unknown/missing tokens return 401 `UNAUTHORIZED`.

### Independent local capabilities

Both trial-session routes return `profileManagement`, `scenarioManagement`, `customCards`, and `analytics`. All four follow independent backend startup environment flags. Analytics runs locally in the Web client; enabling it does not upload communication history or create an analysis API. Restart after changing flags and GET the existing trial session to refresh capabilities. Existing tokens, quota counts and completed-request metadata remain intact.

These flags apply to the local development process, **not** a paid purchase, per-family license or verified identity. No cloud profile/card/scenario CRUD or storage is introduced. All family data is still on the client; only the necessary request snapshot is transiently sent to the selected model service.

When processing expansions, after identity verification and before model invocation or charging:

- Non-null `context.profile` requires `profileManagement`; otherwise 403 `FEATURE_DISABLED`.
- A scene ID starting `custom-scene-` requires `scenarioManagement`; otherwise 403 `FEATURE_DISABLED`.
- Any card ID starting `custom-` requires `customCards`; otherwise 403 `FEATURE_DISABLED`.

The ID prefixes are an agreed local test contract, not proof of data origin or production authorization. Built-in scenes/cards and `core-` cards continue to work. These backend gates do not disable raw local playback or history. When enabled, supplied snapshots are bounded by DTO validation and used as data, without querying a cloud profile database.

### POST `/expansions`

Bearer required. Body:

```json
{
  "requestId":"new-uuid-for-this-expression",
  "inputVersion":1,
  "roomId":"local-room-uuid",
  "cards":[{"id":"i","label":"我"},{"id":"apple","label":"苹果","description":"水果"}],
  "context":{
    "preset":"children",
    "scene":{"id":"kitchen","name":"厨房","description":"家里准备吃饭"},
    "participants":[{"id":"mom","name":"妈妈"}],
    "profile":{"name":"小明","age":8,"language":"普通话","interests":"水果","personality":"慢热","communicationStyle":"短句","currentState":""}
  }
}
```

`preset`: children/adult. `scene` and `profile` may be null/omitted. `participants` is required (empty is valid, max 20). `profile` accepts `name` (100 chars), `age` (0–130), `language` (80), `interests` (500), `personality` (500), `communicationStyle` (500) and `currentState` (500), all optional. The profile-containing example requires its development capability to be enabled. `currentState` is a separately entered current-context field, never inferred from family observations/interests/personality. These background fields are data, cannot override selected cards, and cannot establish the user's present intention. `context.emotion` is optional: `neutral|happy|sad|angry|fearful`. Omitted/null means no extra emotion styling. Non-neutral styling must correspond to selected built-in card IDs (`happy/sad/angry/afraid`); absent or mismatched cards return 400 `INVALID_EMOTION_CONTEXT`. Multiple different selected emotion IDs or a selected negation/refusal card (`not/no/core-no/core-refuse`, or an exact trimmed label `不/不是/不要`, including custom cards) neutralize added styling without flipping it to an opposite emotion. No emotion is inferred from profile, age, scene or generated text. Original card meaning (including emotions and refusal) is retained even when styling is disabled. Cards are ordered, repeated cards are valid, and input requires 1–20 cards. Card `personId` is optional. Card description accepts up to 500 characters. Scene name accepts 100 and its composed snapshot description accepts 2200, covering the saved description, activities and objects. See `Contracts.java` for all length limits.

```json
{
  "requestId":"new-uuid-for-this-expression",
  "inputVersion":1,
  "candidates":[{"id":"candidate-1","text":"我想吃苹果。","sourceCardIds":["i","apple"]}],
  "needsClarification":false,
  "clarification":"",
  "promptVersion":"aac-2026-10-12-v3",
  "remaining":19
}
```

The example is illustrative, not a fallback. Candidate count is 1–3; empty, duplicate, overlong or malformed output is rejected without charging. Source IDs always copy the ordered input, including duplicates. Candidates require human confirmation. Prompt rules preserve refusal, pain and boundaries, but are not a semantic correctness guarantee.

The client should retain its requestId for retries of the same payload. Changing cards/context means a new requestId and inputVersion. On received results, compare inputVersion/roomId lifecycle before presenting them; a stale result must not replace newer input.

- 400 `INVALID_REQUEST`: input validation failed.
- 401 `UNAUTHORIZED`: unknown/rotated token.
- 429 `QUOTA_EXHAUSTED`: daily allowance used.
- 503 `LLM_NOT_CONFIGURED`: missing provider configuration.
- 504 `LLM_TIMEOUT`: upstream deadline exceeded.
- 502 `LLM_UPSTREAM_ERROR` / `LLM_INVALID_RESPONSE`: upstream or candidate validation failed.
- 409 `REQUEST_ID_CONFLICT`: reused request ID with changed payload.
- 409 `REQUEST_ALREADY_COMPLETED`: success metadata exists but the short-lived response is gone. Use the client-saved result or deliberately start a new request.
- 503 `STORAGE_UNAVAILABLE`: quota metadata could not be persisted; no success is reported.

Concurrent retries of one identity are serialized. Only a valid successfully persisted response consumes one unit. The cached response is reused for 5 minutes (global max 256 entries, expired entries are removed on next cache access). Completed request IDs and fingerprints persist across restarts and dates, so an expired replay never charges again. Cache hits report the current remaining quota.

## POST `/speech`

Bearer required. Body `{ "requestId":"speech-uuid", "text":"确认后的表达", "rate":1.0, "emotion":"neutral" }`. `text` is required, max 1100 UTF-16 code units; optional rate range 0.5–2.0. Optional emotion enum is the same as expansion. Omit/null when emotion styling is disabled; no/conflicting emotion cards resolve to neutral in the client. The speech endpoint consumes already-confirmed text and does not re-infer emotion or alter its wording.

The limit accommodates a confirmed raw expression containing 20 custom-card labels of 50 characters plus 19 separating spaces (1019 characters). Text is forwarded unchanged. Xfyun documents a 64 KB total input text limit for super-smart and an 8000-byte limit **after Base64 encoding** for voice-clone. Even conservatively allowing 4 UTF-8 bytes per code unit, 1100 units encode to at most 5868 Base64 bytes, below both provider limits.

Successful response is `audio/mpeg`, binary, `Cache-Control: no-store`. It accepts no client-supplied upstream URL or voice resource. The client controls playback. Xfyun receives a single complete Base64 UTF-8 text message (`status=2`) and returns audio chunks over authenticated WebSocket. The backend assembles ordered `lame`/24 kHz chunks with a 5 MB total limit and rejects incomplete/invalid streams. It does not persist audio or text.

Two explicitly different Xfyun products are supported:

- **super-smart (default):** official endpoint `wss://cbm01.cn-huabei-1.xf-yun.com/v1/private/mcd9m97e6`; configured console-authorized voice. The current official parameter table does not document an emotion-control parameter. Non-neutral requests return **422 `TTS_EMOTION_UNSUPPORTED`** rather than pretending an emotion was applied. Oral rewriting is disabled (`spark_assist=0`, `remain=1`; these controls are documented for x4 voices); no emotion marker or spoken instruction is added to confirmed text.
- **voice-clone (optional):** official endpoint `wss://cn-huabei-1.xf-yun.com/v1/private/voice_clone`; consumes an **existing authorized multi-style resource trained with `engineVersion=omni_v1`**, using `header.res_id`, `vcn=x6_clone` and `languageID=0`. Maps `happy→glad`, `sad→sad`, `angry→angry`, `fearful→afraid` to the documented `parameter.tts.style`. Neutral/omitted emotion does not set style. Ordinary/standard clone resources do not support these styles. No recording, training or clone-management functionality is implemented.

Both use the official HMAC-SHA256 WebSocket URL authentication (host, RFC1123 GMT date, GET request line). Signed URLs and credentials are never exposed to clients or logged. `ttsEmotionSupported` means the required multi-style configuration is present, **not** that a live call or resource entitlement has been verified. Invalid/revoked resource rights are reported by the provider. Real emotional delivery still requires listening tests with the authorized voice.

Missing configuration: 503 `TTS_NOT_CONFIGURED`. Other failures: 502 `TTS_UPSTREAM_ERROR`/`TTS_INVALID_RESPONSE`/`TTS_VOICE_NOT_AUTHORIZED`, 504 `TTS_TIMEOUT`, 409 `TTS_CANCELLED`/`TTS_ALREADY_RUNNING`, 503 `TTS_BUSY`. Current speech does not decrement expansion quota and successful repeated requests are not cached/idempotent; retries may repeat provider charges. Production needs separate speech quotas and verified identities.

## POST `/speech/{requestId}/cancel`

Bearer required, same identity as the original speech request. Returns HTTP 200 `{ "cancelled":true }` when an active operation was interrupted, otherwise `{ "cancelled":false }`. Both outcomes remember cancellation for **120 seconds** (bounded 256 tombstones), so a cancellation arriving before synthesis registration prevents that request from starting. A later attempt under that token/requestId returns 409 `TTS_CANCELLED`; a new playback uses a new requestId. Other identities cannot cancel it. Invalid token returns 401 and invalid requestId returns 400.

Stop/toggle/context-change should first stop local playback and abort its response handling, then send this cancellation best-effort. Fetch abort alone does not reliably interrupt servlet work. Cancellation aborts pending handshake/active WebSocket; deadlines and thread interruption also close it. Cancellation cannot undo provider work or fees already incurred.

## Official Xfyun protocol evidence

- Super-human synthesis and voice list: https://www.xfyun.cn/doc/spark/super%20smart-tts.html
- Multi-style resource synthesis (`style`, `languageID`, `x6_clone`, `res_id`): https://www.xfyun.cn/doc/spark/voiceclone.html
- WebSocket HMAC authentication: https://www.xfyun.cn/doc/spark/general_url_authentication.html

The older Android MSC `EMOT` parameter is not treated as proof of a current WebSocket emotion field. The two product protocols remain distinct. No real provider call or voice training was performed during automated verification.

## Persistence and deployment boundary

Only installation ID, token SHA-256, quota date/count, successful request IDs and request fingerprints are saved. Input text, profiles, scene contents, candidates and speech audio are not persisted. Responses exist transiently in process memory for deduplication; the model/TTS provider receives relevant input and follows its own retention terms. A fingerprint is metadata, not encryption or anonymization.

The JSON file is atomically replaced. Do not run multiple server processes against it. Installation IDs are self-issued, so resetting an identity can bypass its local trial allowance; do not expose this development implementation as a paid public API. Persisted deduplication metadata has no automatic deletion in P1. Account authorization, retention/deletion policy, robust anti-abuse and PostgreSQL transactions are production prerequisites.

## Evidence

Tests use real loopback HTTP fake providers and MockMvc; no live paid model/TTS calls are made. JDK 21 request builders support per-request deadlines and immutable built requests: https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html . Spring Boot 3.5 requirements: https://docs.spring.io/spring-boot/3.5/system-requirements.html . The project pins locally available 3.5.13 rather than claiming it is the latest release.
