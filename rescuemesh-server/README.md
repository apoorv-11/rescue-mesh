# RescueMesh — Sync Server

Spring Boot backend for RescueMesh. It receives signed message batches
from gateway phones, verifies every envelope signature, stores each
record exactly once, materialises per-type views (SOS records,
missing-person cases, announcements), serves the coordinator dashboard
over REST + Server-Sent Events, and handles accounts, email
verification and role management.

## Tech stack

| Area | Technology |
|---|---|
| Framework | Spring Boot 4.1.2, Java 21 |
| Security | Spring Security, Nimbus JWT (HS256), BCrypt |
| Persistence | Spring Data JPA, PostgreSQL 18, Flyway 12.4.0 (migrations V1–V4) |
| Signatures | Ed25519 (envelope verification + signed sync acknowledgements) |
| Mail | Resend Java SDK 4.30.0 (HTTPS API; log-only fallback in dev) |
| API docs | springdoc-openapi 3.2.0 (Swagger UI) |
| Packaging | Docker Compose (app + PostgreSQL) |

## Run

```bash
cp .env.example .env     # adjust secrets (see Configuration)
docker compose up --build
```

- API base: `http://localhost:${APP_PORT}` (default 8080)
- Health: `GET /health` → `{"status":"UP"}`
- Swagger UI: `/swagger-ui.html`
- First boot seeds an admin from `ADMIN_USERNAME` / `ADMIN_PASSWORD`.
- Never run `docker compose down -v` against a database you want to
  keep — it deletes the PostgreSQL volume.

Tests and packaging without Docker:

```bash
./mvnw test        # 58 tests
./mvnw package
```

## Configuration (`.env`)

| Variable | Required | Purpose |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | yes | Database credentials |
| `DB_PORT` | no (5432) | Host port for PostgreSQL |
| `APP_PORT` | no (8080) | Host port for the app |
| `JWT_SECRET` | yes | HS256 signing secret, ≥ 32 characters |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | yes | Seeded admin (first boot only) |
| `RESEND_API_KEY` | no | Enables real email via Resend; empty = codes are written to the server log |
| `RESEND_FROM` | no | Sender identity (default `onboarding@resend.dev` sandbox — can only mail the account owner until a domain is verified) |
| `SYNC_SIGNING_KEY` | **yes, for deployment** | Base64 PKCS#8 Ed25519 private key used to sign sync acknowledgements. If unset, an ephemeral key is generated per restart (WARN in log) and older acknowledgements stop verifying. Generate once, never rotate casually. |
| `PHOTO_DIR` | no | Where verified record photos are stored (Docker: `/data/photos` on a named volume) |

## API overview

Base paths are relative to the app root. All `/records/**` reads and
writes need a Bearer JWT except where noted.

**Public**

| Endpoint | Description |
|---|---|
| `GET /health` | Liveness |
| `GET /sync/pubkey` | Server sync-signing public key + `keyId` (phones verify acknowledgements with it) |
| `POST /auth/register` | Create account (always `CITIZEN`; email verification code issued) |
| `POST /auth/verify-email` | Verify the 6-digit code (15 min validity, hash-stored) |
| `POST /auth/resend-verification` | Issue a new code |
| `POST /auth/login` | JWT login (verified emails only) |
| `POST /auth/device` | Device token for gateway sync (bound devices) |

**Sync (gateway phones)**

| Endpoint | Description |
|---|---|
| `POST /sync/batch` | Batch of signed envelopes → per-message results + one signed `serverAck` per accepted message. Idempotent: resubmitted messages return `ALREADY_EXISTS` and merge hop traces. |
| `POST /sync/photo?messageId=…` | Upload a record's photo (raw JPEG). Stored only if size + SHA-256 match the signed `photoRef`. |
| `GET /records/photos/{messageId}` | Download a stored photo |

**Dashboard reads**

| Endpoint | Description |
|---|---|
| `GET /records/sos` | SOS records, newest first (cap 200) |
| `GET /records/missing` | Missing-person **case view**: a missing report and its found update are one case (`OPEN` / `FOUND_REPORTED`), with photos and the persisted coordinator review |
| `GET /records/announcements` | Announcements |
| `GET /records/messages` | Raw stored envelopes |
| `GET /records/audit` | Latest sync-batch audit rows |
| `GET /records/stream` | SSE stream (`records-changed` / `photo-changed` events) for live dashboard updates |

**Dashboard writes (COORDINATOR / ADMIN)**

| Endpoint | Description |
|---|---|
| `POST /records/announcements` | Publish an announcement directly |
| `POST /records/missing/{messageId}/review` | Persist a case review (`CONFIRMED` / `REJECTED`). A review never changes the sync-derived case status and never closes a case by itself. |

**Roles**

| Endpoint | Description |
|---|---|
| `POST /roles/apply`, `GET /roles/my-applications` | Apply for VOLUNTEER / COORDINATOR |
| `GET/POST /admin/role-applications…` | Admin review; approval issues a one-time code `RM-XXXXX-XXXXX` (7 days, emailed) |
| `POST /admin/role-codes` | Admin-issued code (the only path to ADMIN) |
| `POST /profile/role-code` | Redeem a code |
| `POST /profile/bind-device` | Bind this account to a device id |

A ready-to-run Postman collection covering the main flows is in
`rescuemesh.postman_collection.json` (22 requests).

## Sync verification pipeline

Every envelope in a batch passes, in order: structural validation →
device-key binding → Ed25519 origin-signature check (the origin signs
exactly `messageId, eventType, originDeviceId, createdAt, expiresAt,
priority, hopLimit, payload`; hop entries are self-signed with a
previous-signature chain) → time-window checks → idempotent insert.
Accepted SOS / missing / announcement envelopes materialise into their
per-type tables; one `SyncAudit` row is written per batch, and a
`records-changed` event is published to SSE subscribers.

## Project structure

```
src/main/java/com/rescuemesh/server/
├── auth/       Registration, verification, JWT, device tokens
├── config/     Security (JWT, CORS), mail, data seeder
├── domain/     User, Message, SOSRecord, MissingPerson, Announcement, SyncAudit, …
├── mail/       MailService — Resend implementation + log fallback
├── repository/ Spring Data repositories
├── sync/       Batch sync, records + dashboard controllers, photo storage, SSE
└── web/        Profile, roles, admin, health endpoints
src/main/resources/db/migration/   Flyway V1–V4
```