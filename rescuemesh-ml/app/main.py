"""RescueMesh ML service (Prompt 8) — advisory priority + duplicate APIs.

Endpoints:
  GET  /health      -> {"status": "UP", "service": "rescuemesh-ml", ...}
  POST /priority    {"text": "..."} -> priority 1|2|3 with class scores
  POST /duplicate   {"text": "...", "candidates": [{"id", "text"}]}
                    -> {"matches": [{"id", "score"}], ...}
  POST /face-match  multipart image_a (missing) + image_b (found)
                    -> {"similarity", "verdict", "facesDetected", "model"}
                    or 503 {"error": "face model unavailable"}

No auth: in the demo this service sits behind the server/dashboard and
is never exposed directly (see README). ML output is advisory only —
the face-match score in particular NEVER closes a case.
"""

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app import face, model

app = FastAPI(title="RescueMesh ML", version=model.MODEL_VERSION)

# CORS: this is a local advisory demo service that the browser
# dashboard calls directly during the demo, so permissive origins are
# deliberate here. Tighten allow_origins before any real deployment.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class PriorityRequest(BaseModel):
    text: str = Field(default="")


class Candidate(BaseModel):
    id: str
    text: str


class DuplicateRequest(BaseModel):
    text: str = Field(default="")
    candidates: list[Candidate] = Field(default_factory=list)


def _require_text(text: str) -> str:
    cleaned = (text or "").strip()
    if not cleaned:
        raise HTTPException(status_code=400, detail="text must not be empty")
    return cleaned


@app.get("/health")
def health() -> dict:
    return {
        "status": "UP",
        "service": "rescuemesh-ml",
        "model_version": model.MODEL_VERSION,
    }


@app.post("/priority")
def priority(req: PriorityRequest) -> dict:
    text = _require_text(req.text)
    return model.predict_priority(text)


@app.post("/duplicate")
def duplicate(req: DuplicateRequest) -> dict:
    text = _require_text(req.text)
    candidates = [c.model_dump() for c in req.candidates]
    matches = model.find_duplicates(text, candidates)
    return {
        "matches": matches,
        "is_duplicate": len(matches) > 0,
        "threshold": model.DUPLICATE_THRESHOLD,
        "model_version": model.MODEL_VERSION,
    }


@app.post("/face-match")
def face_match(image_a: UploadFile = File(...), image_b: UploadFile = File(...)):
    """Advisory face similarity: missing photo vs found photo.

    Returns the fixed contract shape from app.face.compare_faces, or
    503 {"error": "face model unavailable"} when the face model did
    not load in this build — the dashboard renders an honest
    unavailable state from that. A similarity is never invented.
    """
    data_a = image_a.file.read()
    data_b = image_b.file.read()
    try:
        return face.compare_faces(data_a, data_b)
    except face.FaceModelUnavailable:
        return JSONResponse(
            status_code=503, content={"error": "face model unavailable"}
        )
