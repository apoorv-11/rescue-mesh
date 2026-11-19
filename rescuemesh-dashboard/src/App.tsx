import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { MapContainer, TileLayer, CircleMarker, Polyline, Popup, useMap } from "react-leaflet";
import L from "leaflet";
import "leaflet.heat";
import {
  API_BASE,
  ApiError,
  fetchAnnouncements,
  fetchAudit,
  fetchMe,
  fetchMessages,
  fetchMissing,
  fetchPhotoBlob,
  fetchSos,
  loadToken,
  login,
  mlDuplicate,
  mlFaceMatch,
  mlPriority,
  openStream,
  publishAnnouncement,
  resolveSos,
  reviewMissing,
  saveToken,
  type AnnouncementDto,
  type AuditDto,
  type FaceMatchResult,
  type MeInfo,
  type MessageDto,
  type MissingCaseDto,
  type MlPriorityResult,
  type SosRecordDto,
} from "./api";

/* ============================================================
   RescueMesh Dashboard — functional build on the approved template.
   All data comes from the real server:
     GET /records/sos, /records/missing, /records/announcements,
     /records/messages, /records/photos/{messageId}
     Live: fetch-based SSE reader on GET /records/stream
     (records-changed / photo-changed), Polling-2s fallback.
   Newer server endpoints wired here: POST /records/announcements,
   POST /records/missing/{id}/review, POST /records/sos/{id}/resolve,
   GET /records/audit. Against an
   older server build those answer 404 and the dashboard falls back
   to the template behaviour (local-only announcements, session-only
   case review, messages-derived audit strip) — labelled honestly.
   ML service (separate, VITE_ML_BASE): POST /priority chips and
   /duplicate badges in the SOS queue, POST /face-match assist on
   case cards. Advisory only — when the ML service is unreachable
   the dashboard keeps the "not connected yet" label, shows no
   badges, and says the face check is unavailable. A score never
   reorders the queue and never closes a case.
   v4: the Live feed is rebuilt from server history on every load
   (synced records + announcements + persisted case reviews + sync
   audit), so a page reload no longer erases it — dashboard-published
   announcements exist only as Announcement rows, never as messages,
   and used to vanish from the feed on reload. Announcement
   "Valid until" badges now carry the date, not just the time.
   ============================================================ */

const CENTER: [number, number] = [28.6139, 77.209]; // New Delhi — default view

/* ---------- helpers ---------- */

function shortId(id: string | null | undefined, n = 8): string {
  if (!id) return "—";
  return id.length > n ? id.slice(0, n) : id;
}

function deviceLabel(deviceId: string): string {
  return `RM-${deviceId.slice(0, 12)}…`;
}

function typeLabel(t: string): string {
  return (
    {
      SOS: "SOS",
      SAFETY_CHECKIN: "I Am Safe",
      MISSING_PERSON: "Missing",
      PERSON_FOUND_UPDATE: "Found update",
      RESOURCE_UPDATE: "Resource",
      ANNOUNCEMENT: "Announcement",
    } as Record<string, string>
  )[t] ?? t;
}

function ageLabel(ms: number): string {
  const min = Math.max(0, Math.floor(ms / 60000));
  if (min < 1) return "just now";
  if (min < 60) return `${min} min ago`;
  return `${Math.floor(min / 60)} h ${min % 60} min ago`;
}

function clockTime(epochMs: number, withSec = false): string {
  return new Date(epochMs).toLocaleTimeString("en-IN", {
    hour: "2-digit",
    minute: "2-digit",
    second: withSec ? "2-digit" : undefined,
    hour12: false,
    timeZone: "Asia/Kolkata",
  });
}

function clockDate(epochMs: number): string {
  return new Date(epochMs).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    timeZone: "Asia/Kolkata",
  });
}

/* Feed timestamps: time only for today's entries; older entries carry
 * their date, so a history rebuilt after a reload stays readable. */
function feedTime(epochMs: number): string {
  return clockDate(epochMs) === clockDate(Date.now())
    ? clockTime(epochMs)
    : `${clockDate(epochMs)} · ${clockTime(epochMs)}`;
}

function payloadStr(p: Record<string, unknown>, key: string): string | null {
  const v = p[key];
  return typeof v === "string" && v.length > 0 ? v : null;
}

/* Pin / trail colours per record type — shared by the pins view and
 * the trails view so a type means the same colour in both. */
const PIN_COLORS: Record<string, string> = {
  SOS: "#d92d20",
  SAFETY_CHECKIN: "#12805c",
  MISSING_PERSON: "#b54708",
  PERSON_FOUND_UPDATE: "#7a5af8",
  RESOURCE_UPDATE: "#1d6fd1",
  ANNOUNCEMENT: "#667085",
};

/* A hop entry's `loc` is the string "<lat>,<lon>" (3 dp) the device
 * embedded when it handled the record. Parse it, or null. */
function parseHopLoc(loc: string | undefined): { lat: number; lng: number } | null {
  if (!loc) return null;
  const parts = loc.split(",");
  if (parts.length !== 2) return null;
  const lat = Number(parts[0]);
  const lng = Number(parts[1]);
  return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null;
}

function payloadLocation(
  p: Record<string, unknown>,
): { lat: number; lng: number } | null {
  const loc = p.location;
  if (loc && typeof loc === "object") {
    const rec = loc as Record<string, unknown>;
    /* The Android build sends lat/lon as 3-dp STRINGS ("28.613") —
     * accept strings as well as numbers, or those records never get
     * a map pin. */
    const lat =
      typeof rec.lat === "number"
        ? rec.lat
        : typeof rec.lat === "string"
          ? Number(rec.lat)
          : null;
    const lon =
      typeof rec.lon === "number"
        ? rec.lon
        : typeof rec.lon === "string"
          ? Number(rec.lon)
          : null;
    if (lat !== null && lon !== null && Number.isFinite(lat) && Number.isFinite(lon)) {
      return { lat, lng: lon };
    }
  }
  return null;
}

function payloadText(m: MessageDto): string {
  const p = m.payload;
  switch (m.eventType) {
    case "SOS":
      return payloadStr(p, "description") ?? payloadStr(p, "text") ?? "SOS — no description provided";
    case "MISSING_PERSON": {
      const name = payloadStr(p, "name");
      const desc = payloadStr(p, "description");
      return `Missing: ${name ?? "unknown"}${desc ? ` — ${desc}` : ""}`;
    }
    case "PERSON_FOUND_UPDATE": {
      const caseId = payloadStr(p, "caseId");
      const desc = payloadStr(p, "description") ?? payloadStr(p, "text");
      return `Found report for case ${shortId(caseId)}${desc ? ` — ${desc}` : ""}`;
    }
    case "ANNOUNCEMENT":
      return payloadStr(p, "text") ?? payloadStr(p, "description") ?? "Announcement";
    case "SAFETY_CHECKIN":
      return payloadStr(p, "text") ?? payloadStr(p, "description") ?? "I am safe";
    case "RESOURCE_UPDATE": {
      const cat = payloadStr(p, "category");
      return payloadStr(p, "text") ?? payloadStr(p, "description") ?? (cat ? `Resource: ${cat}` : "Resource update");
    }
    default:
      return payloadStr(p, "text") ?? payloadStr(p, "description") ?? m.eventType;
  }
}

/* The text sent to the ML service: the card's display text, but only
 * when the payload actually carries a text/description — records with
 * no real text are skipped (payloadText's synthetic fallbacks like
 * "SOS — no description provided" would be noise to the model). */
function mlText(m: MessageDto): string | null {
  const has = payloadStr(m.payload, "text") ?? payloadStr(m.payload, "description");
  return has ? payloadText(m) : null;
}

/* Scores arrive as 0..1 probabilities; render as a whole percent.
 * The >1 guard keeps a mis-scaled service value from printing 8200%. */
function scorePct(score: number): number {
  return Math.round(score <= 1 ? score * 100 : score);
}

function PriBadge({ pri }: { pri: number }) {
  if (pri === 1) return <span className="badge p1">P1 · Critical</span>;
  if (pri === 2) return <span className="badge p2">P2 · High</span>;
  return <span className="badge">P3 · Normal</span>;
}

/* ============================================================
   App root — auth gate
   ============================================================ */

export default function App() {
  const [token, setToken] = useState<string | null>(() => loadToken());
  const [me, setMe] = useState<MeInfo | null>(null);
  const [booting, setBooting] = useState<boolean>(() => loadToken() !== null);
  const [bootError, setBootError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setBooting(false);
      return;
    }
    let alive = true;
    fetchMe(token)
      .then((info) => {
        if (!alive) return;
        setMe(info);
        setBooting(false);
      })
      .catch((e: unknown) => {
        if (!alive) return;
        if (e instanceof ApiError && e.status === 401) {
          saveToken(null);
          setToken(null);
        } else {
          setBootError(e instanceof Error ? e.message : "Could not reach the server");
        }
        setBooting(false);
      });
    return () => {
      alive = false;
    };
  }, [token]);

  const handleLogin = useCallback((newToken: string, info: MeInfo) => {
    saveToken(newToken);
    setToken(newToken);
    setMe(info);
    setBootError(null);
  }, []);

  const handleLogout = useCallback(() => {
    saveToken(null);
    setToken(null);
    setMe(null);
  }, []);

  if (booting) {
    return (
      <div className="login-wrap">
        <div className="card login-card">
          <div className="brand">
            <div className="brand-mark" aria-hidden="true">RM</div>
            <h1>RescueMesh Dashboard<small>Coordinator console</small></h1>
          </div>
          <p className="login-note">Checking your session…</p>
        </div>
      </div>
    );
  }

  if (!token || !me) {
    return <LoginScreen onLogin={handleLogin} initialError={bootError} />;
  }

  return <Dashboard token={token} me={me} onLogout={handleLogout} />;
}

/* ============================================================
   Login
   ============================================================ */

function LoginScreen({
  onLogin,
  initialError,
}: {
  onLogin: (token: string, me: MeInfo) => void;
  initialError: string | null;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(initialError);
  const [busy, setBusy] = useState(false);

  async function submit(ev: React.FormEvent) {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const token = await login(username.trim(), password);
      const me = await fetchMe(token);
      onLogin(token, me);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Login failed");
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submit}>
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">RM</div>
          <h1>RescueMesh Dashboard<small>Coordinator console · sign in</small></h1>
        </div>
        {error && <div className="login-err">{error}</div>}
        <label>
          Username
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <button className="btn primary" type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        <p className="login-note">
          Server: <span className="mono">{API_BASE}</span> — use the same account as the
          RescueMesh app. CITIZEN accounts are read-only here; case review needs
          COORDINATOR or ADMIN.
        </p>
      </form>
    </div>
  );
}

/* ============================================================
   Dashboard
   ============================================================ */

type ConnState = "connecting" | "sse" | "polling";
type Mode = "sse" | "polling";

interface FeedItem {
  id: number;
  time: string;
  text: string;
  flash: boolean;
}

/* One feed-history candidate before it is sorted/merged into the feed. */
interface FeedSeed {
  epoch: number;
  text: string;
}

/* The feed keeps the newest 20 history/live entries (v4: was a
 * 5-entry seed and a 14-entry cap, which lost context on reload). */
const FEED_LIMIT = 20;

interface QueueRow {
  messageId: string;
  type: string;
  pri: number;
  text: string;
  area: string;
  hops: number;
  createdAt: number;
  statusLabel: string;
  statusClass: string;
  originDeviceId: string;
  /* SAFETY_CHECKIN rows are grouped per device: this counts every
   * check-in the device sent; the row itself is the newest one. */
  checkinCount: number;
  /* False when the record carries no real text for the ML service —
   * those rows show no ML slot at all (not a misleading
   * "not connected yet"). */
  mlEligible: boolean;
}

interface Pin {
  key: string;
  lat: number;
  lng: number;
  color: string;
  title: string;
  text: string;
}

/* One record's located path for the trails view: origin location
 * followed by each hop's embedded location, in order. */
interface Trail {
  key: string;
  color: string;
  points: { lat: number; lng: number }[];
  title: string;
  text: string;
}

interface LocalAnnouncement {
  localId: string;
  title: string;
  body: string;
  validH: number;
  createdAt: number;
}

function Dashboard({
  token,
  me,
  onLogout,
}: {
  token: string;
  me: MeInfo;
  onLogout: () => void;
}) {
  const [sos, setSos] = useState<SosRecordDto[]>([]);
  const [cases, setCases] = useState<MissingCaseDto[]>([]);
  const [announcements, setAnnouncements] = useState<AnnouncementDto[]>([]);
  const [messages, setMessages] = useState<MessageDto[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const [mode, setMode] = useState<Mode>("sse");
  const [conn, setConn] = useState<ConnState>("connecting");

  const [feed, setFeed] = useState<FeedItem[]>([]);
  const [feedPaused, setFeedPaused] = useState(false);
  const feedPausedRef = useRef(false);
  const feedIdRef = useRef(0);
  const knownIdsRef = useRef<Set<string>>(new Set());
  const knownAnnIdsRef = useRef<Set<number>>(new Set());
  const knownAuditIdsRef = useRef<Set<number>>(new Set());
  const knownReviewKeysRef = useRef<Set<string>>(new Set());
  const firstLoadRef = useRef(true);

  const [fType, setFType] = useState("ALL");
  const [fPri, setFPri] = useState("ALL");
  const [fSearch, setFSearch] = useState("");

  const [localAnn, setLocalAnn] = useState<LocalAnnouncement[]>([]);
  const [annBusy, setAnnBusy] = useState(false);
  const [annError, setAnnError] = useState<string | null>(null);
  // True once the server has answered 404 to a publish — older build.
  const [annEndpointMissing, setAnnEndpointMissing] = useState(false);

  // Session-only review fallback (older server): caseKey → outcome label.
  const [review, setReview] = useState<Record<string, string>>({});
  const [reviewLocalOnly, setReviewLocalOnly] = useState<Record<string, boolean>>({});
  const [reviewBusy, setReviewBusy] = useState<Record<string, boolean>>({});
  const [reviewError, setReviewError] = useState<Record<string, string>>({});

  // SOS resolve (server-persisted): busy + per-row error, keyed by messageId.
  const [resolveBusy, setResolveBusy] = useState<Record<string, boolean>>({});
  const [resolveError, setResolveError] = useState<Record<string, string>>({});

  // Server sync-audit rows; null = endpoint unavailable (older server) or
  // not loaded yet — the strip then derives from /records/messages.
  const [auditRows, setAuditRows] = useState<AuditDto[] | null>(null);

  // ML assist (advisory only): priority scores + duplicate flags keyed
  // by messageId. Every messageId is attempted exactly once per session
  // (the tried-refs); a failed call simply never lands in state, so the
  // honest "not connected yet" label stays and no badges appear.
  const [mlScores, setMlScores] = useState<Record<string, MlPriorityResult>>({});
  const [mlDupes, setMlDupes] = useState<ReadonlySet<string>>(new Set());
  const mlPriorityTriedRef = useRef<Set<string>>(new Set());
  const mlDupeTriedRef = useRef<Set<string>>(new Set());

  const [clock, setClock] = useState("--:--:--");
  const [, setTick] = useState(0);

  const canReview = me.role === "COORDINATOR" || me.role === "ADMIN";

  /* ---------- clock + age re-render ---------- */
  useEffect(() => {
    const t = window.setInterval(() => {
      setClock(
        new Date().toLocaleTimeString("en-IN", {
          hour12: false,
          timeZone: "Asia/Kolkata",
        }),
      );
      setTick((n) => n + 1);
    }, 1000);
    return () => window.clearInterval(t);
  }, []);

  /* ---------- feed ---------- */
  const pushFeed = useCallback((text: string, flash = true) => {
    if (feedPausedRef.current) return;
    feedIdRef.current += 1;
    const item: FeedItem = {
      id: feedIdRef.current,
      time: clockTime(Date.now()),
      text,
      flash,
    };
    setFeed((prev) => [item, ...prev].slice(0, FEED_LIMIT));
  }, []);

  /* ---------- data refresh ---------- */
  const refresh = useCallback(async () => {
    try {
      const [sosData, caseData, annData, msgData, auditData] = await Promise.all([
        fetchSos(token),
        fetchMissing(token),
        fetchAnnouncements(token),
        fetchMessages(token),
        // /records/audit exists only on newer server builds — a 404 (or any
        // failure of this one call) must not fail the whole refresh; the
        // strip falls back to deriving from /records/messages.
        fetchAudit(token).catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 401) throw e;
          return null;
        }),
      ]);
      setSos(sosData);
      setCases(caseData);
      setAnnouncements(annData);
      setMessages(msgData);
      setAuditRows(auditData);
      setLoadFailed(false);
      setLoaded(true);

      // Feed: seed on first load, then announce genuinely new records.
      const caseById = new Map(
        caseData.filter((c) => c.messageId).map((c) => [c.messageId as string, c]),
      );
      const describe = (m: MessageDto): string => {
        const area =
          payloadStr(m.payload, "lastSeenArea") ??
          caseById.get(m.messageId)?.lastSeenArea ??
          "unknown area";
        return `${typeLabel(m.eventType)} synced — ${area} (P${m.priority}, ${m.hopCount} hops)`;
      };
      // Feed history (v4): the feed is rebuilt from the server on the
      // first load, not only from this session's live events — a page
      // reload must not erase it. Sources: synced records, announcements,
      // persisted case reviews, and sync-batch audit rows. A dashboard-
      // published announcement exists ONLY as an Announcement row, never
      // as a message — that is why it used to vanish from the feed on
      // reload. A phone-synced announcement exists as BOTH a message and
      // an announcement row with the same createdAt; the row copy is
      // skipped so it is never reported twice.
      const syncedAnnTimes = new Set(
        msgData.filter((m) => m.eventType === "ANNOUNCEMENT").map((m) => m.createdAt),
      );
      const annEntry = (a: AnnouncementDto): FeedSeed => ({
        epoch: a.createdAt,
        text: `Announcement published — “${a.title || a.body}”`,
      });
      const reviewKey = (c: MissingCaseDto): string => `${c.messageId}@${c.reviewedAt}`;
      const reviewEntry = (c: MissingCaseDto): FeedSeed => ({
        epoch: c.reviewedAt as number,
        text: `Coordinator review on case ${shortId(c.messageId)} — ${
          c.reviewStatus === "CONFIRMED"
            ? "match confirmed (saved to server)"
            : "found report rejected (saved to server), case stays open"
        }${c.reviewedBy ? ` · by ${c.reviewedBy}` : ""}`,
      });
      const auditEntry = (a: AuditDto): FeedSeed => ({
        epoch: a.createdAt,
        text: `Sync batch received — ${a.deviceId ? deviceLabel(a.deviceId) : "unknown device"}${a.detail ? ` · ${a.detail}` : ""}`,
      });
      const reviewedCases = caseData.filter(
        (c) => c.messageId && c.reviewStatus && c.reviewedAt,
      );

      if (firstLoadRef.current) {
        firstLoadRef.current = false;
        knownIdsRef.current = new Set(msgData.map((m) => m.messageId));
        knownAnnIdsRef.current = new Set(annData.map((a) => a.id));
        knownAuditIdsRef.current = new Set((auditData ?? []).map((a) => a.id));
        knownReviewKeysRef.current = new Set(reviewedCases.map(reviewKey));
        const history: FeedSeed[] = [
          ...msgData.map((m) => ({ epoch: m.createdAt, text: describe(m) })),
          ...annData.filter((a) => !syncedAnnTimes.has(a.createdAt)).map(annEntry),
          ...reviewedCases.map(reviewEntry),
          ...(auditData ?? []).map(auditEntry),
        ]
          .sort((x, y) => y.epoch - x.epoch)
          .slice(0, FEED_LIMIT);
        setFeed(
          history.map((h, i) => ({
            id: -1 - i,
            time: feedTime(h.epoch),
            text: h.text,
            flash: false,
          })),
        );
      } else {
        // Later refreshes (SSE / polling): announce genuinely new items
        // from every source, oldest first so the newest ends on top.
        const fresh: FeedSeed[] = [];
        const freshMsgs = msgData.filter((m) => !knownIdsRef.current.has(m.messageId));
        for (const m of freshMsgs) knownIdsRef.current.add(m.messageId);
        fresh.push(...freshMsgs.map((m) => ({ epoch: m.createdAt, text: describe(m) })));
        const freshAnn = annData.filter((a) => !knownAnnIdsRef.current.has(a.id));
        for (const a of freshAnn) knownAnnIdsRef.current.add(a.id);
        fresh.push(...freshAnn.filter((a) => !syncedAnnTimes.has(a.createdAt)).map(annEntry));
        const freshReviews = reviewedCases.filter(
          (c) => !knownReviewKeysRef.current.has(reviewKey(c)),
        );
        for (const c of freshReviews) knownReviewKeysRef.current.add(reviewKey(c));
        fresh.push(...freshReviews.map(reviewEntry));
        if (auditData) {
          const freshAudit = auditData.filter((a) => !knownAuditIdsRef.current.has(a.id));
          for (const a of freshAudit) knownAuditIdsRef.current.add(a.id);
          fresh.push(...freshAudit.map(auditEntry));
        }
        fresh.sort((x, y) => x.epoch - y.epoch);
        for (const f of fresh) pushFeed(f.text);
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onLogout();
        return;
      }
      setLoadFailed(true);
    }
  }, [token, onLogout, pushFeed]);

  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;

  useEffect(() => {
    void refresh();
  }, [refresh]);

  /* ---------- ML assist: score + duplicate-check each new record once ----------
   * Display only. Results are cached by messageId; a record is never
   * re-scored on re-render or refresh, and queue order never changes. */
  useEffect(() => {
    const textBearing = messages
      .map((m) => ({ m, text: mlText(m) }))
      .filter(
        (x): x is { m: MessageDto; text: string } => x.text !== null,
      );
    if (textBearing.length === 0) return;
    let cancelled = false;

    for (const { m, text } of textBearing) {
      if (!mlPriorityTriedRef.current.has(m.messageId)) {
        mlPriorityTriedRef.current.add(m.messageId);
        mlPriority(text)
          .then((r) => {
            if (!cancelled) {
              setMlScores((prev) => ({ ...prev, [m.messageId]: r }));
            }
          })
          .catch(() => {
            /* ML unavailable — the "not connected yet" label stays */
          });
      }

      if (!mlDupeTriedRef.current.has(m.messageId)) {
        mlDupeTriedRef.current.add(m.messageId);
        const candidates = textBearing
          .filter((x) => x.m.messageId !== m.messageId)
          .slice(0, 50)
          .map((x) => ({ id: x.m.messageId, text: x.text }));
        if (candidates.length > 0) {
          mlDuplicate(text, candidates)
            .then((matches) => {
              if (cancelled || matches.length === 0) return;
              setMlDupes((prev) => {
                const next = new Set(prev);
                next.add(m.messageId);
                for (const mt of matches) next.add(mt.id);
                return next;
              });
            })
            .catch(() => {
              /* ML unavailable — no duplicate badges */
            });
        }
      }
    }
    return () => {
      cancelled = true;
    };
  }, [messages]);

  /* ---------- live updates: SSE with polling fallback ---------- */
  useEffect(() => {
    let cancelled = false;
    let pollTimer: number | undefined;
    const startPolling = () => {
      if (pollTimer === undefined) {
        pollTimer = window.setInterval(() => {
          void refreshRef.current();
        }, 2000);
      }
    };
    const stopPolling = () => {
      if (pollTimer !== undefined) {
        window.clearInterval(pollTimer);
        pollTimer = undefined;
      }
    };

    if (mode === "polling") {
      setConn("polling");
      startPolling();
    } else {
      const ac = new AbortController();
      setConn("connecting");
      void (async () => {
        while (!cancelled) {
          try {
            await openStream(
              token,
              () => {
                if (cancelled) return;
                setConn("sse");
                stopPolling();
                void refreshRef.current();
              },
              () => {
                if (cancelled) return;
                setConn("sse");
                stopPolling();
              },
              ac.signal,
            );
          } catch {
            /* stream failed or was aborted — fall back below */
          }
          if (cancelled) break;
          // Stream unavailable/ended: poll every 2s while retrying SSE.
          setConn("polling");
          startPolling();
          await new Promise((r) => setTimeout(r, 15000));
          if (!cancelled) stopPolling();
        }
      })();
      return () => {
        cancelled = true;
        ac.abort();
        stopPolling();
      };
    }
    return () => {
      cancelled = true;
      stopPolling();
    };
  }, [token, mode]);

  /* ---------- derived data ---------- */
  const sosById = useMemo(
    () => new Map(sos.map((s) => [s.messageId, s])),
    [sos],
  );
  const caseByMsgId = useMemo(
    () =>
      new Map(
        cases.filter((c) => c.messageId).map((c) => [c.messageId as string, c]),
      ),
    [cases],
  );
  const msgById = useMemo(
    () => new Map(messages.map((m) => [m.messageId, m])),
    [messages],
  );

  /* Every found report per case, newest first. The server's case view
   * links only the newest found update (foundMessageId); the rest are
   * still stored messages — the case card shows them all in a strip so
   * a second person's report is never silently displaced. */
  const foundReportsByCase = useMemo(() => {
    const map = new Map<string, MessageDto[]>();
    for (const m of messages) {
      if (m.eventType !== "PERSON_FOUND_UPDATE") continue;
      const caseId = payloadStr(m.payload, "caseId");
      if (!caseId) continue;
      const arr = map.get(caseId) ?? [];
      arr.push(m);
      map.set(caseId, arr);
    }
    for (const arr of map.values()) {
      arr.sort((a, b) => b.createdAt - a.createdAt);
    }
    return map;
  }, [messages]);

  const queueRows = useMemo<QueueRow[]>(() => {
    const rows = messages
      .map((m) => {
        const sosRec = sosById.get(m.messageId);
        const loc = payloadLocation(m.payload);
        const lat = sosRec?.latitude ?? loc?.lat ?? null;
        const lng = sosRec?.longitude ?? loc?.lng ?? null;
        const area =
          payloadStr(m.payload, "lastSeenArea") ??
          caseByMsgId.get(m.messageId)?.lastSeenArea ??
          payloadStr(m.payload, "area") ??
          (lat !== null && lng !== null
            ? `${lat.toFixed(4)}, ${lng.toFixed(4)}`
            : "—");
        let statusLabel = "Server synced";
        let statusClass = "badge ok";
        if (m.eventType === "SOS" && sosRec) {
          if (sosRec.status === "OPEN") {
            statusLabel = "Open";
            statusClass = "badge p2";
          } else if (sosRec.status === "ACKNOWLEDGED") {
            statusLabel = "Acknowledged";
            statusClass = "badge";
          } else if (sosRec.status === "RESOLVED") {
            statusLabel = "Resolved";
            statusClass = "badge ok";
          }
        }
        return {
          messageId: m.messageId,
          type: m.eventType,
          pri: m.priority,
          text: payloadText(m),
          area,
          hops: m.hopCount,
          createdAt: m.createdAt,
          statusLabel,
          statusClass,
          originDeviceId: m.originDeviceId,
          checkinCount: 1,
          mlEligible: mlText(m) !== null,
        };
      });
    /* Group "I Am Safe" check-ins per device: one row per device (its
     * newest check-in) with the total count, instead of one row per tap.
     * Display only — the server keeps every check-in as its own record. */
    const grouped: QueueRow[] = [];
    const checkinByDevice = new Map<string, QueueRow>();
    for (const row of rows) {
      if (row.type !== "SAFETY_CHECKIN") {
        grouped.push(row);
        continue;
      }
      const prev = checkinByDevice.get(row.originDeviceId);
      if (!prev) {
        checkinByDevice.set(row.originDeviceId, row);
        grouped.push(row);
      } else {
        const count = prev.checkinCount + 1;
        if (row.createdAt > prev.createdAt) {
          Object.assign(prev, row);
        }
        prev.checkinCount = count;
      }
    }
    return grouped.sort((a, b) => a.pri - b.pri || a.createdAt - b.createdAt);
  }, [messages, sosById, caseByMsgId]);

  const filteredRows = useMemo(() => {
    const q = fSearch.trim().toLowerCase();
    return queueRows.filter((r) => {
      if (fType !== "ALL" && r.type !== fType) return false;
      if (fPri !== "ALL" && r.pri !== Number(fPri)) return false;
      if (q && `${r.text} ${r.area} ${r.messageId}`.toLowerCase().indexOf(q) === -1)
        return false;
      return true;
    });
  }, [queueRows, fType, fPri, fSearch]);

  const pins = useMemo<Pin[]>(() => {
    /* Every record that carries a location gets a pin, coloured by
     * type: SOS (from the server's extracted coordinates, falling
     * back to the payload), I Am Safe check-ins, Missing, Found
     * updates, Resources, Announcements. Records without a location
     * in the payload simply have no pin — nothing is invented. */
    const out: Pin[] = [];
    const pinnedSos = new Set<string>();
    for (const s of sos) {
      if (s.latitude !== null && s.longitude !== null) {
        pinnedSos.add(s.messageId);
        out.push({
          key: `sos-${s.messageId}`,
          lat: s.latitude,
          lng: s.longitude,
          color: PIN_COLORS.SOS,
          title: "SOS",
          text: s.note ?? "SOS record",
        });
      }
    }
    for (const m of messages) {
      if (m.eventType === "SOS" && pinnedSos.has(m.messageId)) continue;
      const loc = payloadLocation(m.payload);
      if (!loc) continue;
      out.push({
        key: `msg-${m.messageId}`,
        lat: loc.lat,
        lng: loc.lng,
        color: PIN_COLORS[m.eventType] ?? "#1d6fd1",
        title: typeLabel(m.eventType),
        text: payloadText(m),
      });
    }
    return out;
  }, [sos, messages]);

  const [mapMode, setMapMode] = useState<"pins" | "trails">("pins");

  /* Hop trails: for each recent record, the path its locations make —
   * the origin's payload location followed by every hop entry's
   * embedded `loc`, in order. Only records with at least two located
   * points can draw a trail; capped at the 12 most recent. */
  const trails = useMemo(() => {
    const out: Trail[] = [];
    for (const m of messages) {
      const pts: { lat: number; lng: number }[] = [];
      const push = (p: { lat: number; lng: number } | null) => {
        if (!p) return;
        const last = pts[pts.length - 1];
        if (!last || last.lat !== p.lat || last.lng !== p.lng) pts.push(p);
      };
      push(payloadLocation(m.payload));
      if (Array.isArray(m.hopTrace)) {
        for (const h of m.hopTrace) push(parseHopLoc(h.loc));
      }
      if (pts.length < 2) continue;
      out.push({
        key: `trail-${m.messageId}`,
        color: PIN_COLORS[m.eventType] ?? "#1d6fd1",
        points: pts,
        title: typeLabel(m.eventType),
        text: `${deviceLabel(m.originDeviceId)} · ${m.hopCount} hops · ${ageLabel(Date.now() - m.createdAt)}`,
      });
      if (out.length >= 12) break;
    }
    return out;
  }, [messages]);

  /* Heat points: every location that arrived with a record — payload
   * locations plus every hop entry's embedded location. */
  const heatPoints = useMemo<[number, number, number][]>(() => {
    const pts: [number, number, number][] = [];
    for (const m of messages) {
      const loc = payloadLocation(m.payload);
      if (loc) pts.push([loc.lat, loc.lng, 1]);
      if (Array.isArray(m.hopTrace)) {
        for (const h of m.hopTrace) {
          const p = parseHopLoc(h.loc);
          if (p) pts.push([p.lat, p.lng, 1]);
        }
      }
    }
    return pts;
  }, [messages]);

  const kpis = useMemo(() => {
    const now = Date.now();
    const activeSosRecs = sos.filter((s) => s.status !== "RESOLVED");
    const activeIds = new Set(activeSosRecs.map((s) => s.messageId));
    const p1 = messages.filter(
      (m) => m.eventType === "SOS" && m.priority === 1 && activeIds.has(m.messageId),
    ).length;
    const safe24h = messages.filter(
      (m) => m.eventType === "SAFETY_CHECKIN" && now - m.createdAt < 24 * 3600 * 1000,
    ).length;
    const startOfToday = new Date();
    startOfToday.setHours(0, 0, 0, 0);
    const syncedToday = messages.filter((m) => m.createdAt >= startOfToday.getTime()).length;
    /* Missing cases split by coordinator review: a case CONFIRMED found
     * is closed work — it leaves the "still open" headline number and
     * shows in its own confirmed count instead. REJECTED found reports
     * keep the case in the open number (the person is still missing). */
    let confirmedFound = 0;
    let awaitingReview = 0;
    for (const c of cases) {
      const key = c.messageId ?? `case-${c.id}`;
      const rs = review[key] ?? c.reviewStatus;
      if (rs === "CONFIRMED") confirmedFound++;
      /* "Awaiting" means a found report arrived and NO decision exists
       * yet — a REJECTED case is not awaiting anything, it is simply
       * open again (it stays in the headline number only). */
      else if (c.status === "FOUND_REPORTED" && !rs) awaitingReview++;
    }
    const openCases = cases.length - confirmedFound;
    return {
      activeSos: activeSosRecs.length,
      p1,
      openCases,
      confirmedFound,
      awaitingReview,
      safe24h,
      syncedToday,
    };
  }, [sos, cases, messages, review]);

  const auditItems = useMemo(() => messages.slice(0, 6), [messages]);

  /* ---------- connection pill (honest state, never a fake "Live") ---------- */
  const connText = loadFailed
    ? `Server unreachable — retrying (${API_BASE})`
    : conn === "sse"
      ? "Connected · SSE live updates"
      : conn === "polling"
        ? mode === "polling"
          ? "Connected · Polling 2s"
          : "Polling 2s · SSE unavailable, retrying"
        : "Connecting…";
  const connLive = !loadFailed && conn !== "connecting";

  /* ---------- announcements: POST to the server; 404 → local-only ---------- */
  async function submitAnnouncement(ev: React.FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const form = ev.currentTarget;
    const data = new FormData(form);
    const title = String(data.get("title") ?? "").trim();
    const body = String(data.get("body") ?? "").trim();
    const validH = Number(data.get("valid") ?? 24);
    const priority = Number(data.get("priority") ?? 3);
    if (!title || !body || annBusy) return;
    setAnnBusy(true);
    setAnnError(null);
    try {
      await publishAnnouncement(token, {
        title,
        body,
        priority,
        validUntil: Date.now() + validH * 3600 * 1000,
      });
      // Published on the server — refetch so the item renders as a normal
      // server announcement (no local-only badge). Mark the refetched ids
      // as known so the next refresh does not re-announce this publish in
      // the feed (the local pushFeed below is its one feed entry).
      try {
        const anns = await fetchAnnouncements(token);
        setAnnouncements(anns);
        for (const a of anns) knownAnnIdsRef.current.add(a.id);
      } catch {
        void refreshRef.current();
      }
      pushFeed(`Announcement published to the server — “${title}”`);
      form.reset();
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        // Older server build: no publish endpoint — keep the template
        // behaviour exactly (local item + “Local only” badge + note).
        setAnnEndpointMissing(true);
        setLocalAnn((prev) => [
          { localId: `local-${Date.now()}`, title, body, validH, createdAt: Date.now() },
          ...prev,
        ]);
        pushFeed(`Announcement composed locally — “${title}” (not sent: no server endpoint yet)`);
        form.reset();
      } else if (e instanceof ApiError && e.status === 401) {
        onLogout();
      } else {
        // 403 / network / 500 — surface it on the form, add nothing silently.
        setAnnError(
          e instanceof Error ? e.message : "Could not publish the announcement",
        );
      }
    } finally {
      setAnnBusy(false);
    }
  }

  /* ---------- case review: POST to the server; 404 → session-only ---------- */
  async function doReview(c: MissingCaseDto, act: "confirm" | "reject") {
    const caseKey = c.messageId ?? `case-${c.id}`;
    const caseLabel = `case ${shortId(c.messageId)}`;
    if (!c.messageId || reviewBusy[caseKey]) return;
    setReviewBusy((prev) => ({ ...prev, [caseKey]: true }));
    setReviewError((prev) => {
      const next = { ...prev };
      delete next[caseKey];
      return next;
    });
    try {
      await reviewMissing(token, c.messageId, act === "confirm" ? "CONFIRMED" : "REJECTED");
      // Saved on the server — refetch so the card state comes from the
      // server's reviewStatus / reviewedBy / reviewedAt fields. Mark the
      // refetched reviews as known so the next refresh does not
      // re-announce this review in the feed (the local pushFeed below
      // is its one feed entry).
      try {
        const cs = await fetchMissing(token);
        setCases(cs);
        for (const c of cs) {
          if (c.messageId && c.reviewStatus && c.reviewedAt) {
            knownReviewKeysRef.current.add(`${c.messageId}@${c.reviewedAt}`);
          }
        }
      } catch {
        void refreshRef.current();
      }
      setReview((prev) => {
        const next = { ...prev };
        delete next[caseKey];
        return next;
      });
      pushFeed(
        `Coordinator review on ${caseLabel} — ${
          act === "confirm"
            ? "match confirmed (saved to server)"
            : "found report rejected (saved to server), case stays open"
        }`,
      );
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        // Older server build: no review endpoint — session-only, as before.
        setReviewLocalOnly((prev) => ({ ...prev, [caseKey]: true }));
        setReview((prev) => ({
          ...prev,
          [caseKey]: act === "confirm" ? "CONFIRMED FOUND" : "OPEN",
        }));
        pushFeed(
          `Coordinator review on ${caseLabel} — ${
            act === "confirm"
              ? "match confirmed locally (not saved to server)"
              : "found report rejected locally, case stays open"
          }`,
        );
      } else if (e instanceof ApiError && e.status === 401) {
        onLogout();
      } else {
        setReviewError((prev) => ({
          ...prev,
          [caseKey]:
            e instanceof Error ? e.message : "Could not save the review to the server",
        }));
      }
    } finally {
      setReviewBusy((prev) => ({ ...prev, [caseKey]: false }));
    }
  }

  /* ---------- SOS resolve: POST to the server; errors stay on the row ---------- */
  async function doResolve(r: QueueRow) {
    if (resolveBusy[r.messageId]) return;
    setResolveBusy((prev) => ({ ...prev, [r.messageId]: true }));
    setResolveError((prev) => {
      const next = { ...prev };
      delete next[r.messageId];
      return next;
    });
    try {
      const updated = await resolveSos(token, r.messageId);
      setSos((prev) =>
        prev.map((s) => (s.messageId === r.messageId ? { ...s, ...updated } : s)),
      );
      pushFeed(`SOS resolved — msg ${shortId(r.messageId)} (saved to server)`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onLogout();
      } else if (e instanceof ApiError && e.status === 404) {
        setResolveError((prev) => ({
          ...prev,
          [r.messageId]: "Resolve needs the updated server build",
        }));
      } else {
        setResolveError((prev) => ({
          ...prev,
          [r.messageId]:
            e instanceof Error ? e.message : "Could not resolve this SOS",
        }));
      }
    } finally {
      setResolveBusy((prev) => ({ ...prev, [r.messageId]: false }));
    }
  }

  return (
    <>
      <header className="topbar">
        <div className="topbar-in">
          <div className="brand">
            <div className="brand-mark" aria-hidden="true">RM</div>
            <h1>
              RescueMesh Dashboard<small>Coordinator console</small>
            </h1>
          </div>
          <div className="topbar-spacer"></div>
          <span className="pill">
            {me.username} · {me.role}
          </span>
          <span className="pill">
            <span className={connLive ? "dot" : "dot off"}></span>
            <span>{connText}</span>
          </span>
          <div className="seg" role="group" aria-label="Live update mode">
            <button
              type="button"
              aria-pressed={mode === "sse"}
              onClick={() => setMode("sse")}
            >
              SSE
            </button>
            <button
              type="button"
              aria-pressed={mode === "polling"}
              onClick={() => setMode("polling")}
            >
              Polling 2s
            </button>
          </div>
          <span className="clock mono">{clock} IST</span>
          <button className="btn" type="button" onClick={onLogout}>
            Log out
          </button>
        </div>
      </header>

      <div className="wrap">
        {loadFailed && (
          <div className="conn-banner">
            Cannot reach the RescueMesh server at {API_BASE}. Showing the last data
            received (if any) and retrying automatically — check that the server is
            running.
          </div>
        )}

        {/* ================= KPI STRIP ================= */}
        <section className="kpis" aria-label="Summary">
          <div className="card kpi red">
            <div className="num">{kpis.activeSos}</div>
            <div className="lbl">Active SOS</div>
            <div className="delta">{kpis.p1} critical (P1)</div>
          </div>
          <div className="card kpi">
            <div className="num">{kpis.openCases}</div>
            <div className="lbl">Missing cases still open</div>
            <div className="delta">
              {kpis.confirmedFound} confirmed found · {kpis.awaitingReview} awaiting
              review
            </div>
          </div>
          <div className="card kpi green">
            <div className="num">{kpis.safe24h}</div>
            <div className="lbl">“I Am Safe” check-ins (24h)</div>
            <div className="delta">Never gated by login</div>
          </div>
          <div className="card kpi amber">
            <div className="num">{kpis.syncedToday}</div>
            <div className="lbl">Records synced today</div>
            <div className="delta">Via gateway phones</div>
          </div>
        </section>

        {/* ================= MAP + LIVE FEED ================= */}
        <section className="mapfeed">
          <div className="card">
            <div className="card-head">
              <h2>Incident map</h2>
              <span className="sub">Every record with a location</span>
              <div className="right legend">
                <span><i style={{ background: "var(--red)" }}></i>SOS</span>
                <span><i style={{ background: "#12805c" }}></i>I Am Safe</span>
                <span><i style={{ background: "var(--amber)" }}></i>Missing</span>
                <span><i style={{ background: "#7a5af8" }}></i>Found</span>
                <span><i style={{ background: "var(--accent)" }}></i>Resource</span>
              </div>
            </div>
            <div style={{ padding: "0 16px 10px" }}>
              <div className="seg" role="group" aria-label="Map view">
                <button
                  aria-pressed={mapMode === "pins"}
                  onClick={() => setMapMode("pins")}
                >
                  Pins
                </button>
                <button
                  aria-pressed={mapMode === "trails"}
                  onClick={() => setMapMode("trails")}
                >
                  Hop trails + heat
                </button>
              </div>
            </div>
            <IncidentMap
              pins={pins}
              mode={mapMode}
              trails={trails}
              heatPoints={heatPoints}
            />
            {mapMode === "trails" && trails.length === 0 && (
              <p className="review-note" style={{ padding: "10px 16px" }}>
                No hop-trail locations yet — trails appear once phones embed
                their location in hop entries (Android location update). The
                heat layer is built only from locations that arrived with
                records ({heatPoints.length} point{heatPoints.length === 1 ? "" : "s"} so far).
              </p>
            )}
          </div>

          <div className="card feed">
            <div className="card-head">
              <h2>Live feed</h2>
              <span className="sub">{feedPaused ? "paused" : "server history + live events"}</span>
              <div className="right">
                <button
                  className="btn"
                  type="button"
                  aria-pressed={feedPaused}
                  onClick={() => {
                    setFeedPaused((p) => {
                      feedPausedRef.current = !p;
                      return !p;
                    });
                  }}
                >
                  {feedPaused ? "Resume" : "Pause"}
                </button>
              </div>
            </div>
            <div className="feed-list" aria-live="polite">
              {feed.length === 0 && (
                <div className="feed-item">
                  <span className="m">
                    {loaded ? "No records yet — new syncs will appear here." : "Loading records…"}
                  </span>
                </div>
              )}
              {feed.map((f) => (
                <div key={f.id} className={f.flash ? "feed-item flash" : "feed-item"}>
                  <span className="t">{f.time}</span>
                  <span className="m">{f.text}</span>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* ================= SOS QUEUE ================= */}
        <section className="card">
          <div className="card-head">
            <h2>SOS queue</h2>
            <span className="sub">Priority first, then oldest — same order the mesh relays in</span>
            <div className="right filters">
              <select
                value={fType}
                onChange={(e) => setFType(e.target.value)}
                aria-label="Filter by type"
              >
                <option value="ALL">All types</option>
                <option value="SOS">SOS</option>
                <option value="SAFETY_CHECKIN">I Am Safe</option>
                <option value="MISSING_PERSON">Missing person</option>
                <option value="PERSON_FOUND_UPDATE">Found update</option>
                <option value="RESOURCE_UPDATE">Resource update</option>
                <option value="ANNOUNCEMENT">Announcement</option>
              </select>
              <select
                value={fPri}
                onChange={(e) => setFPri(e.target.value)}
                aria-label="Filter by priority"
              >
                <option value="ALL">All priorities</option>
                <option value="1">P1 · Critical</option>
                <option value="2">P2 · High</option>
                <option value="3">P3 · Normal</option>
              </select>
              <input
                type="search"
                value={fSearch}
                onChange={(e) => setFSearch(e.target.value)}
                placeholder="Search area, device, text…"
                aria-label="Search records"
              />
            </div>
          </div>
          <table className="sos-table">
            <thead>
              <tr>
                <th>Priority</th>
                <th>Type</th>
                <th>Message</th>
                <th>Area</th>
                <th>Hops</th>
                <th>Age</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {filteredRows.map((r) => {
                const ml = mlScores[r.messageId];
                const mlTop = ml
                  ? (ml.scores[String(ml.priority)] ??
                    Math.max(0, ...Object.values(ml.scores)))
                  : null;
                return (
                <tr key={r.messageId} data-pri={r.pri}>
                  <td data-lbl="Priority">
                    <PriBadge pri={r.pri} />
                    {ml ? (
                      <span className="ml-chip">
                        ML P{ml.priority} · {scorePct(mlTop ?? 0)}%
                      </span>
                    ) : r.mlEligible ? (
                      <span className="ml-slot">ML score: not connected yet</span>
                    ) : null}
                  </td>
                  <td data-lbl="Type">
                    <b>{typeLabel(r.type)}</b>
                    <br />
                    <span className="mono">msg {shortId(r.messageId)}</span>
                  </td>
                  <td data-lbl="Message">
                    {r.text}
                    {mlDupes.has(r.messageId) && (
                      <>
                        <br />
                        <span className="badge dup">Possible duplicate</span>
                      </>
                    )}
                    {r.type === "SAFETY_CHECKIN" && r.checkinCount > 1 && (
                      <>
                        <br />
                        <span className="ml-slot">
                          {r.checkinCount} check-ins from this device — newest shown
                        </span>
                      </>
                    )}
                  </td>
                  <td data-lbl="Area">{r.area}</td>
                  <td data-lbl="Hops">{r.hops}</td>
                  <td data-lbl="Age">{ageLabel(Date.now() - r.createdAt)}</td>
                  <td data-lbl="Status">
                    <span className={r.statusClass}>{r.statusLabel}</span>
                    {canReview &&
                      r.type === "SOS" &&
                      (r.statusLabel === "Open" || r.statusLabel === "Acknowledged") && (
                        <>
                          <br />
                          <button
                            className="btn"
                            type="button"
                            style={{ marginTop: 6, padding: "3px 10px", fontSize: 12 }}
                            disabled={!!resolveBusy[r.messageId]}
                            onClick={() => void doResolve(r)}
                          >
                            {resolveBusy[r.messageId] ? "Resolving…" : "Resolve"}
                          </button>
                        </>
                      )}
                    {resolveError[r.messageId] && (
                      <>
                        <br />
                        <span className="ml-slot">{resolveError[r.messageId]}</span>
                      </>
                    )}
                  </td>
                </tr>
                );
              })}
            </tbody>
          </table>
          {filteredRows.length === 0 && (
            <div className="empty">
              {loaded
                ? "No records match these filters."
                : loadFailed
                  ? "Server unreachable — no records loaded yet."
                  : "Loading records…"}
            </div>
          )}
        </section>

        {/* ================= MISSING CASES ================= */}
        <section>
          <div className="card-head" style={{ paddingLeft: 4, paddingRight: 4, borderBottom: 0 }}>
            <h2 style={{ fontSize: 18 }}>Missing-person cases</h2>
            <span className="sub">
              Missing ↔ Found are one case. Side-by-side compare — a coordinator reviews,
              the case never auto-closes.
            </span>
          </div>
          {cases.length === 0 ? (
            <div className="card empty" style={{ marginTop: 10 }}>
              {loaded ? "No missing-person cases on the server yet." : "Loading cases…"}
            </div>
          ) : (
            <div className="cases" style={{ marginTop: 10 }}>
              {cases.map((c) => {
                const caseKey = c.messageId ?? `case-${c.id}`;
                return (
                  <CaseCard
                    key={c.id}
                    c={c}
                    token={token}
                    canReview={canReview}
                    localReview={review[caseKey]}
                    localOnly={reviewLocalOnly[caseKey] ?? false}
                    busy={reviewBusy[caseKey] ?? false}
                    error={reviewError[caseKey]}
                    foundMsg={c.foundMessageId ? msgById.get(c.foundMessageId) : undefined}
                    foundReports={
                      c.messageId ? (foundReportsByCase.get(c.messageId) ?? []) : []
                    }
                    onReview={doReview}
                  />
                );
              })}
            </div>
          )}
        </section>

        {/* ================= ANNOUNCEMENTS + SYNC AUDIT ================= */}
        <section className="bottomrow">
          <div className="card">
            <div className="card-head">
              <h2>Announcements</h2>
              <span className="sub">Broadcast to the mesh — valid-until is honoured by every phone</span>
            </div>
            {canReview ? (
              <form className="ann-form" onSubmit={submitAnnouncement}>
                <input
                  type="text"
                  name="title"
                  placeholder="Title — e.g. Water tanker at Sector 12 gate"
                  required
                  maxLength={90}
                />
                <textarea name="body" placeholder="Message for citizens…" required maxLength={400} />
                <select name="priority" aria-label="Priority" defaultValue="3">
                  <option value="1">P1 · Critical</option>
                  <option value="2">P2 · High</option>
                  <option value="3">P3 · Normal</option>
                </select>
                <div className="row">
                  <select name="valid" aria-label="Valid for" defaultValue="24">
                    <option value="6">Valid 6 hours</option>
                    <option value="12">Valid 12 hours</option>
                    <option value="24">Valid 24 hours</option>
                    <option value="48">Valid 48 hours</option>
                  </select>
                  <button className="btn primary" type="submit" disabled={annBusy}>
                    {annBusy ? "Publishing…" : "Publish announcement"}
                  </button>
                </div>
                {annError && <div className="login-err">{annError}</div>}
                <p className="review-note" style={{ padding: 0 }}>
                  {annEndpointMissing
                    ? "Note: this server build has no announcement-publish endpoint yet — a composed announcement stays in this browser only and is labelled “Local only”."
                    : "Publishing sends the announcement to the server. If the server build predates the publish endpoint, it stays in this browser only and is labelled “Local only”."}
                </p>
              </form>
            ) : (
              <p className="review-note" style={{ paddingTop: 12 }}>
                Publishing announcements needs a COORDINATOR or ADMIN account — your
                account ({me.role}) is read-only here.
              </p>
            )}
            <div>
              {localAnn.map((a) => (
                <div className="ann-item" key={a.localId}>
                  <div className="h">
                    <b>{a.title}</b>
                    <span className="badge">Valid {a.validH} h</span>
                    <span className="badge local">Local only — not sent to server</span>
                  </div>
                  <p>{a.body}</p>
                  <div className="t">Composed {ageLabel(Date.now() - a.createdAt)} · by {me.username} (this browser only)</div>
                </div>
              ))}
              {announcements.map((a) => (
                <div className="ann-item" key={a.id}>
                  <div className="h">
                    <b>{a.title || "Announcement"}</b>
                    {a.expiresAt !== null && (
                      <span className="badge">
                        Valid until {clockDate(a.expiresAt)} · {clockTime(a.expiresAt)} IST
                      </span>
                    )}
                  </div>
                  <p>{a.body}</p>
                  <div className="t">
                    Published {ageLabel(Date.now() - a.createdAt)} · by {deviceLabel(a.createdBy)}
                  </div>
                </div>
              ))}
              {localAnn.length === 0 && announcements.length === 0 && (
                <div className="empty">
                  {loaded ? "No announcements yet." : "Loading announcements…"}
                </div>
              )}
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <h2>Sync audit</h2>
              <span className="sub">
                {auditRows !== null
                  ? "Sync audit — server record"
                  : "Latest records received by the server · signed envelopes"}
              </span>
            </div>
            <div className="audit-list">
              {auditRows !== null ? (
                <>
                  {auditRows.length === 0 && (
                    <div className="empty">
                      {loaded ? "No sync-audit rows on the server yet." : "Loading…"}
                    </div>
                  )}
                  {auditRows.slice(0, 6).map((a) => (
                    <div className="audit-item" key={a.id}>
                      <span className="t mono">{clockTime(a.createdAt, true)} IST</span>
                      <span>
                        {a.deviceId ? deviceLabel(a.deviceId) : "—"} · {a.action}
                        {a.messageId ? ` · msg ${shortId(a.messageId)}` : ""}
                        {a.hopCount !== null ? ` · ${a.hopCount} hops` : ""}
                        {a.detail ? ` · ${a.detail}` : ""}
                      </span>
                    </div>
                  ))}
                </>
              ) : (
                <>
                  {auditItems.length === 0 && (
                    <div className="empty">
                      {loaded ? "No synced records yet." : "Loading…"}
                    </div>
                  )}
                  {auditItems.map((m) => (
                    <div className="audit-item" key={m.messageId}>
                      <span className="t mono">{clockTime(m.createdAt, true)} IST</span>
                      <span>
                        {deviceLabel(m.originDeviceId)} · {typeLabel(m.eventType)} · msg{" "}
                        {shortId(m.messageId)} · {m.hopCount} hops · signature on record
                      </span>
                    </div>
                  ))}
                </>
              )}
            </div>
          </div>
        </section>

        <footer>
          <span>RescueMesh coordinator dashboard — live data from the RescueMesh server.</span>
          <span>
            API: <span className="mono">{API_BASE}</span> · Signed in as {me.username} ({me.role})
          </span>
        </footer>
      </div>
    </>
  );
}

/* ============================================================
   Map (react-leaflet, circle markers as in the template)
   ============================================================ */

function FitPins({ pins }: { pins: Pin[] }) {
  const map = useMap();
  const key = pins.map((p) => p.key).join(",");
  useEffect(() => {
    if (pins.length === 1) {
      map.setView([pins[0].lat, pins[0].lng], 13);
    } else if (pins.length > 1) {
      const bounds = L.latLngBounds(pins.map((p) => [p.lat, p.lng] as [number, number]));
      map.fitBounds(bounds, { padding: [30, 30] });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, map]);
  return null;
}

/* leaflet.heat layer (L.heatLayer, added by the plugin import).
 * Rebuilt whenever the point set changes; removed on cleanup. */
function HeatLayer({ points }: { points: [number, number, number][] }) {
  const map = useMap();
  useEffect(() => {
    if (points.length === 0) return;
    const heat = (
      L as unknown as {
        heatLayer: (
          pts: [number, number, number][],
          opts?: Record<string, unknown>,
        ) => L.Layer;
      }
    ).heatLayer(points, { radius: 28, blur: 18, minOpacity: 0.35 });
    heat.addTo(map);
    return () => {
      map.removeLayer(heat);
    };
  }, [map, points]);
  return null;
}

function IncidentMap({
  pins,
  mode,
  trails,
  heatPoints,
}: {
  pins: Pin[];
  mode: "pins" | "trails";
  trails: Trail[];
  heatPoints: [number, number, number][];
}) {
  const fitPoints: Pin[] =
    mode === "pins"
      ? pins
      : trails.flatMap((t) =>
          t.points.map((p, idx) => ({
            key: `${t.key}-fit-${idx}`,
            lat: p.lat,
            lng: p.lng,
            color: t.color,
            title: t.title,
            text: t.text,
          })),
        );
  return (
    <MapContainer id="map" center={CENTER} zoom={12} scrollWheelZoom>
      <TileLayer
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        maxZoom={18}
        attribution="&copy; OpenStreetMap contributors"
      />
      <FitPins pins={fitPoints} />
      {mode === "pins" &&
        pins.map((p) => (
          <CircleMarker
            key={p.key}
            center={[p.lat, p.lng]}
            radius={8}
            pathOptions={{ color: "#fff", weight: 2, fillColor: p.color, fillOpacity: 0.95 }}
          >
            <Popup>
              <b>{p.title}</b>
              <br />
              {p.text}
            </Popup>
          </CircleMarker>
        ))}
      {mode === "trails" && (
        <>
          <HeatLayer points={heatPoints} />
          {trails.map((t) => (
            <Fragment key={t.key}>
              <Polyline
                positions={t.points.map((p) => [p.lat, p.lng] as [number, number])}
                pathOptions={{ color: t.color, weight: 3, opacity: 0.85 }}
              >
                <Popup>
                  <b>{t.title}</b>
                  <br />
                  {t.text}
                </Popup>
              </Polyline>
              {t.points.map((p, idx) => (
                <CircleMarker
                  key={idx}
                  center={[p.lat, p.lng]}
                  radius={idx === 0 ? 6 : 4}
                  pathOptions={{
                    color: "#fff",
                    weight: 1.5,
                    fillColor: t.color,
                    fillOpacity: 0.95,
                  }}
                />
              ))}
            </Fragment>
          ))}
        </>
      )}
    </MapContainer>
  );
}

/* ============================================================
   Case card — side-by-side photos + coordinator review
   ============================================================ */

function PhotoSlot({
  messageId,
  token,
  caption,
  emptyText,
  onBlob,
}: {
  messageId: string | null;
  token: string;
  caption: string;
  emptyText: string;
  /* Reports the loaded photo Blob (used by the ML face-match assist);
   * null whenever no photo is loaded. Optional. */
  onBlob?: (blob: Blob | null) => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "ok" | "error">("idle");
  /* Full-photo view: the card shows a cropped 170px thumb; clicking it
   * opens the same loaded image full-size in an overlay. */
  const [viewing, setViewing] = useState(false);

  useEffect(() => {
    if (!viewing) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setViewing(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [viewing]);

  useEffect(() => {
    onBlob?.(null);
    if (!messageId) {
      setState("idle");
      setUrl(null);
      return;
    }
    let alive = true;
    let objUrl: string | null = null;
    setState("loading");
    fetchPhotoBlob(messageId, token)
      .then((blob) => {
        if (!alive) return;
        objUrl = URL.createObjectURL(blob);
        setUrl(objUrl);
        setState("ok");
        onBlob?.(blob);
      })
      .catch(() => {
        if (alive) setState("error");
      });
    return () => {
      alive = false;
      if (objUrl) URL.revokeObjectURL(objUrl);
    };
    // onBlob is a parent state setter — intentionally not a dependency;
    // the load is keyed by messageId + token only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messageId, token]);

  return (
    <figure>
      {state === "ok" && url ? (
        <img
          src={url}
          alt={`${caption} photo`}
          loading="lazy"
          className="clickable"
          title="Click to view the full photo"
          onClick={() => setViewing(true)}
        />
      ) : (
        <div className="nophoto">
          {state === "loading"
            ? "Loading photo…"
            : state === "error"
              ? "Photo not stored on the server"
              : emptyText}
        </div>
      )}
      <figcaption>{caption}</figcaption>
      {viewing && url && (
        <div
          className="lightbox"
          role="dialog"
          aria-label={`${caption} — full photo`}
          onClick={() => setViewing(false)}
        >
          <img src={url} alt={`${caption} photo (full size)`} />
          <span className="lightbox-cap">{caption} — click anywhere or press Esc to close</span>
        </div>
      )}
    </figure>
  );
}

function faceVerdictText(v: FaceMatchResult["verdict"]): string {
  switch (v) {
    case "LIKELY_MATCH":
      return "Likely the same person — coordinator to confirm";
    case "UNCERTAIN":
      return "Uncertain — coordinator to compare carefully";
    case "UNLIKELY":
      return "Looks different — coordinator to confirm";
    case "NO_FACE_DETECTED":
      return "No face detected in one of the photos";
  }
}

function CaseCard({
  c,
  token,
  canReview,
  localReview,
  localOnly,
  busy,
  error,
  foundMsg,
  foundReports,
  onReview,
}: {
  c: MissingCaseDto;
  token: string;
  canReview: boolean;
  localReview: string | undefined;
  localOnly: boolean;
  busy: boolean;
  error: string | undefined;
  foundMsg: MessageDto | undefined;
  /* All found reports linked to this case, newest first (the server's
   * foundMessageId is the newest of these). */
  foundReports: MessageDto[];
  onReview: (c: MissingCaseDto, act: "confirm" | "reject") => void;
}) {
  /* Case status (c.status: OPEN | FOUND_REPORTED) and the coordinator
   * review are separate facts — a review never changes the case status
   * and a case never auto-closes. Server review fields win; the session
   * fallback applies only when the server has no review endpoint (404). */
  const serverReview = c.reviewStatus ?? null;
  const confirmed = serverReview === "CONFIRMED" || (!serverReview && localReview === "CONFIRMED FOUND");
  const rejected =
    serverReview === "REJECTED" || (!serverReview && localOnly && localReview === "OPEN");

  const statusBadge = confirmed ? (
    <span className="badge ok">REVIEWED · CONFIRMED FOUND</span>
  ) : rejected ? (
    <>
      <span className="badge p2">OPEN</span>{" "}
      <span className="badge">REVIEWED · REJECTED</span>
    </>
  ) : c.status === "OPEN" ? (
    <span className="badge p2">OPEN</span>
  ) : c.status === "FOUND_REPORTED" ? (
    <span className="badge p1">FOUND REPORTED — review</span>
  ) : (
    <span className="badge">{c.status}</span>
  );

  const foundNote = c.foundMessageId
    ? `Found report: ${foundMsg ? payloadText(foundMsg) : "linked report"} · ${
        c.foundAt ? `linked ${ageLabel(Date.now() - c.foundAt)}` : "link time unknown"
      }`
    : "No found report yet.";

  const canAct = canReview && c.status === "FOUND_REPORTED" && !confirmed && !rejected && !busy;

  /* ML face-match assist: runs ONLY on the coordinator's click, with
   * the two photo Blobs the slots above already loaded from the
   * server. Advisory only — it never touches the review flow, and a
   * 503 / network failure renders as "unavailable", never a score. */
  const [missingBlob, setMissingBlob] = useState<Blob | null>(null);
  const [foundBlob, setFoundBlob] = useState<Blob | null>(null);
  const [faceBusy, setFaceBusy] = useState(false);
  const [faceResult, setFaceResult] = useState<FaceMatchResult | null>(null);
  const [faceUnavailable, setFaceUnavailable] = useState(false);

  const doFaceCheck = async () => {
    if (!missingBlob || !foundBlob || faceBusy) return;
    setFaceBusy(true);
    setFaceUnavailable(false);
    try {
      const r = await mlFaceMatch(missingBlob, foundBlob);
      setFaceResult(r);
    } catch {
      setFaceResult(null);
      setFaceUnavailable(true);
    } finally {
      setFaceBusy(false);
    }
  };

  return (
    <article className="card case">
      <div className="case-photos">
        <PhotoSlot
          messageId={c.hasPhoto ? c.messageId : null}
          token={token}
          caption="MISSING photo"
          emptyText={c.photoRef ? "Photo referenced but not yet uploaded to the server" : "No photo attached to this report"}
          onBlob={setMissingBlob}
        />
        <PhotoSlot
          messageId={c.foundMessageId}
          token={token}
          caption="FOUND report photo"
          emptyText="No found report yet"
          onBlob={setFoundBlob}
        />
      </div>
      {missingBlob && foundBlob && (
        <div className="ml-assist">
          <button
            className="btn"
            type="button"
            disabled={faceBusy}
            onClick={() => void doFaceCheck()}
          >
            {faceBusy ? "Checking…" : "Check face match (ML)"}
          </button>
          {faceResult && (
            <p className="ml-assist-result">
              {faceResult.similarity !== null && (
                <>Face similarity {scorePct(faceResult.similarity)}% · </>
              )}
              {faceVerdictText(faceResult.verdict)}
              <span className="ml-assist-model">ML model: {faceResult.model}</span>
            </p>
          )}
          {faceUnavailable && (
            <p className="ml-assist-result">
              Face match unavailable on the ML service.
            </p>
          )}
          <p className="review-note" style={{ padding: 0 }}>
            Advisory only — the case is never closed by a score; the
            coordinator decides.
          </p>
        </div>
      )}
      {foundReports.length > 1 && (
        <div className="found-strip">
          <div className="found-strip-head">
            Found reports — {foundReports.length} received · newest first (the
            pair above and the face match use the newest)
          </div>
          <div className="found-strip-grid">
            {foundReports.map((m) => (
              <PhotoSlot
                key={m.messageId}
                messageId={m.payload["photoRef"] ? m.messageId : null}
                token={token}
                caption={`Found · ${ageLabel(Date.now() - m.createdAt)}`}
                emptyText="No photo with this report"
              />
            ))}
          </div>
        </div>
      )}
      <div className="case-body">
        <h3>
          {c.name}
          {c.age !== null ? ` · ${c.age} yrs` : ""}
        </h3>
        <div className="case-meta">
          <span className="mono">{shortId(c.messageId)}</span>
          <span>{c.lastSeenArea ?? "Area unknown"}</span>
          <span>Reported {ageLabel(Date.now() - c.createdAt)}</span>
          {statusBadge}
        </div>
        <p className="case-desc">{c.description ?? "No description provided."}</p>
        <p className="case-desc">{foundNote}</p>
      </div>
      {canReview ? (
        <div className="case-actions">
          <button
            className="btn primary"
            type="button"
            disabled={!canAct}
            onClick={() => onReview(c, "confirm")}
          >
            {busy ? "Saving…" : "Confirm match — mark found"}
          </button>
          <button
            className="btn ghost-danger"
            type="button"
            disabled={!canAct}
            onClick={() => onReview(c, "reject")}
          >
            Not a match
          </button>
        </div>
      ) : (
        c.status === "FOUND_REPORTED" &&
        !confirmed &&
        !rejected && (
          <p className="review-note">
            Awaiting coordinator review — only COORDINATOR / ADMIN accounts can review a
            found report.
          </p>
        )
      )}
      {error && (
        <p className="review-note" style={{ color: "var(--red)" }}>
          Review could not be saved: {error}
        </p>
      )}
      {serverReview ? (
        <p className="review-note">
          Review {serverReview === "CONFIRMED" ? "CONFIRMED FOUND" : "REJECTED"} · by{" "}
          {c.reviewedBy ?? "coordinator"}
          {c.reviewedAt
            ? ` · ${ageLabel(Date.now() - c.reviewedAt)} (${clockTime(c.reviewedAt)} IST)`
            : ""}{" "}
          · saved to the server. Case status remains {c.status} — a review never closes
          a case on its own.
        </p>
      ) : localOnly ? (
        <p className="review-note">
          Coordinator decision only — a found report never closes a case on its own.
          Review decisions are currently kept in this browser (the server has no review
          endpoint yet).
        </p>
      ) : (
        <p className="review-note">
          Coordinator decision only — a found report never closes a case on its own.
        </p>
      )}
    </article>
  );
}
