# RescueMesh — Coordinator Dashboard

Web console for RescueMesh coordinators. It shows the live operational
picture from the sync server: active SOS reports, an incident map,
missing-person cases with side-by-side missing/found photos, a publish
composer for announcements, and a sync audit trail. All data comes from
the real server API — there is no simulated or mock data anywhere in
this app.

## Tech stack

| Area | Technology |
|---|---|
| Framework | React 19.3.1, TypeScript 6.0.3 |
| Build tool | Vite 8.3.8 |
| Map | Leaflet 1.9.4 + react-leaflet 5.0.0 (OpenStreetMap tiles) |
| Live updates | Server-Sent Events (`GET /records/stream`) with automatic 2 s polling fallback |
| ML integration | Optional calls to the RescueMesh ML service (advisory only) |

## Features

- **Login + role gating.** JWT login against the server. `CITIZEN` and
  `VOLUNTEER` accounts are read-only; `COORDINATOR` and `ADMIN` can
  publish announcements and review cases.
- **KPI strip.** Active SOS, open missing cases, "I Am Safe" check-ins
  (24 h), records synced today.
- **Incident map.** Pins from record payload locations — red SOS,
  amber missing, blue resource.
- **SOS queue.** Priority-first, then oldest — the same order the mesh
  relays in. Type/priority filters and text search. When the ML service
  is reachable, each row can carry an advisory ML priority chip and a
  "Possible duplicate" badge; when it is not, the queue honestly shows
  "ML score: not connected yet" and no badges.
- **Missing-person cases.** A missing report and its found update are
  one case. Photos load side-by-side from the server; an optional
  "Check face match (ML)" button shows an advisory similarity score.
  **A case is never closed by a score** — a coordinator reviews and
  decides; the review is persisted on the server and survives reloads.
- **Announcements.** Coordinators publish directly to the server; the
  list shows server-persisted announcements with their validity window.
- **Live feed.** Rebuilt from server history on every load (records,
  announcements, case reviews, sync batches — newest 20), then updated
  live. A reload never erases it.
- **Sync audit.** The latest server-side sync batches (device, counts,
  time) from `GET /records/audit`.
- **Honest connection state.** The header always shows the real state:
  SSE live, polling, or server unreachable — never a fake "Live".

## Run

Prerequisites: Node.js 20+, the RescueMesh server running.

```bash
cp .env.example .env     # adjust if needed (see Configuration)
npm install
npm run dev              # http://localhost:5173
```

Production build:

```bash
npm run build            # tsc -b && vite build → dist/
npm run preview
```

## Configuration (`.env`)

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE` | `http://localhost:8081` | RescueMesh server base URL. For a LAN demo use the server machine's LAN IP; after deployment, the public server URL. |
| `VITE_ML_BASE` | `http://localhost:8001` | ML service base URL. Optional — if unset or unreachable, all ML assists degrade to honest "not connected" labels. |

Vite inlines these at build time: after changing `.env`, restart the
dev server or rebuild.

## Server endpoints used

`GET /records/sos`, `GET /records/missing`, `GET /records/announcements`,
`GET /records/messages`, `GET /records/audit`, `GET /records/photos/{id}`,
`GET /records/stream` (SSE), `POST /records/announcements`,
`POST /records/missing/{id}/review`, plus `/auth/login` and `/me`.
Against an older server build the three newer endpoints answer 404 and
the dashboard falls back gracefully (locally-labelled announcements,
session-only reviews, audit derived from messages).

Note on SSE: the stream needs the `Authorization` header, which the
browser's `EventSource` cannot send — the dashboard therefore reads the
stream with a `fetch`-based reader and falls back to 2 s polling
automatically if the stream drops, retrying SSE in the background.

## Project structure

```
src/
├── api.ts    Typed API client (auth, records, photos, SSE reader, ML calls)
├── App.tsx   The whole console: login, KPIs, map, feed, queue, cases,
│             announcements, audit
├── main.tsx  Entry point
└── index.css Template styles (dark coordinator theme)
```
