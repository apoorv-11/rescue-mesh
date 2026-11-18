# RescueMesh ML service (Prompt 8)

Advisory ML for RescueMesh: a priority classifier for emergency message
texts, a duplicate filter for spotting reworded repeats of the same
report, and an advisory face-similarity score to help a coordinator
compare a missing person's photo with a found person's photo.
**ML is advisory only** — the phone sets priority by rules; this
service never changes the message envelope (11 fields), any server
contract, or any stored priority. The face score in particular NEVER
closes a case: a human coordinator reviews it and decides.

**All training/evaluation data is SIMULATED** (synthetic texts generated
from seed phrases — see `data/generate_dataset.py`). No real SOS message
was used. See `evaluation.md` for measured metrics and their limits.

## Run

```bash
cd rescuemesh-ml
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

.venv/bin/python data/generate_dataset.py   # regenerate the SIMULATED dataset (optional; CSV is committed)
.venv/bin/python train.py                   # train + save app/model.joblib
.venv/bin/python evaluate.py                # regenerate evaluation.md
.venv/bin/uvicorn app.main:app --port 8000  # serve
```

Tests: `.venv/bin/python -m pytest`

Requires Python 3.11+ (built and verified on 3.12.3).

## Endpoints

No auth in the demo: this service sits behind the Spring Boot server /
dashboard and is never exposed directly.

### GET /health

```bash
curl http://localhost:8000/health
# {"status":"UP","service":"rescuemesh-ml","model_version":"ml-v1"}
```

### POST /priority

```bash
curl -X POST http://localhost:8000/priority \
  -H 'Content-Type: application/json' \
  -d '{"text": "building collapsed, people trapped inside"}'
# {"priority": 1, "label": "Critical",
#  "scores": {"1": 0.97, "2": 0.02, "3": 0.01},
#  "confidence": 0.97, "model_version": "ml-v1"}
```

Priorities match the settled RescueMesh levels: **1 Critical, 2 High,
3 Normal**. `scores` are the model's class probabilities; `priority` is
the argmax. Empty/blank text → HTTP 400.

### POST /duplicate

```bash
curl -X POST http://localhost:8000/duplicate \
  -H 'Content-Type: application/json' \
  -d '{"text": "Missing 8-year-old boy last seen near the market",
       "candidates": [{"id": "m1",
                       "text": "Missing 8-year-old boy last seen near the market, please help find him"}]}'
# {"matches": [{"id": "m1", "score": 0.93}], "is_duplicate": true,
#  "threshold": 0.8, "model_version": "ml-v1"}
```

Cosine similarity in the classifier's fitted TF-IDF space; a candidate
is a match at similarity ≥ 0.80 (`DUPLICATE_THRESHOLD` in
`app/model.py` — an ML-internal number, not a shared contract). Matches
are sorted by score, highest first. Empty/blank text → HTTP 400.

### POST /face-match

Multipart form with two image files: `image_a` = the missing person's
photo, `image_b` = the found person's photo (JPEG/PNG).

```bash
curl -X POST http://localhost:8000/face-match \
  -F image_a=@missing.jpg \
  -F image_b=@found.jpg
# {"similarity": 0.87, "verdict": "LIKELY_MATCH",
#  "facesDetected": {"a": true, "b": true},
#  "model": "insightface buffalo_s"}
```

The score is the cosine similarity of the two face embeddings
(insightface **buffalo_s**: SCRFD detection + ArcFace recognition,
CPU via onnxruntime). If several faces are in a photo, the largest is
used. Verdict thresholds (`app/face.py`, ML-internal numbers):
**≥ 0.60 `LIKELY_MATCH`**, **0.40–0.60 `UNCERTAIN`**,
**< 0.40 `UNLIKELY`**. If no face is detected in either photo:
`similarity: null`, verdict `NO_FACE_DETECTED`, and `facesDetected`
says truthfully which side failed.

The model pack (~120 MB) downloads from the insightface GitHub model
zoo into `~/.insightface/models/` on first use. If the pack cannot be
loaded in a given build (package missing, no network for the download,
load error), the service still starts and serves the text endpoints,
and this endpoint answers **503 `{"error": "face model unavailable"}`**
so the dashboard can show an honest unavailable state. A similarity
score is never invented or placeholder-filled.

**Advisory only, no exceptions:** this score helps a human coordinator
compare two photos side by side. It never closes a case, never marks a
person found, and no accuracy claim is made for it — this project has
no labelled face dataset (see `evaluation.md`).

## Layout

```
app/main.py              FastAPI app: /health, /priority, /duplicate, /face-match (+ CORS)
app/model.py             pipeline loading, priority + duplicate inference
app/face.py              insightface buffalo_s loading + face similarity
app/model.joblib         fitted TF-IDF + LogisticRegression pipeline
data/generate_dataset.py SIMULATED dataset generator (seed phrases x slots)
data/sos_texts.csv       600 synthetic texts, 200/class, all SIMULATED
train.py                 dedupe -> stratified 80/20 split -> train -> save
evaluate.py              held-out metrics + duplicate P/R -> evaluation.md
tests/                   pytest API tests (TestClient), incl. face-match
tests/fixtures/          public-domain portrait fixture + SOURCE.md
evaluation.md            measured results (generated by evaluate.py)
```
