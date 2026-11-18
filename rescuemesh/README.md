# RescueMesh — Android app (Track A, Phase 1)

Offline-first emergency mesh app. B.Tech major project.
Source of truth: *RescueMesh — Synopsis-Aligned Build Plan* (the first doc). Where Doc 2
differs, the first doc wins.

This delivery completes **Phase 1, Track A** = Prompt 1 (foundation) + Prompt 2 (Nearby spike):

## What is built

| Piece | Where |
|---|---|
| Android project, package `com.rescuemesh` | this repo (`app/`) |
| Room entities `Profile, Message, Peer, Metrics` + DAOs | `app/src/main/java/com/rescuemesh/data/local/` |
| Onboarding: name only, stored in Room (FR-01). The profile is always saved as CITIZEN — there is no role picker (UI redesign, 15 Nov); elevated roles come later via Account → apply → admin review → emailed code → redeem | `ui/Screens.kt` → `OnboardingScreen`, policy in `ui/OnboardingPolicy.kt` |
| Home screen: **SOS in two taps** (tap 1 arms, tap 2 sends), **I Am Safe in one tap** | `ui/Screens.kt` → `HomeScreen` |
| Offline-first: every write goes to Room first; no network call exists in Phase 1 | `data/repository/` |
| Records survive an app restart (file-backed Room DB, not in-memory) | `data/local/RescueMeshDatabase.kt` |
| Envelope contract guard: `Message` carries the exact 11 envelope field names | `EnvelopeContractTest` (fails if a field is renamed) |
| `MeshTransport` interface + `NearbyTransport` (Nearby Connections, P2P_CLUSTER) | `mesh/` |
| Runtime permissions per Android version, gated manifest sets | `mesh/NearbyPermissions.kt`, `AndroidManifest.xml` |
| Mesh tab (was the mesh debug screen): relay toggle + TTL test mode + metrics CSV, grant permissions → start mesh → peers list → connect → send test message, expandable received/metric rows; DISCOVERY/TRANSFER metrics are written to Room | `ui/Screens.kt` → `MeshScreen` |
| Phone-side rules from the first doc: priorities 1/2/3 (medical/trapped SOS = 1), TTL SOS 24 h, I Am Safe 12 h, Missing 72 h, hopLimit 8 | `domain/Models.kt`, `domain/MessageFactory.kt` |

**Not in Phase 1 (later prompts, do not expect them here):** signing/Ed25519 + canonical
envelope JSON (Prompt 4), relay engine HELLO/MSG/ACK/DONE (Prompt 5), SOS/Missing/Announcement
forms (Prompt 6), gateway sync (Prompt 9). In the spike, phones exchange a plain test payload,
and `signature` is empty — it is never presented as a real signature.

## Versions (verified 2026-11-14, latest stable, pre-releases excluded)

Checked against official sources: AGP release notes + Google Maven metadata
(`dl.google.com/dl/android/maven2`), kotlinlang.org, Maven Central.

| Component | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 9.6.0 | Required by AGP 9.5 (official compatibility table) |
| Android Gradle Plugin | 9.5.1 | Latest stable. Built-in Kotlin — do **not** add `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.21 | Latest stable (kotlinlang.org). Applied via the Compose compiler plugin |
| KSP | 2.3.14 | Latest stable; KSP now versions independently of Kotlin |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | compileSdk 37 is required by core-ktx 1.19.x (its AAR metadata says minCompileSdk 37) |
| Jetpack Compose BOM | 2026.09.00 | Maps to Compose 1.12.x / Material3 |
| Room | 2.8.5 | Latest stable of the Room 2 line |
| core-ktx / activity-compose / lifecycle | 1.19.1 / 1.13.0 / 2.11.0 | Latest stable |
| kotlinx-coroutines | 1.11.0 | Latest stable |
| play-services-nearby | 19.5.1 | Latest stable; every Nearby API call used here was checked against this AAR's actual signatures |
| Retrofit + converter-kotlinx-serialization | 3.0.0 | Latest stable (Maven Central metadata); account layer only. Retrofit's POM pins its OkHttp 4.12.0 — not re-pinned here |
| kotlinx-serialization-json | 1.13.0 | Latest stable (Maven Central metadata); compiler plugin is versioned with Kotlin (2.4.21) |
| JDK | 17 | Required by AGP 9.5 |

All pins live in `gradle/libs.versions.toml`.

## Run it

Prerequisites: Android Studio (latest stable) with SDK Platform 37 and Build-Tools 36,
JDK 17 (bundled with Android Studio is fine), and **physical phones** — Nearby does not
work properly on emulators.

```bash
# from this folder
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew assembleDebug              # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest  # Room persistence tests (needs a connected phone)
```

Or open the folder in Android Studio, let it sync, and Run on a phone.
Installing the APK by hand: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Verify — Phase 1 gate (do this on real phones, in order)

**Gate 1a — foundation (one phone)**
1. Install, open the app, complete onboarding (name + role). Kill the app, reopen:
   the profile must still be there (home screen, not onboarding).
2. Tap **SOS** once — it asks to tap again — tap again: an `SOS · priority 2 · CREATED`
   card appears in "Saved on this phone".
3. Tap **I AM SAFE** once: a `SAFETY_CHECKIN` card appears immediately.
4. Kill and reopen the app: both cards are still there. ← restart persistence

**Gate 1b — Nearby spike (two phones)**
1. On both phones: airplane mode ON, but Wi-Fi and Bluetooth ON. Google Play Services present.
2. On both: Home → *Mesh debug* → **1. Grant permissions** (allow all) → **2. Start mesh**.
3. Each phone should list the other under *Peers* within ~5 s (synopsis NFR: discovery < 5 s).
   Note the DISCOVERY metric time at the bottom of the screen.
4. Tap **Connect** on one phone; on the other, accept nothing — connection completes by itself.
5. Tap **Send test message**: the sender shows "Sent N bytes in X ms"; the receiver lists the
   message under *Received*. Swap roles and repeat.
6. Record phone models, Android versions and the times in your device matrix for the report.

If Gate 1b fails on your phones, the plan's fallback is a raw BLE GATT transport behind the
same `MeshTransport` interface — decide that on Sunday, not later.

## Prompt 4 — device identity + signed envelopes (14 Nov 2026)

Implemented per the researched decisions in
`../goals/rescuemesh-major-project/files/prompt4-identity-decisions-2026-11-14.md`:

- **Identity (`crypto/DeviceIdentity.kt`):** one Ed25519 keyset per phone, via
  Tink-android 1.24.0 with the **NO_PREFIX** template (raw 64-byte signatures;
  Tink's default template would add a 5-byte prefix — a unit test guards this).
  The keyset is stored by `AndroidKeysetManager` in the app-private
  SharedPreferences file `rescuemesh_identity_keyset`, encrypted under an
  AES-256-GCM key-encryption key in Android Keystore (alias
  `rescuemesh_kek_v1`, StrongBox attempted first, no user-auth requirement).
  The keyset file is excluded from cloud backup and device transfer
  (`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`) — Keystore
  keys cannot move devices, so a restored keyset would be unusable; a fresh
  install / new phone therefore gets a **new identity by design**.
- **originDeviceId:** `lowercase_hex(SHA-256(raw 32-byte public key))`, full
  64 chars. Receivers recompute it from the public key (carried in HELLO from
  Prompt 5 — it is *not* an envelope field) and reject mismatches.
- **Signing (`crypto/EnvelopeSigner.kt`, `crypto/CanonicalJson.kt`,
  `crypto/JsonParser.kt`, `crypto/DeviceIds.kt`):** every SOS / I Am Safe is
  signed before it is stored (MessageRepository signs the factory draft, then
  writes to Room — still local-only, still Room-first). The signed bytes are
  the canonical JSON (JCS-style: sorted keys, no whitespace, integers only)
  of the envelope *without* the signature field; `signature` stores the
  64-byte Ed25519 signature as Base64url-no-pad. Anyone with the sender's raw
  public key can verify fully offline (`EnvelopeSigner.verifyEnvelope`).
- **Identity migration:** Phase 1 builds stored a random UUID as the profile
  deviceId. On first launch of this build, a stored profile id that differs
  from the key-derived originDeviceId is replaced with it (Phase 1 data is
  test data; new profiles always use the identity id).
- **Nearby label:** the mesh now advertises `"RM-" + originDeviceId.take(12)`
  instead of the full id; the full id + public key travel in-band from
  Prompt 5 (HELLO).

## Account & server testing (optional)

The app now has an **account layer** for testing the Spring Boot server
(`rescuemesh-server`, auth v2) end to end: Home → **Account (server)**.
It is strictly optional and strictly additive:

- **No account is needed for anything emergency-related.** The app opens
  straight to Home exactly as before; SOS (2 taps) and I Am Safe (1 tap)
  never check login state, a token, or server reachability, and the whole
  app remains fully usable offline with no server configured at all.
- **Set the server URL first** (top of the Account screen, always
  editable): the HTTPS Render URL (`https://<your-service>.onrender.com`)
  or a LAN Docker server (`http://<pc-ip>:8081` — the phone and the PC
  must be on the same Wi-Fi). It is stored in app-private preferences,
  not in Room.
- **Sign up** with username + email + password. The phone's
  `originDeviceId` (its Prompt 4 identity) is sent automatically as the
  deviceId — you never type it — and no role is sent: the server creates
  every account as CITIZEN.
- **Verify the email:** enter the 6-digit code from the email. On a dev
  server without a mail provider key, the server prints the code in its
  own log instead — verification still works the same way. Verifying
  signs you in (the server returns the first token).
- **Log in:** if the password is right but the email was never verified,
  the server answers 403 and the app takes you straight to the code-entry
  step, with a Resend button.
- **Device binding:** after sign-in the app reads `GET /me`; if this phone
  is not the device bound to the account, it binds itself once
  (`POST /profile/bind-device`). A bind failure is shown as a note, never
  a blocker.
- **Roles:** *Apply for a role* sends a VOLUNTEER/COORDINATOR application
  (organization + reason) for an admin to review; *My applications* shows
  their state. On approval you receive a one-time access code — enter it
  under *Access code* to redeem it; the app stores the fresh token the
  server returns and your role updates immediately.
- **One role, everywhere (role mirror):** the role the server reports
  (`GET /me`) is mirrored into the local profile on every successful read —
  sign-in, verification, code redemption, refresh — so the Home screen and
  the Account screen can never disagree about your role. The local role is
  only ever overwritten by a positive server answer: logging out, being
  offline, or a failed request never changes it (a granted role is a fact
  about the person; a dead server must not un-grant it).
- **APPROVED means granted, not yet active:** an application card says so
  in plain words — the role activates only when the one-time access code
  is redeemed under *Access code*, and an approved-but-unredeemed
  application gets a visible reminder right above that field.
- **Token storage:** the JWT is stored encrypted (Tink AES-256-GCM,
  Android Keystore KEK `rescuemesh_token_kek_v1` — same construction as
  the identity keyset), never in plaintext, and there is deliberately no
  HTTP logging, so tokens never reach logcat. Log out just discards the
  local token (the server's JWT is stateless; there is no logout call).
- **Cleartext HTTP is a testing-build choice:** `AndroidManifest.xml`
  declares the INTERNET permission (used only by this layer) and
  `res/xml/network_security_config.xml` permits cleartext so the LAN
  Docker address above works. Tighten both before any public release
  and use the HTTPS URL.

## Prompt 5 — relay engine (14 Nov 2026)

Records now actually move phone → phone. Protocol source: the batch
contracts (`batch-p5-p9-contracts.md` §1–§4), implemented in `mesh/RelayEngine.kt`
over the same `MeshTransport` boundary as the spike.

**How a session works**

1. **Initiator rule:** of two discovered phones, the one whose advertised
   label (`"RM-" + originDeviceId.take(12)`) is lexicographically smaller
   connects; the other only accepts. A 30 s cooldown (per peer) follows a
   completed session, and a peer whose HELLO fails the id↔key binding is
   never auto-reconnected by this engine instance.
2. **HELLO** is sent by both sides on connect: full deviceId, raw public
   key, gateway flag, the ids this phone holds (inventory, ≤ 200), and any
   server acks it can gossip (≤ 200). The receiver first checks
   `deviceId == hex(SHA-256(publicKey))` — mismatch → ignore + disconnect —
   then applies verified gossiped acks (records the server already has
   become SERVER_SYNCED and stop spreading).
3. **Send phase:** records go one **MSG** at a time, in queue order
   (priority ASC, then oldest first), skipping anything expired,
   hop-limited, already in the peer's inventory, or already ACKed by that
   peer this session. Each MSG waits for its **ACK** (10 s timeout):
   STORED/DUPLICATE → a RELAY metrics row + the record becomes PEER_ACKED
   (it is still offered onward to *other* peers — one ACK is not the end of
   spreading); REJECTED/timeout → backoff `min(2^attempts × 1 s, 30 s)`,
   max 3 attempts per peer per session, then FAILED_RETRYABLE.
4. **Receive path:** size guard (30 000 bytes — larger packets are ignored,
   no ACK) → decode → the origin's public key comes from hop entry 0
   (entry 0 must be the ORIGIN entry of `originDeviceId`, and its `pk` must
   hash to that id) → origin signature verified → expiry on *this* phone's
   clock → hop-limit drop rule → duplicate check (ACK DUPLICATE, no state
   change) → this phone appends its own signed RELAY hop entry →
   **persist to Room, then ACK STORED**. Any failed check before the insert
   gets ACK REJECTED and a failed RELAY metrics row with the reason.
5. **DONE:** when a side's queue for the peer is empty it sends DONE; when
   both sides have sent DONE, the engine disconnects and the cooldown
   starts. A TTL purge runs every 15 s: records past `expiresAt` in
   CREATED/QUEUED/SENDING/PEER_ACKED/FAILED_RETRYABLE become EXPIRED
   (SERVER_SYNCED/REJECTED are never touched).

**Record states** are the contracts table: CREATED (repository, at
creation) → QUEUED (engine adopts it / received records land here) →
SENDING → PEER_ACKED → SERVER_SYNCED (verified server ack; Prompt 9's
GatewaySync also sets it), plus EXPIRED, FAILED_RETRYABLE and REJECTED
(server refusal, Prompt 9).

**Signing-scope change (contracts §1 — read this before verifying old
records):** the origin signature now covers only the 8 origin-fixed fields
{messageId, eventType, originDeviceId, createdAt, expiresAt, priority,
hopLimit, payload}. Prompt 4 signed everything except `signature` — but a
relay appends hop entries and changes `hopCount`, so under the old scope
every relayed record would fail verification. Hop integrity is carried by
the hop entries instead: each entry is signed by the device that wrote it
over {messageId, i, role, deviceId, at, prevSig, +loc/net}, with `pk` bound
by `deviceId == hex(SHA-256(pk))` and `prevSig` chaining the entries
(`crypto/HopTrace.kt`). An invalid entry is flagged, never fatal. Phase 1
test records carry old-scope signatures and will fail verification as
"invalid signature" if they ever reach a peer — accepted, no migration.
The contracts' fixed cross-side test vector is pinned in
`EnvelopeSignerTest` (canonical bytes, envelope signature, hop-0 entry —
byte-identical expectations).

**Pre-identity records (upgraded phones):** records created by the
Phase 1 builds — UUID `originDeviceId`, empty signature, empty hop
trace — are kept on the phone and stay visible in its lists, but they
are **not relayed**: the engine never advertises them in HELLO or sends
them, and if a peer's rejection is one this phone can reproduce locally
(missing/invalid entry 0, bad origin signature) the record is marked
REJECTED (terminal) instead of being re-offered every session
(`mesh/RelayRecordValidation.kt`). Current-build records always carry
entry 0 + a signature and relay normally.

**Creation funnel + SOS guard:** all local creation (quick SOS, I Am Safe,
later the forms) goes through one path in `MessageRepository`: factory
draft → hop entry 0 attached (ORIGIN, at = createdAt) → envelope signed →
Room. SOS creation is capped at 5 per rolling 60 s; excess returns a typed
`Refused("Too many SOS — wait a moment")` that the UI shows in its status
line — never an exception.

**Foreground service:** mesh debug screen → **Relay: On** starts
`RelayService` (persistent notification "RescueMesh relay is on",
`foregroundServiceType="connectedDevice"`), which runs ONE RelayEngine
over the app's shared transport — it never creates a second
NearbyTransport. The engine itself is plain Kotlin and constructor
injected; the server-ack store behind it is a seam (`mesh/ServerAckStore.kt`)
that was a no-op in the Prompt 5 delivery — Prompt 9 (below) has since
wired the Room table + server-key verifier and the shared internet check
into `RelayService`, so HELLO now gossips verified acks and carries the
real gateway flag.

**TTL test mode** (mesh debug screen switch): records are treated as
expired 60 s after creation regardless of their real TTL — for gate tests
only. The switch is persisted in SharedPreferences and read by
RelayService when it builds the engine (toggling it restarts the service).

**Fresh install note:** grant the Nearby permissions first — tapping
**2. Start mesh** or switching **Relay** on now asks for them
automatically, and the status line names any that are still missing
(before this fix an un-granted phone failed with a raw
`8038: MISSING_PERMISSION_BLUETOOTH_ADVERTISE`).

**Gate test — 3-phone relay (what you should see):** phones A, B, C, all
with Relay on, airplane mode, Wi-Fi/Bluetooth on. Create an SOS on A with
only A–B in range: B's home list gains the SOS (state QUEUED, hops 1/8),
A's copy shows PEER_ACKED. Then bring C in range of B (A out of range or
relay off): C gains the SOS with hops 2/8 — B forwarded its stored copy
unchanged and C appended its own entry. Kill/reopen any app: records
persist (Room). Duplicate protection: A meeting B again does not create a
second copy on B.

**Honest limits of this build**

- Role certificates on ANNOUNCEMENT / PERSON_FOUND_UPDATE are NOT checked
  on receive (the project's cert system is a design doc only) — per the
  batch contracts §8, records are accepted without it in this batch.
- The relay engine, codec, hop crypto, DAOs and repository are JVM-verified
  (91 tests, below) against the real jars, and `RelayService` +
  `RescueMeshApp` wiring compile against the real android.jar with the
  same standalone kotlinc setup. The Compose edits (relay switches on the
  mesh debug screen, AppViewModel toggles, SOS refusal status) are small
  and patterned on existing code but are **not** compile-verified in this
  environment, and nothing in Prompt 5 has run on a real phone yet — the
  3-phone gate above is the first on-device proof.
- `FakeMeshTransport` changed for tests: it is now `open`, gained
  `deliverIncoming`/`setPeerConnected` pairing hooks, and its `incoming`
  flow replays recent payloads (a replay-0 SharedFlow silently drops
  packets emitted before a collector subscribes — an artifact that made
  paired-engine sessions race at startup; a real link delivers via
  callbacks). Loopback behaviour is unchanged.

## Prompt 6 — workflow forms + record lists (14 Nov 2026)

Home → **Records & forms** opens a hub (`ui/FormsScreens.kt`) with five
forms and five lists. Everything is offline-first exactly like quick SOS:
forms write through the same `MessageRepository` creation funnel (factory
draft → hop entry 0 → signed → Room, state CREATED), and lists read Room
back through per-type flows (`MessageDao.observeByType`, newest first).
Payload shapes are contracts §7 exactly — optional fields are omitted when
absent (never null), and locations travel as
`{"lat":"<3dp string>","lon":"<3dp string>","source":...}` — strings,
never floats.

- **SOS form:** category chips (MEDICAL, TRAPPED, FIRE, FLOOD_RESCUE,
  FOOD_WATER, SHELTER, OTHER), description with a 280-char counter,
  people stepper 1..999, optional injured stepper 0..people, needs chips
  (WATER, FOOD, MEDICINE, SHELTER, RESCUE), vulnerable chips (CHILD,
  ELDERLY, DISABLED, PREGNANT). MEDICAL/TRAPPED → priority 1, other SOS →
  2. The §4.9 rate guard covers this path too: a refused submission keeps
  the reason visible on the form; a saved one returns to the hub with a
  status line. The quick 2-tap Home SOS is unchanged (minimal payload).
- **Missing person form:** name (optional), age (optional), description,
  last-seen area + date/time. The payload's `caseId` IS the record's own
  messageId (the factory generates the id first, then builds the payload
  around it). Photo: the picker image is downscaled to longest side
  ≤ 320 px, re-encoded JPEG and Base64url-encoded
  (`domain/PhotoThumb.kt`); the string is included as `photoThumb` ONLY
  when ≤ 12 000 chars — the encoder steps quality, then dimensions,
  down a fit ladder to get under the cap, and `photoOmitted: true` is
  set only when even the ladder's floor cannot fit. The form shows "Photo attached ✓" / "Photo omitted
  (too large)". The Missing-persons list decodes `photoThumb` and shows
  the actual photo (72 dp) on the record card — the Base64 string is
  payload data and is never displayed as text; an omitted photo shows
  "Photo omitted (too large to relay)" instead. (TG-4 fix, 14 Nov 2026:
  the list originally never decoded the thumbnail, so relayed photos
  were invisible in the app. The same test found that NearbyTransport
  could silently drop received packets — its incoming hand-off is now
  lossless: an unlimited channel drained by a suspending emit, matching
  the FakeMeshTransport semantics the JVM suite tests against.)
- **Found update:** pick the case from the locally stored MISSING_PERSON
  records (id + name/description), add a note and an optional location.
  Priority 2, payload `{"caseId","note","location"?}`. The form also
  takes an optional photo of the found person, through the exact same
  pipeline as the Missing form's (below), so a case's two photos can
  later be compared side by side on the dashboard.
- **Announcement form:** category, text, valid-until date/time (simple
  text fields). The envelope's `expiresAt` IS `validUntil`. For CITIZEN /
  VOLUNTEER local profiles the form shows a gentle note — "Announcements
  are usually posted by coordinators — yours will still be stored and
  relayed." — and nothing is ever blocked by it.
- **Resource update:** resource type, quantity stepper ≥ 0, optional
  note/location. Priority 3, TTL 12 h.
- **Location fields (shared):** a consent toggle, OFF by default. When on:
  manual lat/lon text fields, plus a *Fill from GPS* button that reads the
  LocationManager's last-known fix (guarded by the existing location
  permissions; no new dependencies and no live location request) and
  formats it to exactly 3 decimals with source `LAST_KNOWN`; typed values
  use source `MANUAL`.
- **Lists:** SOS / Safety / Missing / Announcements / Resources — each row
  shows a summary line (category/name/text head), the record state, hop
  count and creation time.

**Full photos up to 500 KB (photo-transfer design, 14 Nov 2026).**
Missing and Found photos no longer travel as thumbnails only. The picked
image is stepped down (start ≈1600 px / JPEG q80, stepping quality then
resolution down to a ≈800 px / q50 floor, `domain/PhotoFull.kt`) until
the JPEG is **≤ 512 000 bytes**; if even the floor cannot fit it, the
form falls back to the thumbnail-only path (or `photoOmitted`) with a
clear note — a photo problem never blocks the record. What is kept is
held OFF the envelope: the payload carries the small `photoThumb`
preview (now derived from the final JPEG) plus a signed
`photoRef {photoId = the record's own messageId, sha256, sizeBytes,
mime}` — the "parcha" that authenticates the photo bytes. The bytes
live in `domain/PhotoStore.kt` (app files dir: `photos/<messageId>.jpg`
when complete, `photos/<messageId>.parts/` while chunks are in flight)
and move over the mesh in a photo phase that runs AFTER the record
queue inside each relay session (a record's PEER_ACKED never waits for
its photo): HELLO advertises a `photos` inventory of complete,
hash-verified photos held; the holder sends 16 KiB `PHOTO_CHUNK`s and
an END marker; the receiver writes parts, assembles, and answers
`PHOTO_ACK` COMPLETE (verified against the signed ref — it is now a
store-and-forward holder itself) or MISSING with the chunk indices it
still needs (the sender resends only those, ≤ 3 rounds; a hash mismatch
discards the parts and reports the photo missing from scratch). Relay
nodes forward only complete, verified photos, so A→B→C works with B
offline in between. When a record expires, the TTL purge deletes its
photo file and parts too. Missing and Found record cards still show the
thumbnail instantly; when the complete full photo is held locally the
card adds "Full photo saved ✓" and tapping the thumbnail opens the
full photo.

**Preview + delivery visibility (device fix, 14 Nov 2026).** A
detailed photo's 320 px / q70 thumbnail can exceed the 12 000-char
cap; the single-shot encoder then produced NO thumbnail, so a record
carrying a full photo rendered with no picture while "Full photo
saved ✓" showed. Thumbnails are now fitted by a ladder in
`PhotoThumb` (quality down, then dimensions down, first encoding
inside the cap wins; `photoOmitted` only when even the floor cannot
fit). Card previews follow `domain/PhotoPreview.kt`: the payload
thumbnail if present, else the locally held complete photo (decoded
downscaled for the card), else the omitted note. And when a peer's
PHOTO_ACK COMPLETE confirms a delivery, the relay engine records it
in the new `photo_delivery` table (Room v3, `Migration(2,3)`), so the
sender's card adds "· Photo delivered ✓" — proof the photo itself,
not just the record, arrived.

## Prompt 9 — gateway sync (14 Nov 2026)

When a phone can reach the server, it uploads its stored records and —
just as importantly — learns which records the server already has, so the
mesh stops spreading them. Implementation: `net/GatewaySync.kt`,
`net/InternetChecker.kt`, `data/repository/RoomServerAckStore.kt`.

**How a sync run behaves**

1. **Shared internet check:** one implementation (`InternetChecker`) —
   a server URL is configured AND `GET {baseUrl}/health` returns 2xx.
   Its cached flag also drives HELLO's `gateway` field (wired into
   RelayService's engine in place of the Prompt 5 `{ false }` placeholder).
   No URL → the run stops with "No server URL set"; health check fails →
   "Offline", nothing is uploaded.
2. **Server pubkey:** every run fetches `GET /sync/pubkey` (public) and
   caches (keyId, publicKey) in SharedPreferences (`SyncKeyStore`). A
   changed keyId replaces the cache — acks signed by the old key then
   simply stop verifying.
3. **Token order:** the signed-in user's token if there is one; on its
   absence or a 401 from `/sync/batch`, `POST /auth/device {deviceId}`
   mints a device token used for that run only (kept in memory, never
   persisted by the sync layer). If both fail, the run stops with
   "login needed".
4. **Selection:** localState in (CREATED, QUEUED, PEER_ACKED,
   FAILED_RETRYABLE) and not expired, in queue order (priority ASC, then
   oldest first), batches of 50, until nothing eligible remains.
5. **GATEWAY hop:** before uploading a record this phone did NOT
   originate, the phone appends its own signed GATEWAY hop entry
   (`net` = WIFI/CELLULAR from the active network, omitted when neither)
   — to the stored Room row AND the uploaded copy (same updated row).
   Own records upload exactly as stored. `originPublicKey` per item is
   our own key for own records, hop entry 0's `pk` for relayed ones; a
   record whose entry 0 is missing/invalid is skipped silently (it could
   never pass the server's key check).
6. **Per-message results, never blanket marking:** ACCEPTED /
   ALREADY_EXISTS → the returned `serverAck` is verified under the cached
   server key; only then is the ack stored (Room `server_ack`) and the
   record marked SERVER_SYNCED. An invalid or missing ack leaves the
   record untouched and is counted in the status line ("ack unverified
   for N records"). REJECTED → the record is marked REJECTED (terminal)
   with a SYNC metrics row carrying the server's reason. Any HTTP-level
   failure aborts the run with record states unchanged.
7. **Ack gossip:** stored verified acks ride in HELLO (the relay engine's
   Prompt 5 seam is now fulfilled by the Room store — `RelayService`
   passes it to the engine). A phone that learns "the server has record
   X" marks X SERVER_SYNCED and stops relaying/uploading it.
   `RoomServerAckStore.recordAck` stores ONLY verified acks, so gossip
   from this phone only ever carries acks the server really signed.
8. **Triggers:** app start, a ConnectivityManager network callback
   (onAvailable → refresh + sync), RelayService start, and the manual
   **Sync now** button + status line in the Account screen's *Gateway
   sync* section (visible signed in or not). Concurrent triggers collapse
   into one run.
9. **Metrics:** one SYNC row per run (detail = the counts summary), one
   per REJECTED record (detail = reason). The mesh debug screen's
   **Export metrics CSV** button writes ALL metrics rows to
   `rescuemesh-metrics-<epoch>.csv` in the app's external files dir and
   shows the full path in the status line (no share sheet).

**Room v2:** the only schema change in this batch — new `server_ack`
table (messageId PK, status, issuedAt, signature) with an explicit
`Migration(1, 2)` (CREATE TABLE). exportSchema stays on; the schema JSON
under `app/schemas/` is generated by the Android Studio / Gradle build.

**Gate test — gateway sync (do this on the LAN server):**

1. Phone A, airplane mode (or just no server contact): create an SOS
   (quick SOS or the form). It sits in state CREATED.
2. Put phone A on the LAN, open **Account (server)**, set the server URL
   (`http://<pc-ip>:8081`), tap **Sync now** in the *Gateway sync*
   section. The status line reports "Synced: 1 new, …" and the SOS card
   on Home now shows SERVER_SYNCED.
3. Confirm on the server: `GET /records/sos` (authenticated) lists the
   record. (If you sync while signed out, the device-token fallback needs
   this phone's deviceId to be bound to an account — bind once via the
   normal sign-in flow; otherwise the status line says "login needed".)
4. Phone B (relay on, has the same SOS from relaying with A): bring B in
   range of A (or sync B too). B learns the server ack via A's HELLO and
   marks its copy SERVER_SYNCED — it stops offering that SOS to other
   peers.

**Honest limits of this build**

- Role certificates on ANNOUNCEMENT / PERSON_FOUND_UPDATE are still NOT
  checked on receive (contracts §8) — records are accepted without them.
- The announcement date/time and missing-person last-seen fields are
  simple text fields (yyyy-MM-dd + HH:mm), not Material date pickers.
- GatewaySync, the ack store, the factory/forms logic and the CSV builder
  are JVM-verified (122 tests, below). The Compose screens
  (`FormsScreens.kt` and the small Screens/AppViewModel/AccountViewModel
  edits) are patterned on existing code but **not** compile-verified in
  this environment, and gateway sync has not yet run against a real
  server from a phone — the gate test above is its first on-device proof.

## Tests in this repo

- `app/src/test/` (JVM): `MessageFactoryTest` (SOS/safety rules, TTLs, priorities),
  `EnvelopeContractTest` (the 11 field names), `FakeMeshTransportTest` (transport behaviour),
  `TaskAwaitTest` (GMS Task failures surface as exceptions — the error-swallowing gate fix),
  `crypto/DeviceIdsTest` (originDeviceId formula against independent SHA-256 vectors),
  `crypto/CanonicalJsonTest` (canonical form: key order, nesting, escapes, no floats),
  `crypto/EnvelopeSignerTest` (sign/verify round trip on a real Tink keyset; signature is
  exactly 64 bytes; tampering and wrong keys fail; the §1 8-field canonical shape;
  relay mutations of hopCount/hopTrace keep the origin signature valid; the
  contracts §3 fixed test vector — canonical bytes, envelope signature, hop-0 entry),
  `crypto/HopTraceTest` (hop entries: origin/relay/gateway builds, chain validation,
  per-entry tamper flagging, loc/net in the signed set),
  `mesh/PacketCodecTest` (HELLO/MSG/ACK/DONE round-trips incl. envelopes with hop
  entries; structural violations rejected at decode),
  `mesh/RelayEngineTest` (paired FakeMeshTransports: initiator rule, A→B store +
  PEER_ACKED, B→C onward relay with growing trace, duplicate MSG → ACK DUPLICATE,
  queue order, TTL test-mode purge, retry cap → FAILED_RETRYABLE, verified HELLO
  serverAcked → SERVER_SYNCED, impostor HELLO → disconnect),
  `data/MessageRepositoryTest` (creation funnel attaches entry 0 + signs; SOS guard
  refuses the 6th SOS in 60 s and allows it after the window),
  `FormsPayloadTest` (Prompt 6: exact §7 payload JSON per type, omitted optionals,
  caseId == own messageId, announcement expiresAt == validUntil, escaping round-trip,
  the form path through the repository funnel incl. the SOS guard),
  `PhotoThumbTest` (320 px scale factor/dimensions, the 12 000-char cap boundary),
  `net/GatewaySyncTest` (fake SyncApi: GATEWAY entry appended to relayed uploads and
  persisted, own uploads unchanged; verified ack → SERVER_SYNCED + ack row; wrong-key
  ack → unverified, state untouched; REJECTED → terminal, never re-selected; 401 →
  device-token fallback retried with the device token; ALREADY_EXISTS marks synced;
  batch wire field names),
  `data/RoomServerAckStoreTest` (record + verify round trip under a cached server
  key; wrong-key and tampered acks neither verify nor are stored),
  `data/MetricsCsvTest` (CSV header, nulls, comma/quote/newline quoting),
  with shared test support in `TestSupport.kt` (in-memory DAO fakes with the real
  queries' semantics, paired transports, throwaway Tink identities).
  **122 JVM tests, all passing** (91 pre-Prompt-6/9 + 31 new).
  `net/ServerConfigTest` (base-URL normalization/validation),
  `net/ApiErrorsTest` (the server's `{"error": ...}` parsing),
  `net/ApiDtosTest` (wire contract: exact field names, signup never sends a role,
  `/me` and application views decode incl. nulls),
  `ui/RoleSyncTest` (role mirror mapping: known server roles map, unknown ones are
  a no-op; application status explanations incl. APPROVED-is-not-active).
- `app/src/androidTest/` (device): `RoomDaoTest` — profile + SOS + I Am Safe are written to a
  file-backed Room DB, the DB is closed and reopened, and the rows are still there; a duplicate
  `messageId` is stored exactly once (FR-06).

## Verification status of this delivery (honest)

- The domain/data/mesh logic and all three JVM test classes were compiled with Kotlin 2.4.21
  and **all 7 unit tests pass** (run in the build environment against minimal compile stubs
  for Android/Room types, because that environment cannot run Gradle — see next point).
- Every Nearby Connections API signature used in `NearbyTransport.kt` was checked against the
  real `play-services-nearby` 19.5.1 AAR.
- Gate 1 field fix (14 Nov): error 8032 `MISSING_PERMISSION_ACCESS_WIFI_STATE` on both
  test phones was a manifest bug — `ACCESS_WIFI_STATE` was gated `maxSdkVersion="31"`,
  so Android 12+ phones never got it. It is now declared for all API levels (it is a
  normal install-time permission; there is no runtime prompt for it), and
  `ACCESS_FINE_LOCATION` was extended to API 32 to match the runtime list. Rebuild and
  reinstall after pulling this fix. (The await fix below is why the error was visible
  on screen at all — before it, start failures were silent.)
- Gate fix (14 Nov, later): `NearbyTransport.start()`/`connect()` now await the Nearby
  tasks and throw on failure (new `TaskAwait.kt` bridge; `isRunning` turns true only after
  advertising + discovery both start). The changed mesh files were compiled with Kotlin
  2.4.21 against the **real** play-services-nearby 19.5.1 / tasks 18.2.0 classes — clean —
  and `TaskAwaitTest` passes 2/2 against the real GMS `Tasks` implementation.
- The **full Gradle build was still not run** in the assistant's VM: after the user set
  `other_tcp` to Ask, the Gradle daemon now starts and listens, but the client still cannot
  connect to it, and a Java loopback test still gets EOF instead of data — the setting has
  not taken effect for Java loopback in that VM (needs Allow, or a VM restart). The first
  full build is therefore Gate 0 on your machine via Android Studio. If sync or build errors
  appear, paste the exact error back — do not downgrade versions silently.
- Nearby behaviour on your exact phones is unverified until Gate 1b is run. That is the
  plan's own Phase 1 risk, and why the gate exists.
- Prompt 4 verification (14 Nov): the whole crypto package plus entities/domain were
  compiled with Kotlin 2.4.21 against the **real** tink-android 1.24.0 jar (downloaded
  from Maven Central; its API — `rawEd25519Template()`, `Ed25519Parameters`
  NO_PREFIX, `AndroidKeysetManager` — was checked against the 1.24.0 sources/jar
  before use), and **all 27 JVM tests pass** against it: DeviceIds 6, CanonicalJson 10,
  EnvelopeSigner 10, EnvelopeContract 1 — plus MessageFactory/FakeMeshTransport
  regression tests (6) still pass. The Android-side files (DeviceIdentity,
  RescueMeshApp, repositories, AppViewModel) also compile clean in the same setup
  against android.jar + real Room/Nearby/lifecycle jars. What is NOT verified here:
  the AndroidKeystore/keyset code path at runtime (needs a phone — first run of the
  new build creates the KEK + keyset, migrates the profile id, and SOS rows in Room
  should show a non-empty Base64url signature), and the full Gradle/APK build, which
  still happens in Android Studio as before.
- Account layer verification (14 Nov): versions were pinned from Maven Central
  metadata (Retrofit/converter 3.0.0, kotlinx-serialization 1.13.0 — Retrofit's
  own OkHttp 4.12.0 comes transitively) and the jars' actual API surface was
  inspected before use (`Json.asConverterFactory`, Tink's
  `PredefinedAeadParameters.AES256_GCM` + `KeyTemplate.createFrom`). The whole
  non-UI main source set — the new `com.rescuemesh.net` package (DTOs,
  ServerConfig, TokenStore, ApiClient), `AccountViewModel`, and the
  `RescueMeshApp` wiring — compiles clean with Kotlin 2.4.21 against the real
  jars (Retrofit/OkHttp/serialization/Tink/lifecycle/Room/android.jar), and
  **all 54 JVM tests pass**: the 35 pre-existing tests as regression plus 19
  new account-layer tests (ServerConfig 6, ApiErrors 4, ApiDtos 9). What is
  NOT verified here, same boundary as before: the Compose UI (`Screens.kt`,
  `MainActivity.kt`) is not compiled in this environment, Retrofit/OkHttp
  runtime behaviour and the TokenStore Keystore path need a real phone, and
  the first full Gradle build (which also resolves the new plugin/catalog
  entries) happens in Android Studio. One bug was caught by this compile
  pass and fixed: a `/*` sequence inside a TokenStore comment (Kotlin block
  comments nest) had left the file unparseable.
- Role-display fix (14 Nov, after on-device testing): the local profile role
  and the server session role could disagree (Home kept showing CITIZEN
  after a VOLUNTEER code was redeemed), and APPROVED applications looked
  already-active. Now every successful `GET /me` funnels through
  `applyServerSession`, which also mirrors the server-confirmed role into
  the Room profile (`ProfileDao.updateRole` — no schema change; Home
  already observes the profile as a Flow, so it updates live). Logout,
  offline and error paths never touch the local role. Application cards
  explain each status in plain words (APPROVED = granted, not active,
  until the code is redeemed), an approved-but-unredeemed application gets
  a hint above the Access code field, and a successful redeem announces
  "Role activated: … — updated on the Home screen too." The mapping logic
  lives in pure functions (`ui/RoleSync.kt`) with 9 new JVM tests. In this
  environment: the full non-UI main source set (incl. the changed
  `AccountViewModel`, repositories, DAOs) compiles clean against the real
  jars, and **all 63 JVM tests pass** (54 pre-existing + 9 new). NOT
  verified here: `Screens.kt` (the Compose edits — explanation line,
  approved hint — are small and follow the file's existing patterns) and
  on-device behaviour; both are proven by the next Android Studio build
  and phone run, as before.

## UI redesign — four-tab app (15 Nov 2026)

Styling, navigation and composition only — every feature behaves
exactly as before; the sections above describe the pre-redesign
layouts historically. Spec: the project's
`ui-redesign-spec-2026-11-14.md`.

- **Structure:** a hand-rolled router (`ui/AppNav.kt`: a pure reducer
  with per-tab back stacks, unit-tested) over four tabs — **Home,
  Records, Mesh, Account**. There is NO TopAppBar and NO M3
  NavigationBar (layout rebuild, below): tab roots carry in-body
  Archivo headers, pushed sub-screens get an in-body back row
  (chevron + title), and the bottom bar is CUSTOM — white, hairline
  top border, stroke icons drawn from the preview's own SVG paths,
  inactive grey `#9AA0A8`, active tab in Rescue Red with a 5 dp red
  dot under the label. System Back pops a sub-screen; at a tab root
  it keeps its default behaviour. All in-scroll "Back to home"-style
  buttons are gone. The app opens on the **Account tab** (after
  onboarding).
- **Theme:** `ui/Theme.kt` — warm off-white `#FAF7F4` background,
  white 20–24 dp cards, Rescue Red `#E5322D` primary, Safe Green
  `#1FA97A` (+ mint tint), ink `#15181D`; ExtraBold headlines,
  small-caps labels. **Fidelity pass (15 Nov):** the colour scheme is
  now COMPLETE (every `lightColorScheme` role set — the unset
  surface-container roles had fallen back to the Material baseline's
  purple tints, making cards look lavender on device), and all
  screens share one component set (`ui/Components.kt`: `RmCard`
  white cards, `ToggleRow` with right-edge-aligned switches,
  `PrimaryButton`/`SecondaryButton` (46 dp, 16 dp rounded rects
  since the layout rebuild below), `RmSpacing` constants;
  Records filter chips wrap in a `FlowRow`). Record states share one colour table
  (`ui/StateColors.kt`, unit-tested): CREATED slate, QUEUED amber,
  SENDING blue, PEER_ACKED green, SERVER_SYNCED violet,
  FAILED_RETRYABLE orange, EXPIRED grey, REJECTED red — used as card
  left borders + chips in Records and colour accents in Mesh.
- **Onboarding:** the preview's gradient hero (brand row, "Help
  finds you, even offline."), an `RmTextField` name field, the amber
  CITIZEN info card, a "Continue" button and progress dots. Name
  only, saved as CITIZEN (`ui/OnboardingPolicy.kt`, unit-tested at
  the save boundary) — no role picker.
- **Home:** header row (kicker + UPPERCASE name, role chip at the
  right); the red SOS hero with the preview's RING-STROKE target
  (white ring strokes .45/.55, white centre disc, red core — canvas,
  no image assets) and the SAME two taps (arm → send); the green
  one-tap I Am Safe card with its circular shield-check badge; a
  TWO-PILL status strip (relay state + peer count → Mesh tab;
  gateway sync state); the latest 3 records as mini-records WITH
  40 dp thumbs (photo or type tile; tap → Records tab).
- **Account tab:** gradient entry hero — time-based greeting, name,
  role chip + login-status chip, and the two swipe boxes INSIDE the
  hero. **Swipe left** (or tap the left box) pushes Login/Sign-up;
  **swipe right** (or the right box) starts the 3-second countdown,
  shown as the preview's DARK in-page card (red circle numeral, tap
  to cancel) — no more full-screen overlay — which then fires the
  same quick-SOS path as Home and lands on Home. Below: the
  server-account card (logged out) or the signed-in card + Role /
  Server navigation rows, and the green "SOS is never gated" info
  card. Login, Sign up, Verify email, Role & applications and
  Server & sync are pushed sub-pages — every capability and status
  string of the old Account screen is preserved (server URL, signup,
  verify incl. the 403 routing, login + auto bind-device, apply +
  my-applications + status explanations + APPROVED hint, redeem,
  /me role mirror, Sync now, logout).
- **Records tab:** "Records" header + stored-count subtitle; filter
  chips (All, SOS, Missing, Found, **Notices**, Resources) — white
  pills, **RED when selected** — over ONE list; rec-cards (state
  border + dotted state chip, 52 dp thumb — the real photo via the
  existing PhotoPreview decision, or the preview's gradient icon
  tiles — title, sub line, the photo-ok mint pill "Full photo
  saved ✓ · Photo delivered ✓", and the meta row: state chip left,
  time · hops right) that expand in place to the photo section plus
  a dashed-divider 2-column detail grid (Priority, Hops · via
  RM-…, Created, Expires, Origin · Signature, Message ID, and every
  payload field). A circular red FAB with a red-tinted shadow opens
  the chooser sheet with the five forms — fields, validation, photo
  pickers, consent toggles and submit behaviour unchanged. All form
  text inputs are the preview's `.field` style (`RmTextField`:
  small-caps label inside above the value); form chips are the same
  red-selected `.fchip` style; photo attach is a ghost button; save
  is the red button.
- **Mesh tab:** header + the DARK relay card (ink `#15181D`, white
  text, green-track switch, the "Advertising as RM-…" strip inside);
  controls as ghost-button pairs (Permissions + Start/Stop mesh) +
  TTL ToggleRow + Export-metrics ghost button; peers as chips (tap
  selects → that peer's Connect / Send-test ghost button below);
  received texts and metrics as compact message rows (state-style
  left edge, dotted state chips, expandable bold-label detail — no
  data dropped); and the 3-box Sent / Relayed / Dropped strip,
  counted from the real metrics rows by `ui/MeshCounts.kt`
  (RELAY success at hopCount 0 + TRANSFER success = Sent; RELAY
  success at hopCount ≥ 1 = Relayed; RELAY / TRANSFER failures =
  Dropped; DISCOVERY and SYNC rows are not message traffic and are
  not counted). The 8038 permission gate texts and the auto-prompt
  behaviour are unchanged.
- **Icons:** the app uses the official
  `androidx.compose.material:material-icons-extended` library,
  BOM-managed (Compose BOM 2026.09.00 pins it to 1.7.8 — verified in
  the BOM POM and the AAR), for chevrons / refresh / logout / image
  glyphs. The BOTTOM-BAR icons are the exception: they are stroke
  glyphs drawn on Canvas from the preview HTML's exact SVG path data
  (`RmNavIcon` in `ui/Components.kt`), because the preview's bar
  uses line icons, not filled material glyphs.
- **Layout rebuild (15 Nov):** the earlier rounds had only
  re-skinned the old layouts (fonts, colours, shadows) — the user
  was right that the screens still LOOKED the same. This round
  rebuilt the composable TREES element-for-element from the
  preview (`RescueMesh_UI_Redesign_Preview.html`; blueprint:
  `ui-replica-blueprint-2026-11-15.md`): the custom bottom bar, the
  onboarding gradient hero, the Account hero with in-hero swipe
  boxes and the dark countdown card, Home's ring-stroke target +
  two-pill strip + thumbed mini-records, the Records header /
  red chips / rec-card grid / circular FAB, Mesh's dark relay card
  + peer chips + metrics strip, and every form on `RmTextField`.
  Buttons are the preview's btn-red / btn-ghost: 16 dp rounded
  RECTANGLES (not pills), 46 dp tall, single-line. Typefaces stay
  bundled **Archivo** 700/800/900 + **Manrope** 400–800 (OFL — see
  `THIRD_PARTY_NOTICES.md`); cards stay white, borderless, 22 dp,
  soft ink shadow via `Modifier.rmSurface()`. Every ViewModel
  state, callback, form field, validation and submit call is
  unchanged — only the layout trees moved.
- **Verification (this environment):** the full JVM suite is
  **203 tests, green twice in a row** (195 pre-existing + 8 new:
  MeshCounts 7 for the metrics-strip mapping, Greeting 1 for the
  landing's time-based greeting; an earlier run in the round showed
  the known rotating `PhotoChunkRelayTest` timing flake once, then
  two clean runs). The ENTIRE main source set incl. all Compose
  files was compiled with ZERO errors by the official Compose
  compiler plugin (Kotlin 2.4.21) against the real Compose 1.12.1 /
  material3 1.4.0 / icons-extended 1.7.8 artifacts (kotlinc exit
  code checked directly). What still needs the phone: rendering,
  gestures and the real-radio flows — compare each of the five
  screens side-by-side with the preview Space (see the delivery
  note).
- **Forensic fix round — `15Nov-FIX3` (15 Nov):** the defect-fix
  build's three patches (status-bar padding, light status-bar
  icons, icon pivot) were verified present in the delivered zip
  yet the phone showed all three defects unchanged. The audit
  outcome, mechanism by mechanism: (1) the app had never called
  `enableEdgeToEdge()` — it relied on targetSdk enforcement for
  the window state — and the root used a bare
  `statusBarsPadding()`; `MainActivity` now calls
  `enableEdgeToEdge()` with light `SystemBarStyle`s (its insets
  listener reapplies the appearance on every dispatch) and the
  roots consume `WindowInsets.safeDrawing` (top + horizontal) —
  the Scaffold-grade inset source — while `BottomBar` applies
  the navigation-bar inset inside its own white background.
  (2) The dark-icons flag existed only as a one-shot controller
  call with no theme backing; `res/values/themes.xml`
  (`Theme.RescueMesh`, now the manifest theme) carries
  `android:windowLightStatusBar` /
  `windowLightNavigationBar` = true with transparent bar
  colours, applied by the framework before the first frame —
  three independent layers now produce the same outcome.
  (3) Every custom glyph routes through one helper, `draw24`;
  it now scales via a raw `Matrix` (`DrawTransform.transform`)
  instead of the pivot-parameterised `scale()` API — a matrix
  scale has no pivot at all, so the glyph-shifting failure
  class is removed at the single point all icons share.
  **Build tag:** `BuildInfo.TAG` is shown on the Account
  landing next to the device id (signed-out and signed-in
  cards) — a screenshot of that screen proves which build is
  running; if the tag is absent, the phone is on an older
  build. **Responsive pass (device-agnostic — widths ≈320 dp
  compact to 480 dp+ large, font scales 0.85–1.3+):** the two
  buttons' 46 dp is now a MINIMUM height (`heightIn`) so large
  font scales grow rather than clip; the Account hero's chip
  row is a `FlowRow` so the second chip wraps instead of
  overflowing on narrow screens; the bottom bar reads the
  real navigation-bar inset per device. Everything else was
  audited and already adapts (weight-based rows, wrapping
  texts, FlowRow chip groups, content-sized cards) — the
  approved look at standard size/scale is unchanged.
  Verification: whole main source set compiles with ZERO
  errors (official Compose compiler plugin, real 1.12.1 /
  1.4.0 / 1.7.8 artifacts); JVM suite **203/203 green, two
  consecutive runs**; `aapt2 link` validates the new theme
  and manifest against API 37.
