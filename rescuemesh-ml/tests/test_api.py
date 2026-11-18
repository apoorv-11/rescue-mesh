"""API tests for the RescueMesh ML service (all data SIMULATED)."""

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert body["service"] == "rescuemesh-ml"


def test_priority_valid_response_and_scores_sum():
    resp = client.post("/priority", json={"text": "Need food and water for stranded families"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["priority"] in (1, 2, 3)
    assert set(body["scores"].keys()) == {"1", "2", "3"}
    assert sum(body["scores"].values()) == pytest.approx(1.0, abs=1e-6)


def test_priority_obvious_critical():
    resp = client.post(
        "/priority",
        json={"text": "trapped under debris, building collapsed, people injured"},
    )
    assert resp.status_code == 200
    assert resp.json()["priority"] == 1


def test_priority_obvious_normal():
    resp = client.post(
        "/priority",
        json={"text": "I am safe, reached home, all family members are fine"},
    )
    assert resp.status_code == 200
    assert resp.json()["priority"] == 3


def test_priority_empty_text_is_400():
    resp = client.post("/priority", json={"text": "   "})
    assert resp.status_code == 400


def test_duplicate_flags_paraphrase():
    resp = client.post("/duplicate", json={
        "text": "Missing 8-year-old boy last seen near the market",
        "candidates": [
            {"id": "m1", "text": "Missing 8-year-old boy last seen near the market, please help find him"},
            {"id": "m2", "text": "I am safe, reached home with my family"},
        ],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["is_duplicate"] is True
    assert [m["id"] for m in body["matches"]] == ["m1"]
    assert body["matches"][0]["score"] >= body["threshold"]


def test_duplicate_rejects_unrelated():
    resp = client.post("/duplicate", json={
        "text": "Missing 8-year-old boy last seen near the market",
        "candidates": [
            {"id": "x1", "text": "Drinking water tanker needed in the colony"},
            {"id": "x2", "text": "Road blocked by fallen tree, vehicles stuck"},
        ],
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["is_duplicate"] is False
    assert body["matches"] == []


def test_duplicate_empty_text_is_400():
    resp = client.post("/duplicate", json={"text": "", "candidates": []})
    assert resp.status_code == 400


def test_duplicate_no_candidates():
    resp = client.post("/duplicate", json={
        "text": "Missing woman last seen at the bus stand",
        "candidates": [],
    })
    assert resp.status_code == 200
    assert resp.json()["matches"] == []
