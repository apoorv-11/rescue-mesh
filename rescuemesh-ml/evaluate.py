"""Evaluate the RescueMesh ML models and write evaluation.md.

- Priority classifier: metrics on the SAME held-out stratified test split
  that train.py used (shared loader, fixed seed), using the saved
  app/model.joblib pipeline.
- Duplicate filter: precision/recall at DUPLICATE_THRESHOLD on a
  constructed SIMULATED pair set — paraphrase pairs (duplicates) vs
  unrelated / different-person pairs (non-duplicates).

All numbers in evaluation.md come from this script. Nothing is
hand-written. All data is SIMULATED/synthetic.

Run:  python train.py && python evaluate.py
"""

import platform
from pathlib import Path

import joblib
import sklearn
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    precision_score,
    recall_score,
)
from sklearn.metrics.pairwise import cosine_similarity

from app import model as ml_model
from train import MODEL_PATH, load_and_split

ROOT = Path(__file__).resolve().parent
EVAL_MD = ROOT / "evaluation.md"

PRIORITIES = [1, 2, 3]
NAMES = {1: "Critical", 2: "High", 3: "Normal"}

# ---------------------------------------------------------------------------
# SIMULATED duplicate-filter test pairs.
# DUPLICATE_PAIRS: the same report reworded (should match).
# NONDUPLICATE_PAIRS: different people / places / situations (should not
# match), including hard negatives that share template wording.
# ---------------------------------------------------------------------------

DUPLICATE_PAIRS = [
    ("Missing 8-year-old boy last seen near the market",
     "Missing 8-year-old boy last seen near the market, please help find him"),
    ("Missing woman last seen at the bus stand yesterday",
     "Missing woman last seen at bus stand yesterday, family searching"),
    ("Missing teenage girl, phone switched off since evening",
     "Missing teenage girl phone switched off since evening, please inform if seen"),
    ("Elderly woman alone, needs drinking water near the school",
     "Elderly woman alone needs drinking water near school"),
    ("Missing 6-year-old girl last seen near the river bridge",
     "Missing 6 year old girl last seen near river bridge"),
    ("Found a lost child near the temple, parents not found yet",
     "Found a lost child near temple, parents not found yet, child is safe"),
    ("Missing elderly man last seen in sector 4 since morning",
     "Elderly man missing, last seen in sector 4 since morning"),
    ("Stranded travellers need food and water at the relief camp",
     "Stranded travellers need food and water at relief camp, please arrange"),
    ("Missing boy named Aarav, 10 years old, last seen near railway station",
     "10 years old boy Aarav missing, last seen near the railway station"),
    ("Blankets needed for families in open ground near the school",
     "Blankets needed for families staying in open ground near school"),
]

NONDUPLICATE_PAIRS = [
    ("Missing 8-year-old boy last seen near the market",
     "Missing 6-year-old girl last seen near the school"),
    ("Missing woman last seen at the bus stand yesterday",
     "Missing elderly man last seen at the hospital road since morning"),
    ("Elderly woman alone, needs drinking water near the school",
     "Drinking water tanker needed in the colony near the temple"),
    ("Found a lost child near the temple, parents not found yet",
     "Children alone at shelter, parents missing since yesterday"),
    ("Missing teenage girl, phone switched off since evening",
     "I am safe, phone battery low, will update later"),
    ("Stranded travellers need food and water at the relief camp",
     "Reached relief camp safely with my family"),
    ("Blankets needed for families in open ground near the school",
     "Tent and tarpaulin needed for displaced families at the village entrance"),
    ("Missing boy named Aarav, 10 years old, last seen near railway station",
     "Missing girl named Meera, 7 years old, last seen near the market"),
    ("Person trapped under collapsed wall, cannot move",
     "Building collapsed, people buried inside near the market"),
    ("Medicine needed for diabetic patient near the school",
     "Oxygen patient, cylinder empty, needs help urgently near the hospital road"),
]


def evaluate_duplicates(vectorizer) -> dict:
    def sim(a: str, b: str) -> float:
        vecs = vectorizer.transform([a, b])
        return float(cosine_similarity(vecs[0], vecs[1])[0, 0])

    tp = sum(1 for a, b in DUPLICATE_PAIRS
             if sim(a, b) >= ml_model.DUPLICATE_THRESHOLD)
    fn = len(DUPLICATE_PAIRS) - tp
    fp = sum(1 for a, b in NONDUPLICATE_PAIRS
             if sim(a, b) >= ml_model.DUPLICATE_THRESHOLD)
    tn = len(NONDUPLICATE_PAIRS) - fp
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    return {
        "tp": tp, "fn": fn, "fp": fp, "tn": tn,
        "precision": precision, "recall": recall,
        "n_dup": len(DUPLICATE_PAIRS), "n_nondup": len(NONDUPLICATE_PAIRS),
    }


def main() -> None:
    pipeline = joblib.load(MODEL_PATH)
    _, X_test, _, y_test = load_and_split()
    y_pred = pipeline.predict(X_test)

    report = classification_report(
        y_test, y_pred, labels=PRIORITIES,
        target_names=[NAMES[p] for p in PRIORITIES],
        output_dict=True, zero_division=0,
    )
    macro_f1 = report["macro avg"]["f1-score"]
    accuracy = accuracy_score(y_test, y_pred)
    cm = confusion_matrix(y_test, y_pred, labels=PRIORITIES)
    dup = evaluate_duplicates(pipeline.named_steps["tfidf"])
    target_met = macro_f1 > 0.80

    lines = []
    lines.append("# RescueMesh ML evaluation (SIMULATED data)")
    lines.append("")
    lines.append("**All data in this evaluation is SIMULATED / synthetic.** "
                 "No real SOS message was used anywhere in training or "
                 "evaluation. The dataset was generated from seed phrases "
                 "with slot variations (see `data/generate_dataset.py`).")
    lines.append("")
    lines.append(f"Dataset: 600 synthetic emergency texts, 200 per class, "
                 f"deduplicated on normalised text, stratified 80/20 split "
                 f"(480 train / {len(X_test)} test, fixed seed 42).")
    lines.append(f"Model: TF-IDF (1-2 grams, max 5000 features, sublinear_tf) "
                 f"+ LogisticRegression (class_weight=balanced, C=1.0), "
                 f"scikit-learn {sklearn.__version__}, "
                 f"Python {platform.python_version()}.")
    lines.append("ML is advisory only — it never changes the envelope or "
                 "any priority set by the phone's rules.")
    lines.append("")
    lines.append("## Priority classifier (held-out test split)")
    lines.append("")
    lines.append("| class | precision | recall | F1 | support |")
    lines.append("|---|---|---|---|---|")
    for p in PRIORITIES:
        r = report[NAMES[p]]
        lines.append(f"| {NAMES[p]} ({p}) | {r['precision']:.3f} | "
                     f"{r['recall']:.3f} | {r['f1-score']:.3f} | "
                     f"{int(r['support'])} |")
    lines.append("")
    lines.append(f"**Macro F1: {macro_f1:.3f}** (target > 0.80 — "
                 f"{'MET' if target_met else 'NOT MET'}) | "
                 f"**Accuracy: {accuracy:.3f}**")
    lines.append("")
    lines.append("Confusion matrix (rows = true, columns = predicted):")
    lines.append("")
    lines.append("| true \\ pred | Critical (1) | High (2) | Normal (3) |")
    lines.append("|---|---|---|---|")
    for i, p in enumerate(PRIORITIES):
        lines.append(f"| {NAMES[p]} ({p}) | {cm[i][0]} | {cm[i][1]} | {cm[i][2]} |")
    lines.append("")
    lines.append("## Duplicate filter (TF-IDF cosine, threshold "
                 f"{ml_model.DUPLICATE_THRESHOLD:.2f})")
    lines.append("")
    lines.append(f"Constructed SIMULATED pair set: {dup['n_dup']} paraphrase "
                 f"pairs (duplicates) and {dup['n_nondup']} unrelated / "
                 f"different-person pairs (non-duplicates).")
    lines.append("")
    lines.append(f"- Precision: **{dup['precision']:.3f}** "
                 f"(TP={dup['tp']}, FP={dup['fp']})")
    lines.append(f"- Recall: **{dup['recall']:.3f}** "
                 f"(TP={dup['tp']}, FN={dup['fn']})")
    lines.append(f"- True negatives: {dup['tn']} / {dup['n_nondup']}")
    lines.append("")
    lines.append("## Tuning log")
    lines.append("")
    lines.append(f"Run 1 (starting-point config, C=1.0, max_features=5000): "
                 f"macro F1 {macro_f1:.3f}. "
                 + ("Target met; no further tuning performed."
                    if target_met else
                    "Target NOT met with the starting-point config; "
                    "see the numbers above, reported as measured."))
    lines.append("")
    lines.append("## Limits")
    lines.append("")
    lines.append("- Synthetic data only: the texts come from templates and "
                 "slot variations, so train and test share vocabulary and "
                 "phrasing patterns. Real SOS wording will differ and scores "
                 "on real data will be lower.")
    lines.append("- The duplicate pair set is small and hand-constructed "
                 "(also SIMULATED); treat its precision/recall as a smoke "
                 "check of the threshold, not a field measurement.")
    lines.append("- Retrain and re-evaluate on real, labelled, consented "
                 "data before any production claim.")
    lines.append("")

    EVAL_MD.write_text("\n".join(lines), encoding="utf-8")
    print(f"Macro F1: {macro_f1:.4f}  Accuracy: {accuracy:.4f}")
    print(f"Duplicate filter: precision {dup['precision']:.3f}, "
          f"recall {dup['recall']:.3f} "
          f"(TP={dup['tp']} FN={dup['fn']} FP={dup['fp']} TN={dup['tn']})")
    print(f"Wrote {EVAL_MD}")


if __name__ == "__main__":
    main()
