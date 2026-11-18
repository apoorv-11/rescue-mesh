"""Tests for POST /face-match (advisory face similarity).

Behaviour adapts to the environment, per the endpoint contract:
- face model loads -> detection tests run for real;
- face model unavailable -> the 503 contract is asserted instead;
- portrait fixture missing -> the positive test skips with a reason
  (a face result is never faked).
"""

import io
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app import face
from app.main import app

client = TestClient(app)

FIXTURE = Path(__file__).parent / "fixtures" / "portrait.jpg"
MODEL_OK = face.face_model_available()


def _blank_png_bytes() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (240, 240), (255, 255, 255)).save(buf, format="PNG")
    return buf.getvalue()


def _post(a_bytes: bytes, b_bytes: bytes, a_name="a.jpg", b_name="b.jpg",
          a_type="image/jpeg", b_type="image/jpeg"):
    return client.post(
        "/face-match",
        files={
            "image_a": (a_name, a_bytes, a_type),
            "image_b": (b_name, b_bytes, b_type),
        },
    )


@pytest.mark.skipif(MODEL_OK, reason="face model is available; 503 path not exercisable")
def test_face_match_503_when_model_unavailable():
    resp = _post(b"not-an-image", b"not-an-image")
    assert resp.status_code == 503
    assert resp.json() == {"error": "face model unavailable"}


@pytest.mark.skipif(not MODEL_OK, reason="face model unavailable in this environment")
def test_face_match_blank_image_no_face_detected():
    blank = _blank_png_bytes()
    resp = _post(blank, blank, "a.png", "b.png", "image/png", "image/png")
    assert resp.status_code == 200
    body = resp.json()
    assert body["verdict"] == "NO_FACE_DETECTED"
    assert body["similarity"] is None
    assert body["facesDetected"] == {"a": False, "b": False}
    assert body["model"] == face.FACE_MODEL_NAME


@pytest.mark.skipif(not MODEL_OK, reason="face model unavailable in this environment")
@pytest.mark.skipif(
    not FIXTURE.exists(),
    reason="portrait fixture missing (see tests/fixtures/SOURCE.md)",
)
def test_face_match_same_image_likely_match():
    data = FIXTURE.read_bytes()
    resp = _post(data, data)
    assert resp.status_code == 200
    body = resp.json()
    assert body["facesDetected"] == {"a": True, "b": True}
    assert body["similarity"] > 0.99
    assert body["verdict"] == "LIKELY_MATCH"
    assert body["model"] == face.FACE_MODEL_NAME
