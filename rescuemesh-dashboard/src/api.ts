/* RescueMesh dashboard — API layer.
 * Wired to the real Spring Boot server (see phase3-dashboard-kickoff sheet).
 * Base URL: VITE_API_BASE, default http://localhost:8081 (the user's LAN/dev
 * server port — Jenkins occupies 8080 on his machine).
 * All timestamps from the server are epoch milliseconds.
 */

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/$/, "") ??
  "http://localhost:8081";

const TOKEN_KEY = "rescuemesh.dashboard.token";

export function loadToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function saveToken(token: string | null): void {
  try {
    if (token) window.localStorage.setItem(TOKEN_KEY, token);
    else window.localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage unavailable — session-only token */
  }
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/* ---------- DTOs — field names copied from the server source ---------- */

export interface MeInfo {
  username: string;
  role: string; // CITIZEN | VOLUNTEER | COORDINATOR | ADMIN
  deviceId: string | null;
}

export interface SosRecordDto {
  id: number;
  messageId: string;
  originDeviceId: string;
  status: string; // OPEN | ACKNOWLEDGED | RESOLVED
  latitude: number | null;
  longitude: number | null;
  note: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface PhotoRef {
  photoId?: string;
  sha256?: string;
  sizeBytes?: number;
  mime?: string;
  [key: string]: unknown;
}

export interface MissingCaseDto {
  id: number;
  messageId: string | null;
  name: string;
  age: number | null;
  description: string | null;
  lastSeenArea: string | null;
  lastSeenAt: number | null;
  status: string; // OPEN | FOUND_REPORTED — the case status; a review never changes it
  reportedByDeviceId: string | null;
  photoRef: PhotoRef | null;
  hasPhoto: boolean;
  foundMessageId: string | null;
  foundAt: number | null;
  createdAt: number;
  updatedAt: number;
  /* Coordinator review — present only on newer server builds; older builds
   * simply omit these fields (the dashboard falls back to session-only). */
  reviewStatus?: "CONFIRMED" | "REJECTED" | null;
  reviewedBy?: string | null;
  reviewedAt?: number | null;
}

export interface AuditDto {
  id: number;
  deviceId: string | null;
  messageId: string | null;
  action: string;
  hopCount: number | null;
  detail: string | null;
  createdAt: number; // epoch ms
}

export interface AnnouncementDto {
  id: number;
  title: string;
  body: string;
  priority: number;
  createdBy: string;
  expiresAt: number | null;
  createdAt: number;
}

export type Payload = Record<string, unknown>;

export interface MessageDto {
  messageId: string;
  eventType: string; // SOS | SAFETY_CHECKIN | MISSING_PERSON | PERSON_FOUND_UPDATE | ANNOUNCEMENT | RESOURCE_UPDATE
  originDeviceId: string;
  createdAt: number;
  expiresAt: number | null;
  priority: number; // 1 Critical | 2 High | 3 Normal (rule-based; ML service not built yet)
  hopLimit: number;
  hopCount: number;
  hopTrace: unknown;
  payload: Payload;
  signature: string;
}

/* ---------- HTTP helpers ---------- */

async function errorMessage(res: Response): Promise<string> {
  try {
    const body: unknown = await res.json();
    if (body && typeof body === "object") {
      const rec = body as Record<string, unknown>;
      if (typeof rec.error === "string") return rec.error;
      if (typeof rec.message === "string") return rec.message;
    }
  } catch {
    /* non-JSON error body */
  }
  return `Request failed (${res.status})`;
}

export async function apiGet<T>(path: string, token: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
  } catch {
    throw new ApiError(0, `Server unreachable at ${API_BASE}`);
  }
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return (await res.json()) as T;
}

export async function apiPost<T>(path: string, token: string, body: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, `Server unreachable at ${API_BASE}`);
  }
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return (await res.json()) as T;
}

export async function login(username: string, password: string): Promise<string> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw new ApiError(0, `Server unreachable at ${API_BASE} — is the RescueMesh server running?`);
  }
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  const body = (await res.json()) as { token: string; tokenType: string };
  return body.token;
}

export function fetchMe(token: string): Promise<MeInfo> {
  return apiGet<MeInfo>("/me", token);
}

export function fetchSos(token: string): Promise<SosRecordDto[]> {
  return apiGet<SosRecordDto[]>("/records/sos", token);
}

export function fetchMissing(token: string): Promise<MissingCaseDto[]> {
  return apiGet<MissingCaseDto[]>("/records/missing", token);
}

export function fetchAnnouncements(token: string): Promise<AnnouncementDto[]> {
  return apiGet<AnnouncementDto[]>("/records/announcements", token);
}

export function fetchMessages(token: string): Promise<MessageDto[]> {
  return apiGet<MessageDto[]>("/records/messages", token);
}

/* ---------- Newer endpoints (older server builds answer 404) ---------- */

/** Publish an announcement to the server. 201 → the created server item. */
export function publishAnnouncement(
  token: string,
  input: { title: string; body: string; priority: number; validUntil: number },
): Promise<AnnouncementDto> {
  return apiPost<AnnouncementDto>("/records/announcements", token, input);
}

/** Persist a coordinator review for a case. 200 → the updated case item. */
export function reviewMissing(
  token: string,
  messageId: string,
  decision: "CONFIRMED" | "REJECTED",
): Promise<MissingCaseDto> {
  return apiPost<MissingCaseDto>(
    `/records/missing/${encodeURIComponent(messageId)}/review`,
    token,
    { decision },
  );
}

/** Server sync-audit rows, newest first. */
export function fetchAudit(token: string): Promise<AuditDto[]> {
  return apiGet<AuditDto[]>("/records/audit", token);
}

/** Case photo (JPEG) — needs the Authorization header, so fetch a blob. */
export async function fetchPhotoBlob(
  messageId: string,
  token: string,
): Promise<Blob> {
  const res = await fetch(`${API_BASE}/records/photos/${encodeURIComponent(messageId)}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new ApiError(res.status, `Photo not available (${res.status})`);
  return res.blob();
}

/** Case photo as an object URL for <img> display. */
export async function fetchPhotoObjectUrl(
  messageId: string,
  token: string,
): Promise<string> {
  const blob = await fetchPhotoBlob(messageId, token);
  return URL.createObjectURL(blob);
}

/* ---------- ML service (separate Python service, advisory only) ----------
 * Base URL: VITE_ML_BASE, default http://localhost:8001. The ML service
 * is NOT part of the Spring server and needs no dashboard token. Every
 * call is best-effort: callers treat any failure (service down, non-200)
 * as "ML unavailable" and keep the honest non-ML rendering — an ML score
 * never changes record data, queue order, or a case decision. */

export const ML_BASE: string =
  (import.meta.env.VITE_ML_BASE as string | undefined)?.replace(/\/$/, "") ??
  "http://localhost:8001";

export interface MlPriorityResult {
  priority: number; // 1 | 2 | 3 — the model's predicted class
  scores: Record<string, number>; // {"1": p, "2": p, "3": p} probabilities
}

export interface MlDuplicateMatch {
  id: string;
  score: number;
}

export type FaceVerdict =
  | "LIKELY_MATCH"
  | "UNCERTAIN"
  | "UNLIKELY"
  | "NO_FACE_DETECTED";

export interface FaceMatchResult {
  similarity: number | null; // 0..1, null when no comparison was possible
  verdict: FaceVerdict;
  facesDetected: { a: boolean; b: boolean };
  model: string;
}

async function mlPost<T>(path: string, body: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${ML_BASE}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, `ML service unreachable at ${ML_BASE}`);
  }
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return (await res.json()) as T;
}

/** Predict the priority of a message text. Advisory display only. */
export function mlPriority(text: string): Promise<MlPriorityResult> {
  return mlPost<MlPriorityResult>("/priority", { text });
}

/** Flag near-duplicate texts among the given candidates. */
export async function mlDuplicate(
  text: string,
  candidates: { id: string; text: string }[],
): Promise<MlDuplicateMatch[]> {
  const body = await mlPost<{ matches?: MlDuplicateMatch[] }>("/duplicate", {
    text,
    candidates,
  });
  return body.matches ?? [];
}

/** Compare two case photos (missing vs found). 503 when the ML
 * service's face model is unavailable — the caller renders that
 * honestly and never invents a score. */
export async function mlFaceMatch(
  imageA: Blob,
  imageB: Blob,
): Promise<FaceMatchResult> {
  const form = new FormData();
  form.append("image_a", imageA, "missing.jpg");
  form.append("image_b", imageB, "found.jpg");
  let res: Response;
  try {
    res = await fetch(`${ML_BASE}/face-match`, { method: "POST", body: form });
  } catch {
    throw new ApiError(0, `ML service unreachable at ${ML_BASE}`);
  }
  if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
  return (await res.json()) as FaceMatchResult;
}

/* ---------- Live updates: fetch-based SSE reader ----------
 * Exactly the pattern from sse-option-a-starter-2026-11-18.md: the browser
 * EventSource CANNOT send an Authorization header (it would get a 401), so
 * the stream is read with fetch + ReadableStream. Resolves when the stream
 * ends; rejects on HTTP/network failure — the caller falls back to polling
 * and retries with backoff. */
export async function openStream(
  token: string,
  onChange: () => void,
  onOpen?: () => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(`${API_BASE}/records/stream`, {
    headers: { Authorization: `Bearer ${token}` },
    signal,
  });
  if (!res.ok || !res.body) {
    throw new ApiError(res.status, `Stream unavailable (${res.status})`);
  }
  onOpen?.();
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break; // reconnect with backoff from the caller
    buf += decoder.decode(value, { stream: true });
    const frames = buf.split("\n\n");
    buf = frames.pop() ?? "";
    for (const frame of frames) {
      for (const line of frame.split("\n")) {
        if (!line.startsWith("data:")) continue;
        try {
          const msg = JSON.parse(line.slice(5)) as { type?: string };
          if (msg.type === "records-changed" || msg.type === "photo-changed") onChange();
        } catch {
          /* heartbeat / non-JSON frame — ignore */
        }
      }
    }
  }
}
