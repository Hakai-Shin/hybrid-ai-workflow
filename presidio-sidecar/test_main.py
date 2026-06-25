"""pytest tests for the Presidio sidecar."""
import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_analyze_detects_person():
    response = client.post("/analyze", json={"text": "John Doe is the patient.", "language": "en"})
    assert response.status_code == 200
    results = response.json()
    entity_types = [r["entity_type"] for r in results]
    assert "PERSON" in entity_types


def test_anonymize_replaces_entities():
    text = "Call me at 555-867-5309 or email me at patient@example.com"
    analyze_resp = client.post("/analyze", json={"text": text, "language": "en"})
    assert analyze_resp.status_code == 200

    anon_resp = client.post("/anonymize", json={
        "text": text,
        "analyzer_results": analyze_resp.json(),
    })
    assert anon_resp.status_code == 200
    body = anon_resp.json()
    assert "555-867-5309" not in body["text"]
    assert body["entity_count"] > 0


def test_analyze_empty_text():
    response = client.post("/analyze", json={"text": "", "language": "en"})
    assert response.status_code == 200
    assert response.json() == []
