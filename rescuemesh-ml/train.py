"""Train the RescueMesh ML priority classifier.

Pipeline (Prompt 8 starter sheet starting point):
  TfidfVectorizer(ngram_range=(1, 2), max_features=5000, sublinear_tf=True)
  + LogisticRegression(max_iter=1000, class_weight="balanced", C=1.0)

Data: data/sos_texts.csv — 600 SIMULATED synthetic texts, 200 per class.
Deduplicated on normalised text BEFORE a stratified 80/20 split
(fixed seed), so no text appears in both train and test.

Run:  python train.py     (writes app/model.joblib)
"""

import csv
import re
from pathlib import Path

import joblib
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline

ROOT = Path(__file__).resolve().parent
DATA_CSV = ROOT / "data" / "sos_texts.csv"
MODEL_PATH = ROOT / "app" / "model.joblib"

RANDOM_SEED = 42
TEST_SIZE = 0.2

# Settled RescueMesh priority mapping: 1 Critical, 2 High, 3 Normal.
LABEL_TO_PRIORITY = {"Critical": 1, "High": 2, "Normal": 3}


def normalise(text: str) -> str:
    text = text.lower()
    text = re.sub(r"[^a-z0-9 ]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def load_and_split():
    """Load the dataset, dedupe, and return the stratified 80/20 split.

    Shared by train.py and evaluate.py so both always see the SAME split.
    Returns (X_train, X_test, y_train, y_test) with y as priority ints.
    """
    with DATA_CSV.open(newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    assert {r["source"] for r in rows} == {"SIMULATED"}, \
        "dataset must be SIMULATED only"
    seen: set[str] = set()
    texts: list[str] = []
    priorities: list[int] = []
    for row in rows:  # dedupe on normalised text, first occurrence wins
        key = normalise(row["text"])
        if key in seen:
            continue
        seen.add(key)
        texts.append(row["text"])
        priorities.append(LABEL_TO_PRIORITY[row["label"]])
    X_train, X_test, y_train, y_test = train_test_split(
        texts, priorities,
        test_size=TEST_SIZE, random_state=RANDOM_SEED,
        stratify=priorities,
    )
    return X_train, X_test, y_train, y_test


def build_pipeline() -> Pipeline:
    return Pipeline([
        ("tfidf", TfidfVectorizer(
            ngram_range=(1, 2), max_features=5000, sublinear_tf=True)),
        ("clf", LogisticRegression(
            max_iter=1000, class_weight="balanced", C=1.0)),
    ])


def main() -> None:
    X_train, X_test, y_train, y_test = load_and_split()
    print(f"Train: {len(X_train)}  Test: {len(X_test)} "
          f"(stratified, seed={RANDOM_SEED})")
    pipeline = build_pipeline()
    pipeline.fit(X_train, y_train)
    train_acc = pipeline.score(X_train, y_train)
    test_acc = pipeline.score(X_test, y_test)
    print(f"Train accuracy: {train_acc:.4f}  Held-out accuracy: {test_acc:.4f}")
    joblib.dump(pipeline, MODEL_PATH)
    print(f"Saved fitted pipeline to {MODEL_PATH}")


if __name__ == "__main__":
    main()
