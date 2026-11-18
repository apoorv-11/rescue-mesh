"""Advisory face similarity for coordinator case review (Prompt 8 ext).

Uses insightface (buffalo_s pack: SCRFD detection + ArcFace recognition)
on CPU via onnxruntime. Loaded lazily on first use, exactly like the
text pipeline in model.py: if the pack is unavailable (package not
installed, model download failed, load error), the service still starts
and serves the text endpoints; compare_faces() raises
FaceModelUnavailable and the API layer answers the documented 503
contract. This module NEVER invents a similarity score: no score is
better than a fake one in an emergency product.

ADVISORY ONLY: the similarity helps a human coordinator compare a
missing person's photo with a found person's photo. It never closes a
case, never marks anything found, and never changes any record.
"""

import threading

import numpy as np

FACE_MODEL_NAME = "insightface buffalo_s"
_FACE_MODEL_PACK = "buffalo_s"

# Verdict thresholds on embedding cosine similarity (ML-internal
# numbers, not a shared contract).
LIKELY_MATCH_THRESHOLD = 0.60
UNCERTAIN_THRESHOLD = 0.40

_lock = threading.Lock()
_face_app = None
_load_error: str | None = None
_load_attempted = False


class FaceModelUnavailable(Exception):
    """Raised when the face model could not be loaded in this build."""


def _try_load():
    """Attempt the (one-time) model load; record the outcome."""
    global _face_app, _load_error, _load_attempted
    try:
        from insightface.app import FaceAnalysis

        fa = FaceAnalysis(name=_FACE_MODEL_PACK, providers=["CPUExecutionProvider"])
        fa.prepare(ctx_id=-1, det_size=(640, 640))
        _face_app = fa
        _load_error = None
    except Exception as exc:  # ImportError, download failure, bad pack...
        _face_app = None
        _load_error = f"{type(exc).__name__}: {exc}"
    _load_attempted = True


def get_face_app():
    """Return the loaded FaceAnalysis, loading lazily once.

    Raises FaceModelUnavailable if the load failed (the failure is
    cached: a missing model must not trigger a download retry on
    every request).
    """
    global _load_attempted
    if not _load_attempted:
        with _lock:
            if not _load_attempted:
                _try_load()
    if _face_app is None:
        raise FaceModelUnavailable(_load_error or "face model failed to load")
    return _face_app


def face_model_available() -> bool:
    """True if the face model loads in this environment."""
    try:
        get_face_app()
        return True
    except FaceModelUnavailable:
        return False


def face_load_error() -> str | None:
    """The recorded load error, if the model failed to load."""
    if not _load_attempted:
        face_model_available()
    return _load_error


def _largest_face_embedding(face_app, image_bytes: bytes):
    """Embedding of the largest detected face, or None.

    Returns None both when no face is detected and when the bytes are
    not a decodable image at all — from the caller's side both mean
    "no usable face in this photo".
    """
    import cv2

    arr = np.frombuffer(image_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        return None
    faces = face_app.get(img)
    if not faces:
        return None

    def _area(face) -> float:
        x1, y1, x2, y2 = face.bbox
        return float((x2 - x1) * (y2 - y1))

    face = max(faces, key=_area)
    emb = getattr(face, "normed_embedding", None)
    if emb is None:
        raw = np.asarray(face.embedding, dtype=np.float64)
        emb = raw / np.linalg.norm(raw)
    return np.asarray(emb, dtype=np.float64)


def compare_faces(image_a: bytes, image_b: bytes) -> dict:
    """Advisory similarity between the faces in two photos.

    image_a is the missing person's photo, image_b the found person's
    (the comparison is symmetric; the labels only mark which side a
    detection failure was on). Returns the fixed contract shape:
    {"similarity", "verdict", "facesDetected", "model"}.
    """
    face_app = get_face_app()  # raises FaceModelUnavailable
    emb_a = _largest_face_embedding(face_app, image_a)
    emb_b = _largest_face_embedding(face_app, image_b)
    detected = {"a": emb_a is not None, "b": emb_b is not None}
    if emb_a is None or emb_b is None:
        return {
            "similarity": None,
            "verdict": "NO_FACE_DETECTED",
            "facesDetected": detected,
            "model": FACE_MODEL_NAME,
        }
    sim = float(
        np.dot(emb_a, emb_b) / (np.linalg.norm(emb_a) * np.linalg.norm(emb_b))
    )
    sim = max(0.0, min(1.0, sim))  # contract range is 0..1
    if sim >= LIKELY_MATCH_THRESHOLD:
        verdict = "LIKELY_MATCH"
    elif sim >= UNCERTAIN_THRESHOLD:
        verdict = "UNCERTAIN"
    else:
        verdict = "UNLIKELY"
    return {
        "similarity": sim,
        "verdict": verdict,
        "facesDetected": detected,
        "model": FACE_MODEL_NAME,
    }
