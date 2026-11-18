# RescueMesh — ML Service

Advisory machine-learning service for RescueMesh. It offers three
assists to the coordinator dashboard:

1. **Priority classification** of emergency message texts
   (1 Critical / 2 High / 3 Normal).
2. **Duplicate detection** — flags a new report that looks like a
   reworded repeat of an existing one.
3. **Face-match scoring** — a similarity score between a missing
   person's photo and a found person's photo, to help a coordinator
   compare the pair.

**Advisory only, by design.** This service never changes a stored
priority, never edits the message envelope, and a face score **never
closes a case** — a human coordinator reviews the evidence and
decides. If the service is down, the dashboard simply shows
"not connected" labels.

> **Data honesty:** all training and evaluation data is **SIMULATED**
> (synthetic texts generated from templates). The perfect scores in
> `evaluation.md` describe that synthetic setup — train and test share
> template vocabulary. Retraining on real field data is required
> before operational use, and scores will be lower.

## Tech stack

| Area | Technology |
|---|---|
| API | FastAPI 0.163.1, Uvicorn 0.54.0, Pydantic 2.14.3 |
| Text models | scikit-learn 1.9.1 — TF-IDF (1–2 grams, 5 000 features, sublinear) + logistic regression (class-balanced) |
| Face model | InsightFace 2.1 (`buffalo_s`: SCRFD detection + ArcFace recognition), ONNX Runtime 1.32.0, CPU |
| Runtime | Python 3.11+ |

## Run

```bash
python -m venv .venv
.venv\Scripts\activate        # Windows (Linux/macOS: source .venv/bin/activate)
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8001
```

- API base: `http://localhost:8001`
- Health: `GET /health`
- The trained text model (`app/model.joblib`) is committed — no
  training step is needed to run.
- **First face-match call downloads the `buffalo_s` model pack
  (~120 MB) into `~/.insightface`.** Run one face-match before any
  demo so the download does not happen mid-presentation. If the face
  model is unavailable, `/face-match` returns `503` and the text
  endpoints keep working.

## API

### `GET /health`
Service status.

### `POST /priority`
```json
{ "text": "Bridge collapsed, many people trapped under debris" }
```
→ `{ "priority": 1, "scores": { "1": 0.93, "2": 0.05, "3": 0.02 } }`

### `POST /duplicate`
```json
{ "text": "Need drinking water near sector 8 gate",
  "candidates": [ { "id": "msg-123", "text": "Water needed at sector 8" } ] }
```
→ matches whose TF-IDF cosine similarity is **≥ 0.80**, with scores.

### `POST /face-match`
Multipart upload of two images (`image_a`, `image_b`) →
`{ "similarity": 0.0–1.0, "verdict": "LIKELY_MATCH" | "UNCERTAIN" |
"UNLIKELY_MATCH" | "NO_FACE_DETECTED", "model": "buffalo_s" }`.
Verdict thresholds: cosine ≥ 0.60 likely match, ≥ 0.40 uncertain.
No face detected on either side is reported per side. `503` when the
face model is not loaded. **No accuracy is claimed for this endpoint**
— there is no labelled face dataset in this project; the score is a
triage aid only.

## Training and evaluation

```bash
python data/generate_dataset.py   # 600 SIMULATED texts, 200/class, seed 42
python train.py                   # stratified 80/20 split, writes app/model.joblib
python evaluate.py                # refreshes evaluation.md
```

`evaluation.md` reports per-class precision / recall / F1, the
confusion matrix, macro F1 (target > 0.80 — met on the synthetic
data), and duplicate-filter precision / recall, together with the
synthetic-data caveat above.

## Tests

```bash
pytest        # API + face-match tests (the 503-path test skips when
              # the face model is loaded)
```

## Project structure

```
app/
├── main.py       FastAPI app — /health, /priority, /duplicate, /face-match
├── model.py      TF-IDF + logistic-regression pipeline (load/predict)
├── face.py       InsightFace wrapper (detect, embed, compare)
└── model.joblib  Trained text model (committed deliverable)
data/             Dataset generator + sos_texts.csv (SIMULATED)
tests/            pytest suite + face fixture
train.py · evaluate.py · evaluation.md
```
