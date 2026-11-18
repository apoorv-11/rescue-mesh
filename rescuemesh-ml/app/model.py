"""Model loading and inference for the RescueMesh ML service.

Loads the fitted TF-IDF + LogisticRegression pipeline saved by train.py
(app/model.joblib) once at import/startup. The same fitted TF-IDF space
is reused for duplicate detection (cosine similarity), per the Prompt 8
starter sheet.

ML is ADVISORY ONLY: this service never changes the message envelope,
any priority set by the phone's rules, or any shared contract.
"""

from pathlib import Path

import joblib
import numpy as np
from sklearn.metrics.pairwise import cosine_similarity

MODEL_PATH = Path(__file__).resolve().parent / "model.joblib"
MODEL_VERSION = "ml-v1"

# Duplicate-filter threshold (ML-internal number, not a shared contract).
DUPLICATE_THRESHOLD = 0.80

PRIORITY_TO_LABEL = {1: "Critical", 2: "High", 3: "Normal"}

_pipeline = None


def get_pipeline():
    """Load the fitted pipeline lazily, once."""
    global _pipeline
    if _pipeline is None:
        if not MODEL_PATH.exists():
            raise RuntimeError(
                f"Model file not found at {MODEL_PATH}. Run `python train.py` first."
            )
        _pipeline = joblib.load(MODEL_PATH)
    return _pipeline


def predict_priority(text: str) -> dict:
    """Return the priority prediction for one message text."""
    pipeline = get_pipeline()
    proba = pipeline.predict_proba([text])[0]
    classes = list(pipeline.classes_)  # priority ints, ascending
    scores = {str(int(c)): float(p) for c, p in zip(classes, proba)}
    priority = int(classes[int(np.argmax(proba))])
    return {
        "priority": priority,
        "label": PRIORITY_TO_LABEL[priority],
        "scores": scores,
        "confidence": scores[str(priority)],
        "model_version": MODEL_VERSION,
    }


def find_duplicates(text: str, candidates: list[dict]) -> list[dict]:
    """Cosine-similarity matches for `text` among {id, text} candidates.

    Uses the priority pipeline's fitted TF-IDF vectorizer (the same
    TF-IDF space). Returns matches at or above DUPLICATE_THRESHOLD,
    sorted by score descending.
    """
    if not candidates:
        return []
    vectorizer = get_pipeline().named_steps["tfidf"]
    query_vec = vectorizer.transform([text])
    cand_vecs = vectorizer.transform([c["text"] for c in candidates])
    sims = cosine_similarity(query_vec, cand_vecs)[0]
    matches = [
        {"id": c["id"], "score": float(s)}
        for c, s in zip(candidates, sims)
        if s >= DUPLICATE_THRESHOLD
    ]
    matches.sort(key=lambda m: m["score"], reverse=True)
    return matches
