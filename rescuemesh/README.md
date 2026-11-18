# RescueMesh — Android App

The field app of RescueMesh. It sends and relays emergency reports
(SOS, safety check-ins, missing-person reports, found-person updates,
announcements, resource updates) over an offline phone-to-phone mesh,
stores everything locally first, and syncs to the RescueMesh server
whenever any connectivity appears.

- **SOS and "I Am Safe" are never gated** — no account, no login, no
  network required, at any time.
- Package: `com.rescuemesh` · min SDK 26 (Android 8.0) · target SDK 36

## Tech stack

| Area | Technology |
|---|---|
| Language / UI | Kotlin 2.4.21, Jetpack Compose (BOM 2026.09.00), Material 3 |
| Build | AGP 9.5.1, Gradle 9.6.0, JDK 21 |
| Local storage | Room 2.8.5 (database v3, migrations 1→2→3) |
| Mesh transport | Google Nearby Connections 19.5.1 (`P2P_CLUSTER`), behind a `MeshTransport` interface |
| Crypto | Tink Android 1.24.0 — Ed25519 identity, keyset wrapped by an Android Keystore AES-GCM key |
| Networking | Retrofit 3.0.0 + kotlinx-serialization 1.13.0 |
| Async | Kotlin coroutines 1.11.0 |
| Tests | JUnit 4.13.2 (JVM unit tests, 203 tests) |

## How it works

**Identity.** On first launch the app generates an Ed25519 keypair with
Tink (stored encrypted in the Keystore). `originDeviceId` is the lowercase
hex SHA-256 of the raw 32-byte public key; the mesh advertises as
`RM-<first 12 chars of the id>`.

**Message envelope (fixed contract, 11 fields):**
`messageId, eventType, originDeviceId, createdAt, expiresAt, priority,
hopLimit, hopCount, hopTrace, payload, signature`. The origin signs
exactly 8 fields — `messageId, eventType, originDeviceId, createdAt,
expiresAt, priority, hopLimit, payload`. Each relay appends its own
self-signed hop entry chained to the previous hop's signature;
`hopCount = hopTrace.size − 1`.

**Relay.** Phones exchange `HELLO / MSG / ACK / DONE` packets as UTF-8
JSON (30 KB guard). A record is persisted *before* it is acknowledged.
The outgoing queue is ordered priority-first (1 Critical, 2 High,
3 Normal), then oldest first; default `hopLimit` is 8. A foreground
service (`RelayService`) keeps the mesh running. TTLs: SOS 24 h,
safety check-in 12 h, missing/found 72 h, resource 12 h, announcement =
its `validUntil`.

**Photos (missing-person cases).** Photos are capped at 500 KB
(512,000 bytes). The envelope carries only a signed reference
(`photoRef`: photo id, SHA-256, size) plus a small thumbnail; the full
photo travels separately in 16 KiB `PHOTO_CHUNK` / `PHOTO_ACK` rounds
and is stored only after its size and SHA-256 verify. The UI shows
delivery state per record ("Full photo saved ✓", "Photo delivered ✓").

**Gateway sync.** When a phone gets connectivity it uploads queued
records in batches of 50 to `POST /sync/batch`. It uses a device token
(`POST /auth/device`) — sync never needs a user login. A record is
marked `SERVER_SYNCED` only when the server's signed acknowledgement
for that exact message verifies against the cached server key
(`GET /sync/pubkey`). Sync triggers on network-available callbacks,
app start, relay-service start, and a manual "Sync now".

**Accounts (optional layer).** Signup sends the device id automatically
and always creates a `CITIZEN` account; email verification is required
before login. `VOLUNTEER` / `COORDINATOR` are obtained by applying in
the profile, waiting for admin review, and redeeming the emailed
one-time code. The server URL is configurable under
*Account → Server & sync* (used for LAN testing; release builds carry
the production URL as default).

## Project structure

```
app/src/main/java/com/rescuemesh/
├── crypto/     Device identity, canonical JSON, envelope + hop signing
├── data/       Room database, entities, DAOs, repositories
├── domain/     Envelope model, TTL + priority rules, photo store
├── mesh/       MeshTransport interface, Nearby transport, RelayEngine
├── net/        Server config, API client, gateway sync
└── ui/         Compose screens (Home, Records, Mesh, Account, forms)
```

## Build and run

Prerequisites: Android Studio (latest stable), JDK 21, Android SDK 37.

```bash
# Unit tests (JVM)
./gradlew testDebugUnitTest

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

`local.properties` (with `sdk.dir`) is machine-local and is not committed.

## Testing on devices

Mesh features need **two physical phones** within ~10–30 m — emulators
cannot run Nearby Connections. On each phone: install the same APK
build (same signing key), grant Nearby devices / Bluetooth / Wi-Fi /
Location permissions, create a local profile, and confirm the build tag
shown on the Account screen matches the build under test. Do not clear
app data between test rounds — existing records double as Room
migration proof.
