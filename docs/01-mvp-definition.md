# 01 — MVP Definition

## What the MVP Must Prove

The MVP is **not** a finished product. It is a demonstrable end-to-end system that proves the
core architectural thesis:

> *Sensitive enterprise documents never leave the enterprise boundary, yet the cloud can still
> drive AI-powered document intelligence over derived, de-identified artifacts.*

If a reviewer runs the MVP locally with Docker Compose, they should be able to:

1. Drop a sample "legacy" document (PDF or image) into a local intake folder.
2. Watch the enterprise-side service perform OCR + PHI redaction + metadata extraction.
3. See a derived artifact (de-identified text + extracted metadata) appear in a "cloud" service
   running on the same host (simulating Cloud Run).
4. See the cloud-side orchestrator dispatch classification → entity extraction → summarization
   → search-index enrichment as async tasks.
5. Query an enriched search index and get back a summary, entities, and classification label
   — all derived from the original document but containing **zero raw PHI**.

That is the MVP. Everything else is iteration.

---

## In Scope (MVP)

| Capability | Where it runs | Tech |
|---|---|---|
| Local file intake (watch folder + REST upload) | Enterprise | Spring Boot |
| OCR | Enterprise | Tesseract (PaddleOCR optional) |
| PHI/PII redaction | Enterprise | Regex + Presidio (local) |
| Metadata extraction (doc type, dates, author hints) | Enterprise | Spring Boot |
| Secure artifact upload to cloud bucket | Enterprise → Cloud | mTLS, GCS client |
| Cloud orchestrator (workflow state) | Cloud | Spring Boot + Cloud Pub/Sub |
| Async workers (classify, entities, summarize) | Cloud | Spring Boot workers on Cloud Run |
| Local LLM inference (Ollama) for AI steps | Cloud (still inside the boundary of the "cloud project") | Ollama + llama3 |
| Search enrichment index | Cloud | PostgreSQL (later: Vertex AI Search / OpenSearch) |
| Observability (logs, metrics, trace IDs) | Both | Micrometer → Grafana / Cloud Logging |

## Explicitly Out of Scope (MVP)

- Frontend UI (CLI + REST + a minimal React admin page is enough).
- Auth provider integration (Keycloak stubbed; document SSO model in design only).
- Multi-tenant enterprise support.
- Vector embeddings / RAG chatbot. (We may store embeddings, but no chat UX.)
- Production-grade key management (use a local mock KMS; document the real design).
- True air-gapped operation (the MVP runs on one machine; the architecture is split via
  Docker network namespaces to *simulate* the boundary).

## MVP Acceptance Checklist

- [ ] End-to-end run on a fresh `docker compose up`.
- [ ] `curl` upload → enriched result visible in search index in < 60 s.
- [ ] A test fixture document containing a fake SSN, name, and MRN passes through and
      does **not** appear in the cloud-side search index or logs.
- [ ] Cloud-side components never receive the original file (verifiable via network capture
      or by running the cloud stack with no inbound file endpoint).
- [ ] All cloud-side processing traces back to a `document_id`; logs include trace ID.
- [ ] README with a 5-minute "how to demo it" section.

---

## Non-Goals Reinforced

These were called out in the task and we re-confirm them. They are **not** the focus:

- Not a chatbot.
- Not a vector database product (we use one only as an index).
- Not a workflow engine (we orchestrate with Pub/Sub + a thin state machine in Postgres).
- Not a storage system (we use GCS).
- Not a FHIR server.
- Not a frontend-heavy app (one minimal admin page max).
