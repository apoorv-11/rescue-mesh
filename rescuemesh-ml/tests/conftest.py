"""Ensure the fitted model exists before API tests run.

If app/model.joblib is missing (fresh checkout), train it once per test
session using the exact train.py pipeline on the SIMULATED dataset.
"""

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from train import MODEL_PATH  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def trained_model():
    if not MODEL_PATH.exists():
        import train
        train.main()
    return MODEL_PATH
