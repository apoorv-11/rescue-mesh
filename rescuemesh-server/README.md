# RescueMesh Server — Prompt 3 skeleton (Track B)

Spring Boot backend skeleton for RescueMesh: the six server entities on
PostgreSQL (schema owned by Flyway), JWT authentication (username/password
login + device token), role-based security, Swagger UI and a public health
endpoint. This is the Track B half of **Gate 1**. **Prompt 7 adds the sync
API** (`POST /sync/batch`, `GET /sync/pubkey`, `/records/*` read endpoints —
see [Server sync (Prompt 7)](#server-sync-prompt-7) below). The gateway
(Android side) and the dashboard are later prompts — they are intentionally
not here.

- **Stack:** Spring Boot 4.1.2, Java 21, Spring Security + Nimbus JWT
  (HS256), Spring Data JPA, Flyway, PostgreSQL 18.
- **Package:** `com.rescuemesh.server`

## What is inside

| Area | Contents |
|---|---|
| Entities (`domain/`) | `User`, `Message` (the fixed 11-field envelope; `hopTrace` is a TEXT column holding the JSON-serialised hop list in V1), `SOSRecord`, `MissingPerson`, `Announcement`, `SyncAudit`, plus `Role` and `EventType` enums; auth v2 adds `EmailVerification`, `RoleApplication`, `RoleAccessCode` |
| Schema | `db/migration/V1__init.sql` creates the six core tables; `V2__auth_roles.sql` adds `users.email_verified` + the three auth v2 tables; JPA runs `ddl-auto: validate` |
| Auth (`auth/`) | `POST /auth/register` (always CITIZEN, email required, verification code issued), `POST /auth/verify-email`, `POST /auth/resend-verification`, `POST /auth/login` → JWT (verified emails only), `POST /auth/device` → JWT for a bound device; BCrypt password hashes; a `DEVICE_TOKEN_ISSUED` row is written to `sync_audit` per device token |
| Roles (v2, `web/`) | `POST /roles/apply`, `GET /roles/my-applications`, admin review at `GET/POST /admin/role-applications…`, direct codes at `POST /admin/role-codes`, redemption + device binding at `POST /profile/role-code` / `POST /profile/bind-device` |
| Mail (`mail/`, `config/MailConfig`) | `MailService` with two implementations: log-only (dev default) and Resend HTTPS API when `RESEND_API_KEY` is set |
| Sync (`sync/`) | Prompt 7: `POST /sync/batch` (per-item structural → key-binding → Ed25519 signature → future/expired → idempotent insert pipeline, per-type materialisation, one `SyncAudit` row per batch, signed `serverAck`s), public `GET /sync/pubkey`, and the `/records/*` read endpoints. Includes the Java canonicaliser (byte-identical to Android's `CanonicalJson`) and the `SYNC_SIGNING_KEY` ack-signing key |
| Security (`config/SecurityConfig`) | Stateless JWT resource server; `/health`, `/sync/pubkey`, `/auth/**` and the API docs are public, `/admin/**` needs ADMIN, everything else needs a token. The `role` claim becomes the `ROLE_*` authority |
| Seed (`config/DataSeeder`) | Creates the first ADMIN on boot if absent (default `admin` / `admin123`, override via env) |
| Probes (`web/`) | `GET /health` (public), `GET /me` (any token — returns token claims), `GET /admin/ping` (ADMIN only — proves role security) |
| Docs | Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs` (springdoc) |

### `/auth/device` semantics (read this)

A device token is issued to the **user whose account has that `deviceId`
bound** (set at registration). For the skeleton, the deviceId alone is the
credential — a phone returning from the mesh may hold nothing else. That is
a deliberate skeleton simplification, **not** the final trust model: the real
device credential is the Ed25519 key proof that arrives with envelope
signing (Prompt 4), which replaces this. Unknown deviceId → 401.

### Roles at registration (fixed in auth v2)

The skeleton originally honoured the requested role at registration. That
simplification is **removed**: registration now always creates a CITIZEN.
Privileged roles are granted only through the application → approval →
one-time access code flow described in
[Accounts, email verification & role access (v2)](#accounts-email-verification--role-access-v2)
below.

## Run it

### Option A — Docker Compose (the Gate 1 route)

```bash
cd rescuemesh-server
cp .env.example .env        # defaults work locally; change secrets for anything real
docker compose up --build
```

PostgreSQL 18 starts first (named volume `pgdata`), the app waits for its
healthcheck, Flyway applies V1 on first boot, and the admin is seeded.

### Option B — Local Maven + your own PostgreSQL

```bash
# PostgreSQL running locally with database/user rescuemesh (or set DB_* env)
./mvnw spring-boot:run
# configuration is env-driven: DB_URL, DB_USERNAME, DB_PASSWORD,
# JWT_SECRET (>= 32 chars), ADMIN_USERNAME, ADMIN_PASSWORD, PORT
```

## Verify — Gate 1 backend criteria

With the app on `http://localhost:8080`:

```bash
# 1. Health is public → 200 {"status":"UP",...}
curl http://localhost:8080/health

# 2. Flyway applied V1 + V2 (app log shows the migrations applied and the
#    tables exist: users, message, sos_record, missing_person, announcement,
#    sync_audit, email_verification, role_application, role_access_code)

# 3. Register (email required since auth v2) → verify email → login → JWT.
#    Dev mode: the 6-digit code is in the app log ("[DEV MAIL ...]").
curl -X POST http://localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"apoorv","email":"apoorv@example.org","password":"secret123","deviceId":"device-001"}'
curl -X POST http://localhost:8080/auth/verify-email -H 'Content-Type: application/json' \
  -d '{"username":"apoorv","code":"<6-digit code from the mail/log>"}'   # → returns a token directly
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"apoorv","password":"secret123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 4. The JWT unlocks protected endpoints; without it they return 401
curl http://localhost:8080/me -H "Authorization: Bearer $TOKEN"
curl -i http://localhost:8080/me            # → 401

# 5. Device token for the bound device
curl -X POST http://localhost:8080/auth/device -H 'Content-Type: application/json' \
  -d '{"deviceId":"device-001"}'

# 6. Role security: seeded admin reaches /admin/ping, the citizen token gets 403
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl http://localhost:8080/admin/ping -H "Authorization: Bearer $ADMIN_TOKEN"

# 7. Swagger UI opens in a browser:
#    http://localhost:8080/swagger-ui.html   (spec: /v3/api-docs)
```

## Accounts, email verification & role access (v2)

Auth v2 replaces the skeleton's open registration with an account model
where **an account is an official record, not a master key**: self-service
can only ever produce a verified CITIZEN, and every privileged role is
granted by an admin through a one-time code.

### Flows

**Sign-up & verification**

1. `POST /auth/register` with username, **email (required)**, password and
   optionally the phone's `deviceId` (the app sends its originDeviceId).
   Any requested role is ignored — the account is created as `CITIZEN`,
   unverified.
2. The server generates a 6-digit code (only its SHA-256 hash is stored;
   15-minute expiry, attempt counter) and emails it via `MailService`.
3. `POST /auth/verify-email {username, code}` marks the email verified and
   returns a login token (auto-login). `POST /auth/resend-verification
   {username}` issues a fresh code and invalidates the old ones.
4. `POST /auth/login` with a correct password but an **unverified** email
   → `403 {"error":"email not verified"}`. Duplicate username or email →
   409; missing/invalid email → 400.

**Role upgrade (VOLUNTEER / COORDINATOR)**

1. A verified user applies: `POST /roles/apply {requestedRole,
   organization, reason}` (ADMIN → 400; a second application while one is
   PENDING → 409). `GET /roles/my-applications` lists their own.
2. An admin reviews `GET /admin/role-applications?status=PENDING` and
   either rejects (`POST /admin/role-applications/{id}/reject {note}`) or
   approves (`POST /admin/role-applications/{id}/approve`).
3. Approval generates a **role access code** — `RM-XXXXX-XXXXX` from an
   unambiguous alphabet (no 0/O/1/I), 7-day expiry, only the SHA-256 hash
   stored — and emails it to the user. The plaintext is also returned
   **once** in the admin's approve response: in the offline demo the mail
   service may be log-only, so the admin needs a way to hand the code over
   in person. It is never stored or shown again.
4. The user redeems it: `POST /profile/role-code {code}` → their role
   changes and a **fresh JWT** (with the new role claim) is returned.
   Reusing the code → 409; expired → 400.
5. An admin can also skip the application: `POST /admin/role-codes
   {username, role}` creates a direct code for **any** role, including
   ADMIN — the only path that can mint a new admin.

**Device binding** — `POST /profile/bind-device {deviceId}` binds a device
to the signed-in account (reinstall / new phone); 409 if the deviceId
belongs to a different user.

### Endpoint table

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /auth/register` | public | Create an unverified CITIZEN account; sends a verification code |
| `POST /auth/verify-email` | public | Verify email with the 6-digit code → token |
| `POST /auth/resend-verification` | public | Issue a fresh verification code |
| `POST /auth/login` | public | Password login → JWT (verified emails only, else 403) |
| `POST /auth/device` | public | JWT for the user a deviceId is bound to (skeleton trust model, see above) |
| `POST /roles/apply` | token, verified | Apply for VOLUNTEER / COORDINATOR |
| `GET /roles/my-applications` | token | Caller's applications |
| `GET /admin/role-applications?status=` | ADMIN | Review queue (default PENDING) |
| `POST /admin/role-applications/{id}/approve` | ADMIN | Approve → access code emailed + returned once |
| `POST /admin/role-applications/{id}/reject` | ADMIN | Reject with a review note |
| `POST /admin/role-codes` | ADMIN | Direct access code for any user/role |
| `POST /profile/role-code` | token | Redeem an access code → fresh JWT with the new role |
| `POST /profile/bind-device` | token | Bind a deviceId to the caller's account |

### Mail — Resend, and dev mode without a key

Mail goes through **[Resend](https://resend.com)** (official
`resend-java` SDK, HTTPS API) — chosen over SMTP because several hosts
block outbound SMTP ports (e.g. Render free services cannot use ports
25/465/587), while HTTPS works everywhere.

To enable real mail:

1. Create a Resend account → **API Keys** → create a key.
2. Set `RESEND_API_KEY` in the environment (see `.env.example`); optionally
   set `RESEND_FROM` (default `RescueMesh <onboarding@resend.dev>`).
3. Restart the app — `MailConfig` picks `ResendMailService` whenever the
   key is non-blank. If a send fails, the error is logged and the flow
   continues; the user can request a fresh code.

**SANDBOX RESTRICTION: with the default sender
`onboarding@resend.dev`, Resend delivers ONLY to the email address of the
Resend account owner.** To email testers and other users you must verify
a domain in Resend (**Domains → add domain → add the DKIM/SPF DNS
records**) and set `RESEND_FROM` to an address on that domain. The free
plan includes 3,000 emails/month, max 100/day.

Dev mode is unchanged: when `RESEND_API_KEY` is **unset/blank**, no real
email exists — `LogMailService` writes each code to the server log at
INFO, clearly marked `[DEV MAIL — no real email sent]`, and the app still
works end to end (read the code out of `docker compose logs app`).

## Server sync (Prompt 7)

The sync API is how a gateway phone uploads the envelopes it collected over
the mesh, and how the dashboard/Postman read them back. The wire contract is
the batch contracts note §5; all timestamps are epoch-millisecond integers,
and envelope signatures are verified over the canonical JSON signed set
(8 fields — `hopCount`, `hopTrace` and `signature` are **not** signed,
because relay mutates them) with the project's canonicaliser, byte-identical
to Android's `CanonicalJson`.

### Endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /sync/pubkey` | **public** | `{keyId, publicKey}` of the server sync signing key — phones fetch this to verify `serverAck`s |
| `POST /sync/batch` | token | Upload `{deviceId, items: [{envelope, originPublicKey}]}`; per-item results with signed acks (below) |
| `GET /records/sos` | token | Materialised SOS rows, newest first, cap 200 |
| `GET /records/missing` | token | Materialised missing-person rows, newest first, cap 200 |
| `GET /records/announcements` | token | Announcement rows, newest first, cap 200 |
| `GET /records/messages` | token | Stored envelopes (the 11 contract fields; `hopTrace`/`payload` as JSON values, times as epoch ms), newest first, cap 200 |
| `POST /sync/photo?messageId=<id>` | token | Raw JPEG body; accepted only when size + SHA-256 match the synced envelope's signed `photoRef` — stored under `${PHOTO_DIR:./photos}/<messageId>.jpg` (photo design, below) |
| `GET /records/photos/{messageId}` | token | The stored photo for a record (200 `image/jpeg`, or 404) |

A ready-made Postman collection for all of this (plus auth v2 and roles)
lives at **`rescuemesh.postman_collection.json`** in this directory —
import it, set `baseUrl`, and paste a login token into the `token` variable
(`adminToken` for the `/admin` requests).

### `POST /sync/batch` — per-item pipeline

Each item is checked **in this order**; the first failure decides its result,
and one item's failure never rolls back the rest of the batch (each item is
stored in its own transaction):

1. **Structural** — all 11 envelope fields present with the right types,
   integer times, `eventType` in the enum, priority 1–3,
   `hopCount <= hopLimit`, signature decodes to 64 bytes, `originPublicKey`
   decodes to 32 bytes, payload is an object, hopTrace is an array
   → `REJECTED` / `"malformed"`.
2. **Key binding** — `hex(SHA-256(originPublicKey)) == originDeviceId`
   → `REJECTED` / `"key-mismatch"`.
3. **Signature** — JDK Ed25519 verify of the envelope signature over the
   canonical signed set → `REJECTED` / `"bad-signature"`.
4. **Time** — `createdAt > serverNow + 24h` → `REJECTED` /
   `"future-createdAt"`; `expiresAt <= serverNow` → `REJECTED` / `"expired"`.
5. **Insert** by `messageId` (unique): new → **`ACCEPTED`** and the per-type
   row is materialised (SOS → `SOSRecord` OPEN with lat/lon parsed from the
   payload location strings and `note = description`; MISSING_PERSON →
   `MissingPerson` with name fallback `"Unknown"`; ANNOUNCEMENT →
   `Announcement` with title = category, body = text; other types get the
   `Message` row only). Existing → **`ALREADY_EXISTS`** and the hop traces
   are **merged**: union keyed by `(i, deviceId)`, existing entry wins on
   conflict, only valid incoming entries merge, result sorted by
   `(i, deviceId)`, `hopCount := merged size − 1`.

Response: `{serverTime, results: [{messageId, status, reason?, serverAck?}]}`.
One `SyncAudit` row per batch records
`accepted=<n> alreadyExists=<n> rejected=<n>`.

### Acks and the sync signing key

For `ACCEPTED` / `ALREADY_EXISTS` results the response carries a
`serverAck` — `{messageId, status, issuedAt, signature}` where the signature
is Ed25519 over the canonical JSON of `{issuedAt, messageId, status}`, made
with the **server sync key**. A phone that holds the key from
`GET /sync/pubkey` can therefore prove the server accepted a record, and can
gossip that proof to peers (HELLO `serverAcked`).

The key comes from env **`SYNC_SIGNING_KEY`**: standard Base64 of a PKCS#8
DER Ed25519 private key. `keyId` (served by `/sync/pubkey`) is the first 16
hex characters of hex(SHA-256(raw public key)).

**Dev warning:** when `SYNC_SIGNING_KEY` is unset/blank the server generates
an **ephemeral** keypair at startup and logs a clear WARN. That is fine
locally, but acks signed before a restart will not verify after it — set the
env var for any real deployment. Generate one with OpenSSL (verified against
this server: the loader derives the public key from the seed when the PKCS#8
has no public-key field, as OpenSSL's output does not):

```bash
openssl genpkey -algorithm ed25519 -out sync-signing-key.pem
export SYNC_SIGNING_KEY=$(openssl pkcs8 -topk8 -nocrypt -in sync-signing-key.pem -outform DER | base64 -w0)
# macOS base64 wraps lines instead: ... | base64 | tr -d '\n'
# sanity check the matching public key (Base64url, no padding) with:
openssl pkey -in sync-signing-key.pem -pubout -outform DER | tail -c 32 | base64 | tr '+/' '-_' | tr -d '='
```

Keep `sync-signing-key.pem` out of git, like every other secret.

### Photos and the case link (photo transfer design — Flyway V3)

Full photos (up to 500 KB) never ride inside the envelope or the batch
JSON. The envelope payload carries only a signed `photoRef`
(`{photoId = the record's messageId, sha256, sizeBytes, mime}`) next to
the small `photoThumb`; the JPEG itself uploads separately:

- `POST /sync/photo?messageId=<id>` (token, raw `image/jpeg` body): the
  server looks up the stored envelope and accepts the bytes **only** when
  their size and SHA-256 match the signed `photoRef` — mismatch → 400 and
  nothing is stored; unknown `messageId` → 404; a record without a
  `photoRef` → 400. Verified files land in `${PHOTO_DIR:./photos}` as
  `<messageId>.jpg` (written atomically; in Docker, `PHOTO_DIR` points at
  the `photos` named volume). Re-uploading the identical photo → 200
  `ALREADY_EXISTS` (idempotent).
- `GET /records/photos/{messageId}` (token) streams the stored JPEG, or
  404. The Found record's photo is fetched the same way, by its own
  messageId — that pair is the dashboard's side-by-side case view.

Missing ↔ Found are **one case** on the server. Flyway **V3** adds
`missing_person.message_id` (the case key), `found_message_id`,
`found_at`, and moves `status` to `OPEN` → `FOUND_REPORTED` (V1/V2
values migrate: `MISSING` → `OPEN`, `FOUND` → `FOUND_REPORTED`). When a
PERSON_FOUND_UPDATE materialises, its payload `caseId` links it onto
the case; if the case has not synced yet, the link is applied when the
MISSING_PERSON arrives instead (both orders work, and re-syncs are
idempotent). The link points at the **newest** found update for the
case. It is best-effort and informational — a coordinator reviews the
photos side-by-side and closes the case; nothing auto-closes.
`GET /records/missing` returns the case view: the record, `photoRef`
(null when the record carries no full photo), `hasPhoto` (a verified
photo file exists on this server), `status`, `foundMessageId`,
`foundAt`.

### Honest limits (this batch)

- **Hop entries are flagged, not fatal.** An invalid hop entry (bad chain,
  bad signature, `deviceId` not the hash of its `pk`) never rejects the
  record: the envelope is still accepted on its origin signature alone.
  Invalid entries are only excluded from hop merging — the server does not
  reject, rewrite or annotate stored traces beyond that.
- **No role-certificate check.** Doc 2 mentions verifying a coordinator role
  certificate on ANNOUNCEMENT / PERSON_FOUND_UPDATE records. The project's
  cert system is a design document only, so in this batch such records are
  accepted without any role check — any authenticated uploader can sync an
  announcement. Do not treat the announcement feed as trusted until certs
  exist.
- **No Flyway V3** was needed for the Prompt 7 batch itself: the V1
  `message` table already stores the whole envelope and the per-type
  tables exist. (V3 arrived later, for the case link — see above.)

### Seeded-admin exemption

Login requires a verified email, but the seeded admin has no mailbox — so
`DataSeeder` creates it with `email_verified = true`, and Flyway V2 marks
pre-existing ADMIN rows verified for the same reason. This is the one
exemption, and it applies only to the bootstrap admin.

### Security note

Self-registration can no longer create privileged accounts (the skeleton
behaviour the v1 README flagged): requested roles are ignored, every new
account is an unverified CITIZEN, and unverified accounts cannot sign in
at all. Role changes happen only by redeeming a single-use, expiring,
hash-stored code that an admin issued — there is no endpoint that edits a
user's role directly.

## Tests

```bash
./mvnw test
```

Tests run on an **H2 in-memory profile** (`application-test.yml`, Flyway off,
Hibernate creates the schema) — PostgreSQL is only the runtime database, and
the PostgreSQL-dialect migrations are exercised by the Docker route above,
not by these tests. `AuthFlowTests` covers the Gate 1 criteria end to end
plus the auth v2 sign-up rules (role ignored at registration, duplicate
email 409, login 403 before verification, wrong code 400, resend flow);
`RoleFlowTests` covers the role flow (apply → admin queue → approve →
redeem → fresh token carries the new role, double redeem 409, ADMIN
application 400, citizen 403 on the admin queue, unverified user cannot
apply, direct admin codes, bind-device happy path + 409); plus a
context-loads test. Tests capture outgoing mail through a
`CapturingMailService` test bean (`TestMailConfiguration`), so the codes
are read exactly where a user's mailbox would receive them.
`MailConfigTests` covers provider selection (log service without a key or
with a blank one, Resend service with a key) by direct bean construction.
Prompt 7 adds `CanonicalJsonTests` (the §3 fixed test vector: canonical
envelope + hop-0 strings byte-exact, both signatures verify under the JDK,
seed → public key derivation), `SyncSigningKeyTests` (ephemeral mode, JDK
PKCS#8, OpenSSL-style PKCS#8 without a public-key field) and `SyncFlowTests`
(vector batch → ACCEPTED with an ack that verifies against `/sync/pubkey`,
repeat → ALREADY_EXISTS + hop merge, bad-signature / key-mismatch /
future-createdAt rejections, 401s, SyncAudit counts, SOS materialisation;
the server clock is pinned in that one test class — see its javadoc).
Testcontainers was not used (no Docker in the build environment).
Separately, `V1__init.sql` was parse-validated against the real PostgreSQL
grammar (pglast / libpg_query — 9 statements, clean), and so was
`V2__auth_roles.sql` (9 statements, clean). An embedded-PostgreSQL
test was attempted and removed: this build VM's sandbox intercepts localhost
sockets, so no local database server can be reached from it (see below).

## Versions — verified 14 Nov 2026 from official sources

| Item | Version | Verified from |
|---|---|---|
| Spring Boot | 4.1.2 (latest stable GA; 4.2.0-RC2 is an RC, not used) | docs.spring.io (per kickoff check) + Maven Central POM exists |
| Boot 4 starter names | `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-flyway`, `spring-boot-starter-test`, `spring-boot-starter-webmvc-test` | Each 4.1.2 POM fetched from Maven Central; the Boot 4 modular naming replaces `starter-web` / `starter-oauth2-resource-server` (the old names exist only as aliases) |
| Test annotation move (Boot 4) | `@AutoConfigureMockMvc` now in `org.springframework.boot.webmvc.test.autoconfigure` | Inspected the actual `spring-boot-webmvc-test-4.1.2.jar` class list |
| Flyway | 12.4.0, managed by the Boot BOM; `flyway-database-postgresql` also BOM-managed | `spring-boot-dependencies-4.1.2.pom` |
| Resend SDK (auth v2 mail) | `com.resend:resend-java` **4.30.0** — latest stable, **not** BOM-managed (pinned explicitly in the pom) | Maven Central `maven-metadata.xml`: `<latest>` and `<release>` both 4.30.0 (checked 2026-11-14); the 4.30.0 jar was resolved by Maven and its API inspected (`Resend`, `Emails.send(CreateEmailOptions)`, `CreateEmailResponse.getId()`) |
| springdoc-openapi | 3.2.0 (`springdoc-openapi-starter-webmvc-ui`) — 3.x is the Boot 4 branch, **not** BOM-managed | Maven Central metadata (`<release>3.2.0</release>`) + springdoc-openapi GitHub README compatibility table (Boot 4.x → 3.x) |
| PostgreSQL (Docker) | 18 (`postgres:18-alpine`) — latest stable major; 19 is still an RC (GA planned 19 Nov 2026) | postgresql.org roadmap + release reporting, Nov 2026 |
| Java | 21 (project target; Boot 4 baseline is 17) | pom `java.version` |
| Build verification JDK/Maven | Temurin 21.0.13, Maven 3.9.16 | downloaded from Adoptium / Maven Central for the verification run |

## Verification status (honest)

Verified on 2026-11-14 in the build VM (Temurin JDK 21.0.13, Maven 3.9.16,
both fetched for the run because the VM ships no JDK):

- ✅ `mvn -B package` → **BUILD SUCCESS**, executable jar produced
  (`target/rescuemesh-server-0.1.0-SNAPSHOT.jar`).
- ✅ `mvn test` → **5/5 tests pass**: `AuthFlowTests` (4 — health public,
  anonymous → 401, register → login → JWT unlocks `/me`, duplicate → 409,
  wrong password → 401, citizen → 403 on `/admin/ping`, device token for a
  bound device + 401 for an unknown one, seeded admin reaches `/admin/ping`)
  and the context-loads test (1). These run on H2 as described above.
- ✅ `V1__init.sql` parse-validated against the real PostgreSQL grammar
  (pglast / libpg_query): 9 statements, no errors. Entity mappings were
  additionally exercised end to end via Hibernate create-drop on H2.
- ⚠️ **Not verified here — Docker / real PostgreSQL execution.** This VM has
  no Docker, and its sandbox blocks Java loopback sockets (the same limit
  that blocks the Android Gradle daemon), so an embedded PostgreSQL could
  not accept connections either. Therefore Flyway actually applying V1 to
  PostgreSQL 18, Hibernate `validate` against that schema, and the whole
  `docker compose up` route are **proven only by construction** (grammar
  check + BOM-managed Flyway 12.4 + standard config) until someone runs
  Option A on a normal machine — which is exactly Gate 1 step 2 in the
  verify list above. Do that before calling Gate 1 passed.
- ⚠️ The Dockerfile's base-image tags (`maven:3.9-eclipse-temurin-21`,
  `eclipse-temurin:21-jre`, `postgres:18-alpine`) are standard Docker Hub
  tags but the image build itself is untested for the same reason.

### Auth v2 (added later on 2026-11-14)

- ✅ `mvn test` → **16/16 tests pass** (`AuthFlowTests` 8, `RoleFlowTests`
  7, context-loads 1) and `mvn -B package` → **BUILD SUCCESS**, on the same
  H2 test profile. The v2 entity mappings are exercised end to end via
  Hibernate create-drop on H2, like V1's were.
- ✅ `V2__auth_roles.sql` parse-validated with pglast (PostgreSQL grammar):
  9 statements, no errors.
- ✅ `spring-boot-starter-mail:4.1.2` resolved from Maven Central by Maven
  itself; its POM pulls `spring-boot-mail` (the name is unchanged in Boot 4).
- ⚠️ Same caveat as V1: Flyway applying V2 to a real PostgreSQL and
  Hibernate `validate` against it are proven only by construction in this
  VM — run `docker compose up --build` on a normal machine; on an existing
  V1 database Flyway will apply V2 on top (your existing admin stays able
  to log in — V2 marks ADMIN rows verified; your other existing users will
  need to verify their email once via resend-verification).
- ⚠️ Real SMTP delivery is untested (no mail server here); the SMTP path
  is standard `JavaMailSender` usage, and dev mode (log codes) is what the
  tests exercise through the capturing test bean. **Superseded the same
  day** — see the Resend subsection below.

### Sync API — Prompt 7 (added 2026-11-14)

- ✅ `mvn test` → **34/34 tests pass** (the 19 pre-existing, plus
  `CanonicalJsonTests` 6, `SyncSigningKeyTests` 3, `SyncFlowTests` 6) and
  `mvn -B package` → **BUILD SUCCESS**, same H2 test profile, Temurin
  21.0.13 / Maven 3.9.16.
- ✅ The §3 cross-side test vector passes end to end on the server: the Java
  canonicaliser reproduces both canonical strings byte-for-byte, both
  Ed25519 signatures verify under the JDK verifier, and the batch endpoint
  returns ACCEPTED with an ack that verifies against `GET /sync/pubkey`.
  One wrinkle, documented in `SyncFlowTests`: the vector was created in
  Nov 2023, so under the real clock its `expiresAt` is past and check 4
  would reject it as expired — the flow tests pin the server `Clock`
  (production default is the system clock) to just after the vector's
  `createdAt` so the genuine pipeline, time checks included, is exercised.
- ✅ The README's OpenSSL key recipe was verified end to end in this VM:
  a key generated with `openssl genpkey` + `pkcs8 -outform DER` loads via
  `SYNC_SIGNING_KEY`, and the public key the server derives for it matches
  `openssl pkey -pubout` exactly.
- ⚠️ Same standing caveat as everything else here: the Docker/PostgreSQL
  route is untested from this VM (no Docker, loopback blocked) — the sync
  code is exercised on H2 via Hibernate create-drop, and no V3 migration
  exists to apply.

### Mail provider — SMTP replaced by Resend (2026-11-14)

- ✅ `SmtpMailService`, the `spring-boot-starter-mail` dependency and all
  `spring.mail.*` / `MAIL_*` configuration are removed; mail now goes
  through `ResendMailService` (official `com.resend:resend-java` SDK
  **4.30.0**, the latest stable per Maven Central metadata) whenever
  `RESEND_API_KEY` is non-blank, else `LogMailService` as before.
- ✅ `mvn test` and `mvn -B package` re-run after the swap (see the test
  suite: the 16 flow/context tests unchanged, plus `MailConfigTests`
  provider-selection tests).
- ⚠️ Real Resend delivery is untested from this VM (no API key here);
  sending is exercised only through the capturing test bean and provider
  selection. First real send happens at deployment — watch the app log
  for `Mail via Resend … accepted (id: …)` or a logged failure (failures
  never break register/apply flows).
