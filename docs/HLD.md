# DocuBrain — High-Level Design

> System design document covering all dimensions typically explored in a 60-minute
> enterprise system design interview.

---

## 1. Requirements

### 1.1 Functional Requirements

| # | Requirement |
|---|---|
| F1 | Accept multi-format document uploads (PDF, DOCX, TIFF, PNG) via authenticated REST API |
| F2 | Extract text from documents using OCR; preserve page structure |
| F3 | Detect and redact all PHI categories (name, DOB, SSN, MRN, address, phone, email, IP) |
| F4 | Classify each document into a pre-defined type (medical_record, invoice, contract, other) |
| F5 | Extract structured entities (dates, amounts, ICD codes, diagnoses) |
| F6 | Produce an abstractive summary (≤ 200 words) |
| F7 | Generate a dense vector embedding and upsert into a searchable vector index |
| F8 | Store all results in a queryable data warehouse |
| F9 | Provide job status polling API (RECEIVED → PROCESSING → PUBLISHED → ERROR) |
| F10 | Support semantic search over processed documents |

### 1.2 Non-Functional Requirements

| # | Requirement | Target |
|---|---|---|
| NF1 | PHI must never leave the enterprise network boundary | Architectural invariant |
| NF2 | End-to-end processing latency (ingest → all results stored) | p95 ≤ 90 s |
| NF3 | Availability of the ingest API | 99.9% |
| NF4 | Throughput | 500 docs/hour sustained, 2 000 docs/hour burst |
| NF5 | Document size | Up to 50 MB / 200 pages |
| NF6 | Data retention | Raw docs: 7 years; derived data: indefinite |
| NF7 | Audit log for every PHI redaction event | Required |
| NF8 | Encryption at rest and in transit | Required |
| NF9 | Multi-tenancy (source_id namespace isolation) | Required |
| NF10 | Cold-start latency for cloud workers | ≤ 2 s |

### 1.3 Out of Scope

- Real-time document streaming (Kafka ingestion)
- User-facing search UI
- Fine-tuning the LLM on customer data
- Document version control / diff

---## 2. Capacity Estimation

### 2.1 Assumptions

- 500 docs/hour steady state; peak 4x = 2 000 docs/hour
- Average document: 5 pages, 3 MB raw, 10 KB extracted text
- Average processing time per doc: 30–60 s (OCR dominates)
- Retention: 7 years of docs

### 2.2 Storage

| Layer | Per doc | Daily (12 000 docs) | Annual |
|---|---|---|---|
| Raw docs (GCS cold) | 3 MB | 36 GB | 13 TB |
| ArtifactPackage JSON (GCS) | 15 KB | 180 MB | 65 GB |
| BigQuery derived data | 5 KB | 60 MB | 22 GB |
| Vector embeddings (768-dim float32) | 3 KB | 36 MB | 13 GB |
| **Total** | | ~37 GB/day | ~13 TB/year |

### 2.3 Compute

| Service | Instance size | Count (steady) | Count (peak) |
|---|---|---|---|
| Enclave (Spring Boot) | 4 vCPU / 8 GB | 2 | 4 |
| Presidio sidecar | 2 vCPU / 4 GB | 2 | 4 |
| Dispatcher (Cloud Run) | 1 vCPU / 512 MB | 1–3 | up to 10 |
| AI Workers × 4 (Cloud Run) | 2 vCPU / 1 GB | 0–2 each | up to 10 each |

### 2.4 Network

- Ingest bandwidth: 500 docs/hr × 3 MB = 1.5 GB/hr = ~0.4 MB/s (negligible)
- Cross-boundary payload: 500/hr × 15 KB = 7.5 MB/hr (PHI-free ArtifactPackage only)

### 2.5 LLM API Calls

- 4 Vertex AI calls per document (classify, extract, summarise, embed)
- 2 000/hr peak → 8 000 Vertex AI requests/hr → ~2.2 requests/s
- Gemini 1.5 Flash quota: 1 000 RPM per project (sufficient; request quota increase for prod)

---

## 3. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    Enterprise Enclave (on-prem)              │
│                                                              │
│  Client ──POST /api/v1/ingest──► IngestController           │
│                                         │                    │
│                              ┌──────────▼──────────────┐    │
│                              │   IngestionService       │    │
│                              │  (async, @EnableAsync)   │    │
│                              │  1. Tika OCR             │    │
│                              │  2. DeidClient ──HTTP──► Presidio │
│                              │  3. MetadataService      │    │
│                              │  4. PackagerService      │    │
│                              └──────────┬───────────────┘    │
│                                         │ ArtifactPackage    │
│                                    ┌────▼───────┐            │
│                                    │  H2 / PG   │            │
│                                    │ (job state)│            │
│                                    └────────────┘            │
│  ◄──────────────────── PHI BOUNDARY ───────────────────────► │
└──────────────────────────────────────────────────────────────┘
                                         │ GCS upload + Pub/Sub publish
                              ┌──────────▼────────────┐
                              │  GCS: artifact bucket  │
                              │  Pub/Sub: artifact-    │
                              │          intake topic  │
                              └──────────┬────────────┘
                                         │ push subscription
                              ┌──────────▼────────────┐
                              │ Dispatcher (Cloud Run) │
                              │  Go 1.22               │
                              └──┬──┬──┬──┬───────────┘
                      Cloud Tasks fan-out (4 queues)
            ┌─────────┘  │  │  └───────────┐
     ┌──────▼───┐  ┌─────▼──┐  ┌──────▼──┐  ┌─▼──────────┐
     │Classifier│  │Extractor│  │Summariser│  │  Embedder  │
     │Go+Gemini │  │Go+Gemini│  │Go+Gemini │  │Go+Vertex   │
     │BigQuery  │  │BigQuery │  │BigQuery  │  │VectorSearch│
     └──────────┘  └─────────┘  └──────────┘  └────────────┘
```

### Trust Boundary Contract

The `ArtifactPackage` is the **only** payload that crosses the enterprise boundary. It contains:

- `redacted_text` — PHI replaced with `[REDACTED_<TYPE>]` tokens
- `phi_entity_count` — aggregate count (never entity values)
- `metadata` — file format, language, word count, page count
- `job_id`, `source_id` (opaque), `enclave_version`

**Never included:** raw text, original filename, patient name, MRN, DOB, SSN, address.

---## 4. Detailed Component Design

### 4.1 Enclave — Java 21 / Spring Boot 3.3

| Component | Responsibility |
|---|---|
| `IngestController` | Validates multipart upload; creates job record; fires async task; returns 202 |
| `IngestionService` | Orchestrates OCR → de-id → metadata → packaging; updates job state machine |
| `OcrService` | Wraps Apache Tika 2.9.2; auto-detects MIME; extracts text + page count |
| `DeidClient` | WebClient wrapper for Presidio sidecar; posts to `/analyze` then `/anonymize` |
| `MetadataService` | Detects language (Tika); counts words; records file format |
| `PackagerService` | Builds `ArtifactPackage` record; serialises to JSON; uploads to GCS; publishes Pub/Sub message |
| `JobRepository` | JPA + H2 (dev) / Postgres (prod); stores job lifecycle state |

**State machine:** `RECEIVED → OCR_DONE → DEIDENTIFIED → PUBLISHED | ERROR`

**Async:** `@EnableAsync` with a bounded `ThreadPoolTaskExecutor` (core=4, max=16, queue=100).
Backpressure: if the queue is full, the executor rejects with a 503 response via `RejectedExecutionHandler`.

### 4.2 Presidio Sidecar — Python 3.12 / FastAPI

- Runs in the same Docker Compose network / Kubernetes pod as the enclave
- `AnalyzerEngine` uses `en_core_web_lg` spaCy model (NER) + rule-based recognizers
- Entities: PERSON, EMAIL_ADDRESS, PHONE_NUMBER, DATE_TIME, MEDICAL_LICENSE, US_SSN, LOCATION, URL, IP_ADDRESS, NRP
- `/analyze` → returns entity spans + type + confidence
- `/anonymize` → replaces spans with `[REDACTED_<TYPE>]`
- Stateless; horizontally scalable within the enclave pod

### 4.3 Dispatcher — Go 1.22 / Cloud Run

- Receives Pub/Sub push HTTP requests (JSON envelope with base64 message)
- Decodes `ArtifactPackage` notification (`job_id`, `gcs_uri`)
- Fans out to **4 Cloud Tasks queues** in parallel (goroutines)
- Each task targets a specific worker Cloud Run service via OIDC-authenticated HTTPS
- OIDC token obtained from Cloud Tasks; service account: `docubrain-tasks-invoker`
- Dispatch deadline: 10 minutes; retry policy inherited from queue config

### 4.4 AI Workers — Go 1.22 / Cloud Run

All workers share the same pattern: read `ArtifactPackage` from GCS → call Vertex AI → write to BigQuery.

| Worker | Vertex AI Model | BigQuery Table | Key Output |
|---|---|---|---|
| Classifier | `gemini-1.5-flash` | `classifications` | `doc_type`, `confidence`, `reasoning` |
| Extractor | `gemini-1.5-flash` | `entities` | Structured entity array (dates, amounts, codes) |
| Summariser | `gemini-1.5-flash` | `summaries` | `summary_text` (≤200 words), `key_points` array |
| Embedder | `text-embedding-004` | `embeddings` | 768-dim vector; upserted to Vector Search index |

All Gemini prompts request **strict JSON output** (`response_mime_type: application/json`).

### 4.5 Data Warehouse — BigQuery

5 tables, all partitioned by `created_at` (date), clustered by `source_id`:

```sql
jobs              (job_id, source_id, status, phi_entity_count, page_count, created_at)
classifications   (job_id, source_id, doc_type, confidence, reasoning, created_at)
entities          (job_id, source_id, entity_type, value, context, created_at)
summaries         (job_id, source_id, summary_text, key_points JSON, created_at)
embeddings        (job_id, source_id, model, dimension, vector_search_id, created_at)
```

### 4.6 Vector Search — Vertex AI

- Index: 768-dim, streaming update enabled
- Distance metric: DOT_PRODUCT (embeddings are L2-normalised by `text-embedding-004`)
- Deployed endpoint exposes `findNeighbors` (top-k ANN)
- BigQuery `embeddings` table stores `vector_search_id` for cross-reference

---## 5. Data Flow

### 5.1 Happy-Path Sequence

```
Client          Enclave                 Presidio        GCS     Pub/Sub  Dispatcher  Workers
  |                |                      |              |          |          |         |
  |─POST /ingest──►|                      |              |          |          |         |
  |◄─202 {job_id}─|                      |              |          |          |         |
  |                |─Tika OCR────────────►|              |          |          |         |
  |                |◄─raw_text────────────|              |          |          |         |
  |                |─/analyze────────────►|              |          |          |         |
  |                |◄─entity_spans────────|              |          |          |         |
  |                |─/anonymize──────────►|              |          |          |         |
  |                |◄─redacted_text───────|              |          |          |         |
  |                |─upload ArtifactPkg──────────────────►|         |          |         |
  |                |─publish notification────────────────────────────►|         |         |
  |                |                      |              |          |──push────►|         |
  |                |                      |              |          |          |─fan-out─►|
  |                |                      |              |          |          |         |─Gemini─►
  |                |                      |              |          |          |         |─BigQuery write
  |                |                      |              |          |          |         |─Vector Search upsert
```

### 5.2 Error Handling in the Flow

- **OCR failure:** Job → `ERROR`; raw file retained in local scratch dir for reprocessing
- **Presidio timeout** (default 30 s): `DeidClient` retries 3× with exponential backoff; if all fail → job `ERROR`
- **GCS upload failure:** `PackagerService` retries 3× (Spring Retry); on exhaustion → job `ERROR`
- **Pub/Sub publish failure:** Same retry; on failure, the GCS artifact is already uploaded — a manual re-trigger can re-publish without re-processing
- **Worker failure:** Cloud Tasks retries with exponential backoff up to 5 attempts; dead-lettered to `dlq-queue`
- **Gemini rate-limit (429):** Worker returns 429; Cloud Tasks retries — Gemini quota errors are naturally idempotent

---

## 6. API Design

### 6.1 Ingest API (Enclave)

```
POST /api/v1/ingest
Content-Type: multipart/form-data

Fields:
  file      (binary)   — document file, max 50 MB
  sourceId  (string)   — opaque upstream system identifier
  docTypeHint (string) — optional hint: medical_record | invoice | contract

Response 202:
{
  "job_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "RECEIVED"
}

Response 413: file exceeds 50 MB
Response 415: unsupported MIME type
Response 503: processing queue full
```

```
GET /api/v1/status/{jobId}

Response 200:
{
  "job_id": "550e8400-...",
  "status": "PUBLISHED",          // RECEIVED | OCR_DONE | DEIDENTIFIED | PUBLISHED | ERROR
  "phi_entity_count": 7,          // only present when PUBLISHED
  "page_count": 3,
  "created_at": "2024-01-15T10:30:00Z",
  "error_message": null
}

Response 404: unknown job_id
```

### 6.2 Internal Worker API (Cloud Tasks → Cloud Run)

```
POST /classify   (Classifier worker)
POST /extract    (Extractor worker)
POST /summarise  (Summariser worker)
POST /embed      (Embedder worker)

Body (JSON):
{
  "job_id": "...",
  "gcs_uri": "gs://docubrain-artifacts-dev/550e8400-....json"
}

Response 200: { "status": "ok" }
Response 429: Vertex AI quota exceeded (Cloud Tasks will retry)
Response 500: internal error (Cloud Tasks will retry)
```

### 6.3 Presidio Sidecar API (internal only)

```
POST /analyze    — returns entity spans
POST /anonymize  — returns redacted text
GET  /health     — returns {"status":"ok"}
```

---## 7. Database Design

### 7.1 Enclave — Job State Store (H2 dev / Postgres prod)

```sql
CREATE TABLE jobs (
    job_id          UUID        PRIMARY KEY,
    source_id       VARCHAR(256) NOT NULL,
    status          VARCHAR(32)  NOT NULL,   -- enum: RECEIVED, OCR_DONE, DEIDENTIFIED, PUBLISHED, ERROR
    doc_type_hint   VARCHAR(64),
    page_count      INTEGER,
    phi_entity_count INTEGER,
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_jobs_source_id ON jobs(source_id);
CREATE INDEX idx_jobs_status    ON jobs(status);
```

This table is the **only persistent state inside the enclave**. It is intentionally minimal —
no PHI, no redacted text, no file references.

### 7.2 Cloud — BigQuery Schema

All tables share `job_id` as the join key and are partitioned by `DATE(created_at)`.

```sql
-- jobs (mirror of enclave state, written by workers on completion)
job_id STRING, source_id STRING, doc_type STRING, phi_entity_count INT64,
page_count INT64, enclave_version STRING, created_at TIMESTAMP

-- classifications
job_id STRING, source_id STRING, doc_type STRING NOT NULL,
confidence FLOAT64, reasoning STRING, model_version STRING, created_at TIMESTAMP

-- entities (one row per extracted entity instance)
job_id STRING, source_id STRING, entity_type STRING, value STRING,
context STRING, page_number INT64, confidence FLOAT64, created_at TIMESTAMP

-- summaries
job_id STRING, source_id STRING, summary_text STRING,
key_points JSON, word_count INT64, model_version STRING, created_at TIMESTAMP

-- embeddings
job_id STRING, source_id STRING, model STRING, dimension INT64,
vector_search_datapoint_id STRING, created_at TIMESTAMP
```

### 7.3 Data Lifecycle

| Store | Retention | Deletion mechanism |
|---|---|---|
| Raw docs on-prem | 7 years | Enclave scheduled job; secure wipe |
| ArtifactPackage (GCS) | 7 years | GCS lifecycle rule: `DELETE after 2555 days` |
| BigQuery derived data | Indefinite | Manual purge via `source_id` for right-to-erasure |
| Vector Search index | Indefinite | `removeDatapoints` API by `vector_search_datapoint_id` |
| H2 / Postgres job table | 90 days | Spring Batch cleanup job |

---

## 8. Security & Compliance

### 8.1 PHI Trust Boundary

The central invariant: **PHI is detected and masked inside the enclave before any network
egress.** This is enforced at the data model level — `ArtifactPackage` has no field that
can carry raw PHI. Code review policy: any PR adding a field to `ArtifactPackage` requires
a security sign-off.

### 8.2 Encryption

| Layer | Mechanism |
|---|---|
| Data in transit (enclave→GCS) | TLS 1.3 |
| Data in transit (enclave→Presidio) | HTTP on localhost / same pod network |
| GCS at rest | Google-managed AES-256 (upgrade to CMEK for regulated customers) |
| BigQuery at rest | Google-managed encryption (CMEK optional) |
| On-prem raw files | Disk-level encryption (customer responsibility) |
| Pub/Sub messages | TLS 1.3; payload is ArtifactPackage (no PHI) |

### 8.3 Authentication & Authorisation

| Path | Mechanism |
|---|---|
| Client → Enclave | mTLS + API key (enterprise gateway) |
| Enclave → GCS | Service account key / Workload Identity |
| Enclave → Pub/Sub | Service account key / Workload Identity |
| Pub/Sub → Dispatcher | OIDC push subscription token |
| Cloud Tasks → Workers | OIDC token (service account `docubrain-tasks-invoker`) |
| Workers → Vertex AI | Application Default Credentials (Cloud Run identity) |
| Workers → BigQuery | Application Default Credentials |

### 8.4 Audit Logging

- Every de-identification event logged to structured JSON: `{job_id, source_id, entity_types[], phi_entity_count, timestamp}`
- Logs shipped to Cloud Logging via Logstash JSON encoder (never include entity values)
- Immutable audit sink: Cloud Logging with log bucket `_Required` (400-day retention, no deletion)

### 8.5 Threat Model

| Threat | Control |
|---|---|
| Exfiltration via ArtifactPackage | Schema has no PHI fields; code review gate |
| Presidio sidecar bypass | DeidClient is the only code path to GCS upload |
| Malicious document (Tika exploit) | Tika runs in subprocess sandbox; CVE patching via Dependabot |
| LLM prompt injection via doc content | Redacted text only reaches Vertex AI; prompts use strict JSON schema |
| GCS bucket public exposure | Uniform bucket-level access; no public ACLs; Terraform enforces |
| Worker compromise | Workers have read-only GCS + write-only BQ; no PHI access possible |

---## 9. Scalability & Performance

### 9.1 Scalability Strategy

| Component | Scaling mechanism | Bottleneck |
|---|---|---|
| Enclave ingest | Horizontal (load-balanced pods); async processing decouples ingest from OCR | OCR CPU (Tika) |
| Presidio sidecar | Horizontal (co-scaled with enclave); stateless | NER model inference (CPU) |
| Dispatcher | Cloud Run autoscale 0→10; Pub/Sub push provides backpressure naturally | Fan-out latency |
| AI Workers | Cloud Run autoscale 0→10 per queue; Cloud Tasks queue depth as signal | Vertex AI quota |
| BigQuery | Serverless; scales automatically | Concurrent streaming inserts |
| Vector Search | Streaming update index; read replicas for query | ANN query latency |

### 9.2 Backpressure & Flow Control

```
Document rate → Enclave queue (ThreadPoolTaskExecutor, capacity 100)
                    ↓
             GCS / Pub/Sub (durable; unlimited)
                    ↓
          Cloud Tasks queues (max concurrent dispatch per queue: configurable)
                    ↓
           Worker concurrency controlled by Cloud Run max-instances
```

If the enclave queue fills, the ingest API returns 503. Upstream clients back off.
Pub/Sub and Cloud Tasks provide durable buffering; no documents are lost on transient
worker unavailability.

### 9.3 Latency Budget (p95)

| Stage | Budget |
|---|---|
| OCR (5-page PDF) | 5 s |
| Presidio de-id | 3 s |
| GCS upload + Pub/Sub publish | 2 s |
| Dispatcher fan-out | 2 s |
| Gemini classification | 8 s |
| Gemini extraction | 10 s |
| Gemini summarisation | 12 s |
| Vertex AI embedding | 3 s |
| BigQuery write | 2 s |
| **Total (classification is critical path)** | **~47 s** |

Workers run in parallel; the critical path is the slowest worker (summariser ~30 s).
Total p95 target: ≤ 90 s with margin.

### 9.4 Caching

| Layer | Cache | TTL |
|---|---|---|
| Enclave | Tika MIME detector cache (in-process) | Process lifetime |
| Presidio | spaCy NLP pipeline (in-process, loaded once) | Process lifetime |
| Workers | GCS object metadata (not cached; objects are immutable per job) | N/A |
| Vector Search | ANN index loaded into memory on deployed endpoint | Persistent |

---

## 10. Reliability & Fault Tolerance

### 10.1 Failure Modes & Mitigations

| Component | Failure | Mitigation |
|---|---|---|
| Enclave crashes mid-job | Job stuck in PROCESSING state | Watchdog: Spring Batch job marks stale jobs ERROR after 10 min |
| Presidio OOM | DeidClient timeout | Presidio has dedicated memory limit; restart policy: always |
| GCS transient error | Upload fails | Spring Retry: 3 attempts, exponential backoff |
| Pub/Sub publish fails | Notification lost | ArtifactPackage already in GCS; manual re-trigger script re-publishes |
| Dispatcher crash | Tasks not created | Pub/Sub retries push until 200 received; idempotency key = job_id |
| Worker crash mid-task | Partial BigQuery write | BigQuery streaming inserts are atomic per row; Cloud Tasks retries; workers check for existing row before writing |
| Vertex AI quota exceeded | 429 from Gemini | Worker returns 429 → Cloud Tasks exponential retry |
| Vector Search unavailable | Upsert fails | Embedder retries 3×; falls back to BigQuery-only mode; re-upsert scheduled |

### 10.2 Idempotency

All operations are idempotent by design:

- **GCS upload:** Object name = `job_id.json` — overwriting is safe (same content)
- **Pub/Sub publish:** If duplicate notification delivered, dispatcher creates duplicate Cloud Tasks; workers use `IF NOT EXISTS` upsert semantics in BigQuery
- **BigQuery writes:** `MERGE` or `INSERT IF NOT EXISTS` using `job_id` as natural key
- **Vector Search upsert:** `UpsertDatapoints` is idempotent for same datapoint ID

### 10.3 High Availability

- **Enclave:** Active-active (2 replicas minimum); H2 replaced by Postgres with read replica for prod
- **Cloud Run:** Multi-instance; GCP manages regional availability
- **GCS:** Multi-region bucket optional for cross-region DR
- **BigQuery:** Fully managed; regional or multi-regional dataset
- **Pub/Sub:** 99.95% SLA; geo-redundant

### 10.4 Disaster Recovery

| RTO | RPO |
|---|---|
| 4 hours (enclave rebuild from image) | 0 (GCS + BigQuery are the source of truth) |

Re-processing: any job that failed after GCS upload can be re-dispatched without re-running OCR/de-id.

---## 11. Monitoring & Observability

### 11.1 Metrics

| Signal | Tool | Alert |
|---|---|---|
| Ingest API error rate (5xx) | Cloud Monitoring + Spring Actuator | > 1% over 5 min |
| Job queue depth (PROCESSING > 10 min) | Cloud Monitoring custom metric | > 50 stuck jobs |
| OCR latency p95 | Micrometer → Cloud Monitoring | > 15 s |
| Presidio latency p95 | Micrometer → Cloud Monitoring | > 10 s |
| Dispatcher invocation errors | Cloud Run metrics | > 5 errors/min |
| Worker error rate | Cloud Run metrics per service | > 5% over 10 min |
| Pub/Sub oldest undelivered message age | Cloud Monitoring built-in | > 5 min |
| Cloud Tasks queue depth | Cloud Tasks metrics | > 500 tasks |
| BigQuery streaming insert errors | Cloud Monitoring | Any error |
| Vector Search upsert latency | Custom metric from embedder | > 5 s |

### 11.2 Logging

All services emit structured JSON logs (Logstash encoder on JVM; `slog` JSON handler on Go):

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "INFO",
  "service": "enclave",
  "job_id": "550e8400-...",
  "source_id": "ehr-001",
  "event": "deidentification_complete",
  "phi_entity_count": 7,
  "duration_ms": 2341
}
```

**PHI-safe logging rule:** entity type counts only; never log entity values, redacted spans, or raw text.

### 11.3 Distributed Tracing

- `job_id` is the correlation ID propagated across all services via HTTP headers (`X-Job-Id`)
- Cloud Trace captures spans for Gemini calls, BigQuery writes, GCS operations
- Trace sampling: 10% steady state, 100% on error

### 11.4 Dashboards

| Dashboard | Audience | Key panels |
|---|---|---|
| Enclave Operations | On-prem ops team | Ingest rate, OCR latency, queue depth, error rate |
| Cloud Pipeline | Cloud ops team | Dispatcher invocations, worker latency by type, BQ insert rate |
| PHI Audit | Compliance | Daily phi_entity_count by source_id, anomaly detection |
| Cost | FinOps | Vertex AI API calls, BigQuery bytes processed, GCS storage |

### 11.5 Alerting Runbooks

Critical alerts page on-call via PagerDuty:
- Pub/Sub undelivered age > 5 min → check Dispatcher Cloud Run health
- Stuck jobs > 50 → check Presidio sidecar memory, enclave thread pool
- Worker error rate > 5% → check Vertex AI quota, review Cloud Tasks DLQ

---

## 12. Deployment Architecture

### 12.1 Environments

| Environment | Enclave | Cloud resources | Purpose |
|---|---|---|---|
| `local` | Docker Compose (H2, mock Presidio) | Emulators (Pub/Sub, GCS) | Developer inner loop |
| `dev` | Single enclave pod, 2 replicas Presidio | Full GCP, `dev` suffix buckets/topics | Integration testing |
| `staging` | Production topology, smaller quotas | Full GCP, `staging` suffix | Load testing, release validation |
| `prod` | HA enclave (2+ pods), Postgres | Full GCP, multi-region GCS | Live traffic |

### 12.2 Container Images

| Image | Base | Size (approx) |
|---|---|---|
| `docubrain-enclave` | `eclipse-temurin:21-jre` | ~350 MB |
| `docubrain-presidio` | `python:3.12-slim` + spaCy model | ~900 MB |
| `docubrain-dispatcher` | `gcr.io/distroless/static` | ~15 MB |
| `docubrain-{worker}` × 4 | `gcr.io/distroless/static` | ~15 MB each |

Go workers use distroless for minimal attack surface and fast pull times.

### 12.3 Infrastructure as Code

All GCP resources managed by Terraform (modularised):

```
cloud/terraform/
├── main.tf           — providers, module composition
├── variables.tf      — project_id, region, environment
├── modules/
│   ├── storage/      — GCS buckets + lifecycle rules
│   ├── pubsub/       — topic + push subscription
│   ├── tasks/        — 4 Cloud Tasks queues + DLQ
│   ├── cloudrun/     — Dispatcher + 4 worker services
│   ├── bigquery/     — dataset + 5 tables
│   └── vertexai/     — Vector Search index + deployed endpoint
```

State stored in GCS backend (`docubrain-tfstate` bucket); state locking via GCS object versioning.

### 12.4 CI/CD

```
PR opened
  ├── enclave-ci.yml  — mvn verify, pytest, Docker build
  └── cloud-ci.yml    — go test, terraform validate, Docker builds

Merge to main
  ├── Build + push all images to Artifact Registry
  ├── terraform apply (dev auto-deploy)
  └── Create GitHub release → triggers staging deploy (manual approval for prod)
```

---## 13. Trade-offs & Architecture Decisions

### ADR-001: De-identification on-prem, not Cloud Healthcare API

| | Option A: Cloud Healthcare API | Option B: Presidio on-prem (chosen) |
|---|---|---|
| PHI residency | PHI leaves enterprise network | PHI never leaves enclave |
| Operational cost | Zero model maintenance | Presidio + spaCy model (~900 MB) |
| Accuracy | Google's NLP (high) | spaCy en_core_web_lg (high for EN) |
| Multi-language | Built-in | Requires additional spaCy models |
| **Decision** | | **Option B** — PHI residency is non-negotiable |

### ADR-002: Presidio as FastAPI sidecar, not subprocess or Java reimplementation

| | Subprocess | Java NER | Sidecar (chosen) |
|---|---|---|---|
| PHI residency | Same process | Same process | Same pod/network |
| Testability | Hard | Moderate | Easy (HTTP mock) |
| NER quality | Same as sidecar | Lower (no spaCy) | Best |
| Operational complexity | Low | Low | Moderate |
| **Decision** | | | **Sidecar** |

### ADR-003: Go for cloud workers, Java for enclave

| Concern | Java | Go |
|---|---|---|
| Cold start | 3–5 s (unacceptable for scale-to-zero) | ~200 ms |
| Tika OCR ecosystem | Native | Requires JNI or subprocess |
| GCP SDK | First-class | First-class |
| Memory footprint | 256 MB minimum | 20–50 MB |
| **Decision** | Enclave only | All Cloud Run workers |

### ADR-004: Cloud Tasks over direct Pub/Sub fan-out

| Concern | Multiple Pub/Sub subscriptions | Cloud Tasks (chosen) |
|---|---|---|
| Per-worker retry control | Shared subscription config | Per-queue config |
| Deduplication | Subscriber-side | Built-in task dedup |
| Dispatch deadline | Fixed 600 s | Configurable per task |
| Visibility timeout | Ack deadline only | Full dispatch deadline |
| **Decision** | | **Cloud Tasks** — fine-grained retry per worker type |

### ADR-005: Gemini 1.5 Flash over GPT-4 / Claude

- GCP-native: no cross-cloud egress; billing consolidated; IAM-native auth
- Flash: 5× cheaper than Pro; latency fits 90 s budget
- Accepted trade-off: Flash occasionally less consistent on structured JSON; mitigated by strict `response_mime_type` and retry

---

## 14. Cost Estimation

### 14.1 Monthly Estimates (500 docs/hour, 720 hours/month = 360 000 docs)

| Resource | Unit cost | Usage | Monthly cost |
|---|---|---|---|
| GCS Standard (13 GB/month new data) | $0.020/GB | 13 GB | $0.26 |
| GCS Coldline (historical raw docs) | $0.007/GB | 1 000 GB | $7.00 |
| Pub/Sub | $0.04/M msg | 360 K msgs | $0.01 |
| Cloud Tasks | $0.40/M tasks | 1.44 M tasks | $0.58 |
| Cloud Run (Dispatcher) | ~$0.00002400/vCPU-s | ~50 h active | $4.32 |
| Cloud Run (Workers × 4) | ~$0.00002400/vCPU-s | ~400 h combined | $34.56 |
| Gemini 1.5 Flash (input) | $0.075/M tokens | ~180 M tokens | $13.50 |
| Gemini 1.5 Flash (output) | $0.30/M tokens | ~18 M tokens | $5.40 |
| text-embedding-004 | $0.00002/1K chars | ~36 M chars | $0.72 |
| BigQuery storage | $0.02/GB | 22 GB | $0.44 |
| BigQuery queries | $5.00/TB | ~100 GB | $0.50 |
| Vertex AI Vector Search | $0.12/node-hour | 2 nodes × 720 h | $172.80 |
| **Estimated total** | | | **~$240/month** |

> Vector Search deployed endpoint dominates cost. At low query volume, consider
> on-demand index queries or ScaNN on Cloud Run instead.

### 14.2 Cost Optimisation Levers

1. **Committed Use Discounts:** 1-year CUD on Cloud Run CPU saves ~17%
2. **GCS lifecycle:** Raw docs → Nearline after 90 days, Coldline after 1 year
3. **BigQuery slot reservations:** If query volume > 200 TB/month, reservations cheaper than on-demand
4. **Batch Gemini calls:** Use Batch Prediction API (50% discount) for non-latency-sensitive reprocessing
5. **Vector Search scale-down:** Scale to 1 node off-hours if query SLA allows

---

## 15. Open Questions & Future Work

### 15.1 Open Questions

| # | Question | Impact |
|---|---|---|
| Q1 | Multi-language PHI support needed? | Would require additional spaCy language models in Presidio |
| Q2 | Right-to-erasure (GDPR Article 17) requirements? | Need BigQuery `MERGE`-based deletion + Vector Search `removeDatapoints` workflow |
| Q3 | Should the enclave support streaming upload (chunked multipart)? | Affects Tika pipeline; needed for > 50 MB documents |
| Q4 | Cross-tenant search isolation? | Vector Search index per tenant vs. metadata filter |
| Q5 | SLA for semantic search queries? | Drives Vector Search replica count |
| Q6 | Fine-tuning Gemini on customer document corpus? | Vertex AI fine-tuning pipeline; significant data governance implications |

### 15.2 Known Limitations

| Limitation | Mitigation |
|---|---|
| OCR quality degrades on scanned documents with poor scan quality | Pre-processing: deskew + binarize with OpenCV before Tika |
| Gemini Flash structured JSON output occasionally malformed | Retry with temperature=0; fallback to regex extraction |
| Presidio recall for abbreviations (e.g., "Pt." for "Patient") | Custom recognizer patterns; regular red-team testing of redaction |
| Vector Search cold index load (~30 s on restart) | Minimum 1 replica always-on; pre-warm on deploy |
| BigQuery streaming insert latency (typically < 5 s but up to 90 s) | Workers use streaming; downstream queries use `_PARTITIONTIME` with buffer |

### 15.3 Potential Future Enhancements

1. **Streaming ingest** via Kafka Connect for high-volume EHR systems
2. **Feedback loop:** Clinician corrections fed back to improve entity extraction accuracy
3. **Multi-modal support:** Native PDF table extraction (replace Tika with `pdfplumber`)
4. **Differential privacy:** Add ε-DP noise to aggregate statistics exported from enclave
5. **Custom NER models:** Fine-tuned spaCy models on medical terminology per specialty
6. **Self-hosted LLM:** Replace Gemini with an on-prem Llama-3 / Mistral for zero-cloud-egress deployments

---

*Document version: 1.0 | Author: DocuBrain Engineering | Last updated: 2026-06-25*