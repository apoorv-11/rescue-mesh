# RescueMesh

Offline-first emergency communication system. RescueMesh lets people in a
disaster-affected area send SOS alerts, safety check-ins, missing-person
reports and resource updates **without any internet connection**: messages
travel phone-to-phone over a local mesh, and any phone that later gains
connectivity — on any network, anywhere — uploads everything it carries to
a central server, where coordinators see the situation live on a dashboard.

B.Tech major project, built 14–19 November 2026.

## How it works

```
 [Phone A] ──SOS──▶ [Phone B] ──relay──▶ [Phone C]
   (no internet)      mesh (offline)        │
                                            │ any connectivity
                                            ▼
                              ┌─────────────────────────┐
                              │  Spring Boot sync server │──▶ PostgreSQL
                              │  signature-verified sync │
                              └───────────┬─────────────┘
                                          │ REST + SSE (live)
                                          ▼
                              ┌─────────────────────────┐
                              │  React coordinator       │
                              │  dashboard (map, queues, │
                              │  missing-person cases)   │
                              └───────────┬─────────────┘
                                          │ advisory only
                                          ▼
                              ┌─────────────────────────┐
                              │  ML service (priority,   │
                              │  duplicates, face match) │
                              └─────────────────────────┘
```

1. A user sends an SOS (or another report) in the Android app. It is stored
   locally first (Room) and signed with the phone's Ed25519 key.
2. The app relays it to nearby phones using Google Nearby Connections,
   hop by hop. Every relay appends a signed hop entry; duplicates are
   detected and dropped.
3. The first phone in the chain that reaches the internet acts as a
   **gateway**: it uploads its whole queue to the server in signed batches.
4. The server verifies every signature, stores each record once
   (idempotent), and returns a signed acknowledgement per message.
5. The dashboard updates live over Server-Sent Events (with a polling
   fallback): SOS queue, incident map, missing-person cases with photos,
   announcements and a sync audit trail.
6. The ML service advises coordinators — a suggested priority for each
   report, possible duplicates, and a face-similarity score for
   missing/found photo pairs. It never changes stored data and never
   closes a case; a human coordinator decides.

## Components

| Folder | Component | Stack | Runs on |
|---|---|---|---|
| [`rescuemesh/`](rescuemesh/) | Android app — mesh, SOS, forms, gateway sync | Kotlin, Jetpack Compose, Room, Nearby Connections | Android 8.0+ (API 26+) |
| [`rescuemesh-server/`](rescuemesh-server/) | Sync + auth backend | Spring Boot 4.1.2, Java 21, PostgreSQL 18, Flyway | Docker / any JVM host |
| [`rescuemesh-dashboard/`](rescuemesh-dashboard/) | Coordinator dashboard | React 19, TypeScript, Vite, Leaflet | Browser (static build) |
| [`rescuemesh-ml/`](rescuemesh-ml/) | Advisory ML service | Python, FastAPI, scikit-learn, InsightFace | Python 3.11+ |

Each folder has its own README with setup and usage details.

## Quick start (local demo)

```bash
# 1. Backend (API on http://localhost:8081)
cd rescuemesh-server
cp .env.example .env          # set APP_PORT=8081 if 8080 is taken
docker compose up --build

# 2. ML service (API on http://localhost:8001)
cd ../rescuemesh-ml
python -m venv .venv && .venv\Scripts\activate     # Windows
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8001

# 3. Dashboard (http://localhost:5173)
cd ../rescuemesh-dashboard
npm install
npm run dev                   # .env: VITE_API_BASE=http://localhost:8081

# 4. Android app — open rescuemesh/ in Android Studio and Run,
#    or: cd rescuemesh && gradlew.bat assembleDebug
```

Log in to the dashboard with the seeded admin account from the server's
`.env` (`ADMIN_USERNAME` / `ADMIN_PASSWORD`).

## Design rules (project-wide)

- **Offline first.** Every write lands in the phone's local database
  before anything else; no core flow needs a network.
- **SOS is never gated.** SOS and "I Am Safe" work without an account,
  without login and without a network — always.
- **One message envelope, 11 fields, everywhere:**
  `messageId, eventType, originDeviceId, createdAt, expiresAt, priority,
  hopLimit, hopCount, hopTrace, payload, signature`. Phones, server and
  tests all use exactly this shape.
- **Roles are earned, not chosen.** New accounts are always `CITIZEN`;
  `VOLUNTEER` / `COORDINATOR` come only through application → admin
  review → emailed one-time code; `ADMIN` only via an admin-issued code.
- **ML is advisory.** Scores are labelled as advice in the UI; case
  decisions stay with human coordinators.

## Repository layout

```
major-project/
├── rescuemesh/            Android app
├── rescuemesh-server/     Spring Boot backend (+ Postman collection)
├── rescuemesh-dashboard/  React dashboard
└── rescuemesh-ml/         ML service (+ evaluation.md)
```

## Team

| Track | Scope | Members |
|---|---|---|
| A | Android app + peer-to-peer mesh | Abhishek, Ansh |
| B | Spring Boot backend + dashboard | Apoorv Prasad |
| C | ML service, testing, report | Abhishek |
