# 08 — Milestones & Build Order

The build is sequenced so that **every milestone produces a runnable, demoable artifact**.
No "scaffolding-only" milestones. This is critical for a solo project — momentum is
delivered continuously.

Each milestone lists:
- **Goal** — the one sentence that describes what a reviewer can do.
- **Deliverables** — concrete artifacts.
- **Demo path** — what the reviewer clicks/runs.
- **Resume bullet it unlocks** — link back to the target bullets in the task brief.

Estimated effort assumes 1 engineer, ~10–15 h/week of focused work.

---

## M0 — Repo & Local Stack (≈ 1 week)

**Goal:** `git clone && docker compose up` brings up two PostgreSQL containers, the
Pub/Sub emulator, fake-gcs-server, and a "hello" Spring Boot app on each network.

**Deliverables**
- Monorepo with Maven multi-module: `enterprise-service/`, `cloud-services/`,
  `boundary/forwarder/`, `shared/contracts/` (proto/JSON schemas, common Java DTOs).
- `compose.yaml` with isolated networks.
- `Makefile` with `make up`, `make logs`, `make down`, `make test`.
- README with a 5-minute "what is this" section.
- GitHub Actions: build + unit tests on PR.

**Demo path:** reviewer runs `make up`, sees "hello" on `localhost:8080` and
`localhost:8081`.

**Resume:** none yet — this is the platform.

---

## M1 — Enterprise Ingest + OCR (≈ 1 week)

**Goal:** drop a file in a watched folder; see OCR'd text in the enterprise DB.

**Deliverables**
- `enterprise-service` boots with role `ingest`.
- `DocumentWatcher` polls a folder; `IngestController` accepts `POST /v1/documents`.
- `OcrEngine` (Tesseract CLI wrapper) writes OCR text to `documents` and `raw_blobs`.
- Tesseract runs as a sidecar; service calls it via HTTP.
- Integration test: a sample PNG → row in `documents`.

**Demo path:** drop a sample scanned note in `./intake`; `GET /v1/documents/{id}` shows
`status=PROCESSING → REDACTED` (status machine exists but redaction is a no-op stub).

**Resume:** "Built a service for document ingestion and OCR within enterprise environments."

---

## M2 — Redaction + Artifact Packaging (≈ 1.5 weeks)

**Goal:** OCR'd text gets PHI-redacted locally; a clean artifact envelope exists in
the enterprise DB.

**Deliverables**
- `Redactor` interface with two implementations:
  - `RegexRedactor` (fast, covers SSN/MRN/email/phone/date patterns).
  - `PresidioRedactor` (calls presidio-analyzer sidecar; covers names + addresses).
- Configurable policy: `phi-strict-v1` enables both.
- Redaction stats recorded per document.
- `ArtifactPackager` produces a `document_artifacts` row with AES-GCM-encrypted
  `redacted_text_cipher`.
- **CI test that runs the redactor on a fixture with a fake SSN, name, MRN and asserts
  none of the original tokens appear in the redacted output.** This test gates every PR.

**Demo path:** same PNG, now `GET /v1/documents/{id}` shows
`redaction_stats={names:2, ssn:1, mrn:1}` and the raw API
`GET /v1/admin/documents/{id}/raw` shows `[REDACTED:NAME]` tokens.

**Resume:** "…OCR, de-identification, and metadata extraction…transmitting only derived
artifacts and workflow metadata to cloud-hosted services" — this milestone lights up
the redaction half.

---

## M3 — Cloud Ingest Endpoint + Artifact Validation (≈ 1 week)

**Goal:** enterprise side can push an artifact over mTLS; cloud side accepts it and
returns a `workflow_id`.

**Deliverables**
- `cloud-services` boots with role `ingest`.
- `ArtifactIngestController` (`POST /v1/artifacts`) validates against the JSON schema
  in `shared/contracts/artifact.v1.json`.
- `WorkflowOrchestrator` creates `workflows` + `workflow_steps` rows in one transaction.
- `PubSubPublisher` publishes the first `doc.classify` message.
- `boundary/forwarder` is deployed and enforces `path=/v1/artifacts, method=POST`.
- The enterprise `CloudSyncClient` calls the forwarder over mTLS (self-signed CA in dev).
- Integration test: end-to-end from a test enterprise client to a Testcontainers cloud.

**Demo path:** enterprise pushes an artifact; cloud logs show
`workflow.accepted → workflow_steps: 4 PENDING → step[0]=classify message published`.

**Resume:** "…transmitting only derived artifacts and workflow metadata to
cloud-hosted services" — this milestone proves the one-way push.

---

## M4 — Classifier Worker (≈ 1 week)

**Goal:** the `classify` step actually runs and persists a label.

**Deliverables**
- `cloud-services` boots with role `worker-classify`.
- `ClassifierWorker` consumes `doc.classify`, calls Ollama (HTTP), writes
  `workflow_steps.status=DONE, result=...`, publishes to `cloud.workflow.step.completed`.
- `WorkflowStepAdvancer` consumes the fan-in topic, sees `classify` done, publishes
  `doc.extract-entities`.
- Local LLM behind a `LlmClient` interface; same JAR can later point at Vertex AI.
- Integration test uses a recorded Ollama response (no live LLM in CI).

**Demo path:** `GET /v1/workflows/{wf_id}` shows `classify=DONE, classification.label=...`.

**Resume:** "cloud-orchestrated AI pipelines for document classification."

---

## M5 — Summarize + Entity Workers (≈ 1.5 weeks)

**Goal:** full chain runs to completion and produces summary + entities.

**Deliverables**
- `worker-entities` and `worker-summarize` services.
- Entity extraction prompt varies by classification label.
- Summarizer has a 80-word cap and a post-generation redaction-balance check.
- All 4 step types now runnable; orchestrator advances the chain end-to-end.
- Failure injection test: kill Ollama mid-run, assert workflow goes `PARTIAL`.

**Demo path:** reviewer's `curl` upload returns within ~30 s, then
`GET /v1/workflows/{wf_id}` shows `status=ENRICHED` with full results.

**Resume:** "document classification, entity extraction, summarization."

---

## M6 — Indexer + Search API (≈ 1 week)

**Goal:** searchable index; demo query returns a hit with the redaction tokens visible.

**Deliverables**
- `worker-index` writes one `enriched_documents` row with `tsvector`.
- `query` service exposes `GET /v1/search?q=...`.
- GIN index in place; search latency < 50 ms on 10k rows.
- Integration test: 100-doc seed, query, assert ordering + redaction tokens in snippet.

**Demo path:** reviewer types `?q=metformin` and gets back docs whose snippets contain
`[REDACTED:NAME]` — the visible proof that no PHI leaked.

**Resume:** "search enrichment on de-identified document artifacts."

---

## M7 — Observability & Demo Polish (≈ 1 week)

**Goal:** Grafana shows the system running healthily; the demo is repeatable.

**Deliverables**
- Prometheus scrapes both DB pools + every service.
- Grafana dashboard: workflows/min by status, p50/p95 step latency, error rate, DLQ depth.
- Structured JSON logs everywhere with `trace_id`, `workflow_id`, `document_id`.
- One-command demo script: `./bin/demo.sh` (resets state, drops a sample doc, polls
  status, runs a search query).
- 5-minute screencast GIF embedded in the README.
- Architecture Decision Records (ADRs) written for each of the top 5 choices.

**Demo path:** reviewer follows the README; Grafana dashboard is the headline visual.

**Resume:** none new — but this is what *makes the bullet believable* in an interview.

---

## M8 — Production-Readiness Slice (≈ 2 weeks)

**Goal:** a believable production deployment on GCP, even if it's not at scale.

**Deliverables**
- Terraform in `infra/`:
  - VPC, private Cloud SQL, CMEK key in Cloud KMS.
  - Pub/Sub topics + subscriptions with dead-letter topics.
  - Cloud Run services for each cloud role with Workload Identity.
  - Secret Manager entries for the mTLS certs.
  - VPC-SC perimeter around the cloud project.
- `bin/deploy-dev.sh` and `bin/teardown-dev.sh`.
- A second smoke-test suite that runs against the deployed dev env.
- ADR: "Why we did not put the LLM on Cloud Run" (Ollama needs a long-running VM; we use
  a small `n1-standard-4` GCE instance, document the trade-off, and plan a Vertex AI
  migration behind the same `LlmClient`).

**Demo path:** reviewer follows `infra/README.md`; on a clean GCP project,
`bin/deploy-dev.sh` brings up a working stack in < 30 min.

**Resume:** the strongest bullet — *"built and deployed a hybrid-cloud document
processing platform on GCP with Cloud Run, Pub/Sub, Cloud SQL, Cloud Storage, and
VPC Service Controls."*

---

## M9 (Optional) — Swap to Vertex AI + Vertex AI Search (≈ 1 week)

**Goal:** show the architecture's `LlmClient`/`SearchIndex` interfaces are not just
theoretical — actually swap the implementations.

**Deliverables**
- `VertexLlmClient` (Spring bean) replaces `OllamaLlmClient` via config flag.
- `VertexSearchIndex` (Spring bean) replaces `PostgresSearchIndex`.
- A/B comparison: same 100-doc seed, latency + quality side-by-side.
- ADR: "When hosted LLMs are acceptable for this workload" (covers DPIA, BAA, region
  pinning, etc.).

**Resume:** "designed interface boundaries to make hosted-LLM adoption a config change."

---

## Total Estimate

| Milestone | Effort | Cumulative |
|---|---|---|
| M0 | 1 wk | 1 wk |
| M1 | 1 wk | 2 wk |
| M2 | 1.5 wk | 3.5 wk |
| M3 | 1 wk | 4.5 wk |
| M4 | 1 wk | 5.5 wk |
| M5 | 1.5 wk | 7 wk |
| M6 | 1 wk | 8 wk |
| M7 | 1 wk | 9 wk |
| M8 | 2 wk | 11 wk |
| M9 (opt) | 1 wk | 12 wk |

A solo engineer at 10–15 focused hours/week lands a **resume-ready MVP at M6 (~8 weeks)**
and a **production-shaped platform at M8 (~11 weeks)**. Comfortable margin for a few
months of part-time work.

---

## Build-Order Rules We Commit To

1. **Demoable every Friday.** Every week ends with a green-path run.
2. **Schema before code.** A breaking schema change is the only acceptable reason to
   touch more than two services in one PR.
3. **Redaction test gates all merges.** No PR merges if the redaction fixture fails.
4. **One source of truth for contracts.** `shared/contracts/` is the only place artifact
   shapes live; both sides import it.
5. **Cloud never imports enterprise modules.** Enforced by Maven dependency rules.
6. **No new technology without an ADR.** ADRs live in `docs/adr/`.
