"""Synthetic dataset generator for the RescueMesh ML service.

ALL data produced here is SIMULATED. No real SOS message has ever been
used. Texts are built from the seed phrases in the Prompt 8 starter sheet
(``ml-service-starter-2026-11-16.md``), expanded with additional seed
templates and slot variations (place / count / time / prefix / suffix),
then deduplicated on normalised text and sampled to exactly 200 per
class with a fixed seed so the dataset is reproducible.

Classes follow the settled RescueMesh priority mapping:
  1 = Critical, 2 = High, 3 = Normal.

Output: data/sos_texts.csv with columns text,label,source
(label is the class name; source is "SIMULATED" on every row).
"""

import csv
import itertools
import random
import re
from pathlib import Path

RANDOM_SEED = 42
PER_CLASS = 200

PLACES = [
    "near the market", "at the bus stand", "near the river bridge",
    "in sector 4", "near the school", "at the relief camp",
    "on the highway", "near the temple", "in the old city area",
    "near the railway station", "at the village entrance",
    "near the hospital road", "",
]
COUNTS = ["two", "three", "four", "five", "six", "ten", "several", "many", "a few"]
TIMES = [
    "since morning", "since last night", "since yesterday",
    "for two hours", "for three hours", "since the earthquake",
    "since the flood started", "",
]

# ---------------------------------------------------------------------------
# Seed templates. The first 12 per class are the starter sheet's seed
# phrases (lightly templated with slots); the rest are expansions written
# for this generator. {place} / {count} / {time} are slot placeholders.
# ---------------------------------------------------------------------------

CRITICAL_TEMPLATES = [
    "person trapped under collapsed wall, cannot move {place}",
    "someone bleeding heavily, needs ambulance now {place}",
    "child unconscious after fall, not breathing properly {place}",
    "elderly man chest pain, no medicine nearby {place}",
    "family stuck on rooftop, flood water rising fast {place}",
    "building collapsed, people buried inside {place}",
    "woman in labour, no hospital reachable {place}",
    "man electrocuted, unconscious on the road {place}",
    "fire spreading in house, {count} people inside {place}",
    "someone drowning in the river near the bridge",
    "accident on highway, driver trapped in car {place}",
    "oxygen patient, cylinder empty, needs help urgently {place}",
    "people injured in building collapse, trapped under debris {place}",
    "severe head injury, person not responding {place}",
    "house on fire, elderly woman trapped inside {place}",
    "child swept away by flood water, rescue needed now {place}",
    "man crushed under fallen tree, bleeding badly {place}",
    "pregnant woman bleeding, needs hospital immediately {place}",
    "{count} people injured in bus accident, critical condition {place}",
    "person trapped in lift after earthquake, running out of air {place}",
    "infant not breathing, turning blue, emergency {place}",
    "worker buried in landslide debris, alive but trapped {place}",
    "heart attack patient, unconscious, needs doctor now {place}",
    "gas leak explosion, {count} people burned and injured {place}",
]

HIGH_TEMPLATES = [
    "missing 8-year-old boy last seen {place} {time}",
    "stranded travellers need food and water {place}",
    "road blocked by fallen tree, vehicles stuck {place}",
    "elderly woman alone, needs drinking water {place}",
    "shelter needed for {count} people after fire {place}",
    "medicine needed for diabetic patient {place}",
    "missing woman last seen {place} {time}",
    "bridge damaged, people cannot cross to hospital {place}",
    "power cut in relief camp, lights needed at night",
    "found a lost child {place}, parents not found yet",
    "drinking water tanker needed in the colony {place}",
    "blankets needed for families in open ground {place}",
    "family needs shelter and food after house damage {place}",
    "insulin finished, diabetic patient needs medicine soon {place}",
    "missing teenage girl, phone switched off {time}",
    "found an elderly man {place} who cannot recall his address",
    "children alone at shelter, parents missing {time}",
    "water pump broken, colony without drinking water {time}",
    "pregnant woman needs checkup, clinic closed nearby {place}",
    "tent and tarpaulin needed for {count} displaced families {place}",
    "baby food and milk needed at relief camp {place}",
    "vehicle needed to take patients to hospital {place}",
    "generator needed at relief camp for night shelter",
    "ration kits needed for {count} stranded families {place}",
]

NORMAL_TEMPLATES = [
    "I am safe, reached home",
    "all family members safe after the earthquake",
    "we are fine, no help needed right now",
    "reached shelter safely with my family",
    "I am safe at my relative's house {place}",
    "checked in safe from the office building",
    "everyone in our street is safe",
    "I am safe, phone battery low, will update later",
    "reached relief camp safely",
    "all safe here, just sharing location",
    "I am safe and with neighbours",
    "family safe, house intact, no damage",
    "our group is safe at the community hall",
    "no injuries in our family, all accounted for",
    "safe at shelter, food and water available here",
    "checked in, we are okay after the storm",
    "house safe, we stayed with neighbours tonight",
    "all students safe, hostel evacuated without harm",
    "I am safe, staying at a friend's place {place}",
    "grandparents safe, spoke to them just now",
    "our team is safe and back at base camp",
    "safe now, road cleared, heading home",
    "everyone safe in the village, minor damage only",
    "I am fine, do not worry, will call later",
]

# Class-appropriate framing: an "urgent" prefix/suffix is never applied to
# a Normal check-in, and a "no help needed" suffix never lands on Critical.
PREFIXES = {
    "Critical": ["", "sos: ", "urgent: ", "emergency: ", "help: ", "please help: "],
    "High": ["", "help needed: ", "please note: ", "request: ", "attention: "],
    "Normal": ["", "update: ", "status: ", "check-in: ", "info: "],
}
SUFFIXES = {
    "Critical": ["", " please help", " please send help urgently",
                 " send ambulance please", " rescue needed now"],
    "High": ["", " please help", " please arrange", " help appreciated",
             " contact if you can help"],
    "Normal": ["", " all well", " no help needed", " will update again later",
               " thanks to everyone"],
}

TEMPLATES = {
    "Critical": CRITICAL_TEMPLATES,
    "High": HIGH_TEMPLATES,
    "Normal": NORMAL_TEMPLATES,
}


def normalise(text: str) -> str:
    """Normal form used for deduplication."""
    text = text.lower()
    text = re.sub(r"[^a-z0-9 ]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def render(template: str, rng: random.Random) -> str:
    text = template.replace("{place}", rng.choice(PLACES))
    text = text.replace("{count}", rng.choice(COUNTS))
    text = text.replace("{time}", rng.choice(TIMES))
    return re.sub(r"\s+", " ", text).strip().rstrip(",")


def generate_class(label: str, rng: random.Random) -> list[str]:
    """All prefix/template/suffix combinations, deduped, shuffled, capped."""
    seen: dict[str, str] = {}
    combos = list(itertools.product(
        PREFIXES[label], TEMPLATES[label], SUFFIXES[label]))
    rng.shuffle(combos)
    for prefix, template, suffix in combos:
        for _ in range(6):  # several slot draws per framing combo
            text = (prefix + render(template, rng) + suffix).strip()
            text = text[0].upper() + text[1:] if text else text
            key = normalise(text)
            if key and key not in seen:
                seen[key] = text
            if len(seen) >= PER_CLASS * 2:
                break
        if len(seen) >= PER_CLASS * 2:
            break
    texts = list(seen.values())
    rng.shuffle(texts)
    return texts[:PER_CLASS]


def main() -> None:
    rng = random.Random(RANDOM_SEED)
    rows: list[tuple[str, str, str]] = []
    for label in ("Critical", "High", "Normal"):
        texts = generate_class(label, rng)
        assert len(texts) == PER_CLASS, f"{label}: only {len(texts)} unique texts"
        rows.extend((t, label, "SIMULATED") for t in texts)
    rng.shuffle(rows)

    out = Path(__file__).resolve().parent / "sos_texts.csv"
    with out.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["text", "label", "source"])
        writer.writerows(rows)
    print(f"Wrote {len(rows)} SIMULATED rows ({PER_CLASS}/class) to {out}")


if __name__ == "__main__":
    main()
