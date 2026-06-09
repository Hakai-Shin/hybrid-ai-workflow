# 00 — Design Summary (TL;DR)

If you read only one file in `docs/`, read this one. It distills the full design into
roughly two pages.

---

## The Thesis

**Sensitive enterprise documents never leave the enterprise boundary. The cloud
performs AI over derived, de-identified artifacts only.** This is enforced by network
isolation, a one-way artifact API, schema-validated envelopes, and the explicit absence
of any cloud-side endpoint that returns raw documents.

---

## The Architecture in One Picture

```
   ENTERPRISE (on-prem)                       CLOUD (GCP)
 ┌──────────────────────────┐                ┌──────────────────────────┐
 │ Watcher / Ingest API     │                │ Artifact Ingest (mTLS)   │
 │      │                   │                │      │                   │
 │      ▼                   │   POST         │      ▼                   │
 │ OCR (Tesseract)          │  /v1/artifacts │ Workflow Orchestrator    │
 │      │                   │ ──────────────▶│      │                   │
 │      ▼                   │   (one-way)    │      ▼                   │
 │ Redaction (Presidio)     │                │ Pub/Sub topics           │
 │      │                   │                │      │                   │
 │      ▼                   │                │      ▼                   │
 │ Artifact Packager        │                │ Workers (Cloud Run):     │
 │   (encrypted at rest)    │                │  - classify (LLM)        │
 │                          │                │  - extract-entities (LLM)│
 │                          │                │  - summarize (LLM)       │
 │                          │                │  - index (Postgres FTS)  │
 │                          │                │      │                   │
 │                          │                │      ▼                   │
 │                          │                │ Query API + Search Index │
 └──────────────────────────┘                └──────────────────────────┘
```

A tiny "forwarder" sits on the boundary and enforces `method=POST, path=/v1/artifacts`.
Nothing else crosses.

---

## The Workflow

```
accepted → classify → extract-entities → summarize → index-search → ENRICHED
```

- `classify` is critical (gates downstream branching).
- `extract-entities`, `summarize` are best-effort.
- `index-search` is critical.
- All 4 steps communicate via Pub/Sub; the orchestrator is a 150-line state machine
  in Postgres + a `step.completed` fan-in topic.

---

## The Tech

| Concern | Choice | Why |
|---|---|---|
| Language | Java 21 + Spring Boot 3 | Resume target; vast Spring ecosystem |
| Monorepo | Multi-module Maven | One image per role via `SERVICE_ROLE` env var |
| DB | PostgreSQL 16 + Flyway | Same on both sides; FTS for search |
| OCR | Tesseract 5 | Local, no API; PaddleOCR is a swap-in |
| Redaction | Presidio (Python sidecar) | Strong named-entity redaction, local |
| LLM | Ollama + llama3:8b (MVP) | Local; `LlmClient` interface for Vertex AI later |
| Workflow | Pub/Sub + small state machine | Trivial ops, easy to swap to Temporal later |
| Cloud runtime | Cloud Run | No k8s; auto-scales; pays-per-use |
| Boundary | Tiny forwarder sidecar | One-way by construction |
| Observability | Micrometer → Prometheus → Grafana | Standard, resume-friendly |

---

## What This Project Explicitly Is *Not*

(Per the brief and reinforced in `docs/09-complexity-review.md`)

- Not a chatbot
- Not a vector database
- Not a workflow engine clone
- Not a custom storage system
- Not a FHIR server
- Not a frontend-heavy app

These may appear as **supporting** components but never as the main story.

---

## Resume Bullets, Mapped to Code

> Built a document processing platform for legacy enterprise repositories, enabling
> AI-driven document intelligence workflows while ensuring protected health information
> (PHI) remained within enterprise boundaries.

→ M1–M3 (enterprise service + boundary + cloud ingest), demoed end-to-end at M6.

> Developed a service for document ingestion, OCR, de-identification, and metadata
> extraction within enterprise environments, transmitting only derived artifacts and
> workflow metadata to cloud-hosted services.

→ M1 (ingest+OCR) + M2 (redaction+packaging). The CI-gated redaction test is the
single best artifact for this bullet.

> Implemented cloud-orchestrated AI pipelines for document classification, entity
> extraction, summarization, and search enrichment on de-identified document artifacts
> using asynchronous task execution and horizontally scalable worker services.

→ M4–M6. Each step is a separate Spring Boot service on Cloud Run behind Pub/Sub.

---

## Build Timeline

| When | What you can demo |
|---|---|
| Week 1 (M0) | `docker compose up` brings up two PG instances, Pub/Sub emulator, hello endpoints |
| Week 2 (M1) | Drop a scanned image; see OCR text in the enterprise DB |
| Week 3.5 (M2) | Same image, but with PHI tokens replaced; redaction stats recorded |
| Week 4.5 (M3) | Artifact pushed over mTLS; cloud creates a workflow |
| Week 5.5 (M4) | Classification label returned by local LLM |
| Week 7 (M5) | Full pipeline: classify → entities → summary |
| Week 8 (M6) | Search query returns enriched snippets (with `[REDACTED:NAME]` visible) |
| Week 9 (M7) | Grafana dashboard, one-command demo, polished README |
| Week 11 (M8) | Terraform-deployable to a real GCP project |
| Week 12 (M9, opt) | Vertex AI swap-in with A/B comparison |

**Resume-ready at M6 (~8 weeks part-time). Production-shaped at M8 (~11 weeks).**

---

## Three Things That Make This Resume-Worthy

1. **The boundary is real, not aspirational.** The forwarder sidecar + isolated Docker
   networks + schema-validated envelopes + the *absence* of a `GET /documents/{id}/file`
   on the cloud side together make the data-classification boundary a load-bearing
   property of the system, not a sentence in the README.

2. **The interfaces are honest.** `LlmClient`, `SearchIndex`, `Redactor`, `OcrEngine` are
   Spring beans, not design aspirations. The "swap to Vertex AI" promise is one config
   flag (and we actually do the swap in M9).

3. **The orchestration is deliberately small.** A 150-line state machine + Pub/Sub
   demonstrates the same architectural skill as a Temporal deployment, without the
   operational tax — and we explicitly call out when we'd reach for Temporal.

---

## Open Questions (for the implementer)

1. **Ollama model choice.** `llama3:8b-instruct` is the default; we may want a
   `mistral:7b` for entity extraction if 8B is too slow on a laptop. (Measure, don't
   guess.)
2. **PDF handling.** Tesseract doesn't read PDFs. We need `pdftoppm` or PDFBox in front
   of Tesseract for the MVP. Confirm the simplest path that supports ≤ 20-page PDFs.
3. **Spans for entities.** Returning `[120,129]` offsets in the redacted text is
   fragile if the redactor changes the text length. Decide: store offsets in the
   *original* text or the *redacted* text? (Original is more useful but harder to map.)
4. **mTLS in dev.** Self-signed CA + a `make certs` target. Decide whether to use
   `mkcert` (nicer) or plain `openssl` (no install step).
5. **Presidio model size.** The default analyzer uses spaCy `en_core_web_lg` (~700MB).
   Confirm the demo container has the headroom; document the size in the README.

None of these are blockers; they each get a 30-minute decision at the start of the
relevant milestone.
