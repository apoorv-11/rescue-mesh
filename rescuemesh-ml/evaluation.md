# RescueMesh ML evaluation (SIMULATED data)

**All data in this evaluation is SIMULATED / synthetic.** No real SOS message was used anywhere in training or evaluation. The dataset was generated from seed phrases with slot variations (see `data/generate_dataset.py`).

Dataset: 600 synthetic emergency texts, 200 per class, deduplicated on normalised text, stratified 80/20 split (480 train / 120 test, fixed seed 42).
Model: TF-IDF (1-2 grams, max 5000 features, sublinear_tf) + LogisticRegression (class_weight=balanced, C=1.0), scikit-learn 1.9.1, Python 3.12.3.
ML is advisory only — it never changes the envelope or any priority set by the phone's rules.

## Priority classifier (held-out test split)

| class | precision | recall | F1 | support |
|---|---|---|---|---|
| Critical (1) | 1.000 | 1.000 | 1.000 | 40 |
| High (2) | 1.000 | 1.000 | 1.000 | 40 |
| Normal (3) | 1.000 | 1.000 | 1.000 | 40 |

**Macro F1: 1.000** (target > 0.80 — MET) | **Accuracy: 1.000**

Confusion matrix (rows = true, columns = predicted):

| true \ pred | Critical (1) | High (2) | Normal (3) |
|---|---|---|---|
| Critical (1) | 40 | 0 | 0 |
| High (2) | 0 | 40 | 0 |
| Normal (3) | 0 | 0 | 40 |

## Duplicate filter (TF-IDF cosine, threshold 0.80)

Constructed SIMULATED pair set: 10 paraphrase pairs (duplicates) and 10 unrelated / different-person pairs (non-duplicates).

- Precision: **1.000** (TP=10, FP=0)
- Recall: **1.000** (TP=10, FN=0)
- True negatives: 10 / 10

## Tuning log

Run 1 (starting-point config, C=1.0, max_features=5000): macro F1 1.000. Target met; no further tuning performed.

## Limits

- Synthetic data only: the texts come from templates and slot variations, so train and test share vocabulary and phrasing patterns. Real SOS wording will differ and scores on real data will be lower.
- The duplicate pair set is small and hand-constructed (also SIMULATED); treat its precision/recall as a smoke check of the threshold, not a field measurement.
- Retrain and re-evaluate on real, labelled, consented data before any production claim.

## Face-match (advisory photo similarity) — status

<!-- Manually maintained appendix (18 Nov 2026); evaluate.py regenerates
     only the text sections above this one. -->

- **Status: WORKING in this build.** Model: insightface **buffalo_s**
  (SCRFD face detection + ArcFace recognition embeddings), run on CPU
  via onnxruntime 1.32.0 / insightface 2.1, Python 3.12.3. The pack
  downloads from the insightface GitHub model zoo on first use; if it
  cannot load, the endpoint returns the 503 "face model unavailable"
  contract instead of any score.
- Smoke results (functional checks, NOT accuracy measurements):
  the same public-domain portrait compared with itself (see
  `tests/fixtures/SOURCE.md`) → face detected on both sides,
  similarity > 0.99, verdict `LIKELY_MATCH`. A blank solid image →
  verdict `NO_FACE_DETECTED`, similarity `null`. Full pytest suite:
  11 passed, 1 skipped (the 503-path test skips when the model loads).
- **NO accuracy claims are made.** This project has no labelled face
  dataset, so there is no measured precision/recall for face matching
  and none is implied. Same-photo similarity only proves the pipeline
  runs end to end.
- **Advisory only:** the score is shown to a human coordinator next to
  the two photos to help their own comparison. It never closes a case,
  never marks a person found, and never overrides the coordinator's
  review — a case closes only by human decision.
