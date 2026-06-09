# 02 — System Architecture

## Architectural Thesis

We split the system along a **data-classification boundary**, not a network boundary:

- **Enterprise side** = anything that touches raw documents or PHI.
- **Cloud side** = anything that touches derived artifacts and workflow metadata.

The boundary is enforced by:

1. The enterprise side **never** exposes a service that returns raw documents to the cloud.
2. The cloud side **only** has APIs that accept `DocumentId` + `Artifact` payloads — never files.
3. The artifact format is a **schema-validated, redaction-stamped** JSON envelope.
4. A separate **sync** service pushes artifacts one-way (enterprise → cloud) over mTLS.

This is a one-way data flow, not a request/response API. That property is the whole point.

---

## Logical View

```
┌──────────────────────────────────────┐         ┌──────────────────────────────────────┐
│           ENTERPRISE BOUNDARY        │         │              CLOUD SIDE              │
│                                      │         │                                      │
│  ┌────────────┐                      │         │   ┌──────────────────────────────┐   │
│  │  Legacy    │                      │         │   │   Ingestion API (Cloud Run)  │   │
│  │  Repo / FS │                      │         │   │   POST /artifacts            │   │
│  └─────┬──────┘                      │         │   └──────────────┬───────────────┘   │
│        │                             │         │                  │                   │
│        ▼                             │         │                  ▼                   │
│  ┌────────────┐   ┌──────────────┐    │         │   ┌──────────────────────────────┐   │
│  │ Ingest     │──▶│ OCR Worker   │    │         │   │  Workflow Orchestrator       │   │
│  │ Service    │   │ (Tesseract)  │    │         │   │  (Spring Boot + State Mach.) │   │
│  └────────────┘   └──────┬───────┘    │         │   └──────────────┬───────────────┘   │
│                         │            │         │                  │                   │
│                         ▼            │         │                  ▼                   │
│                  ┌──────────────┐    │         │   ┌──────────────────────────────┐   │
│                  │ Redaction    │    │         │   │     Pub/Sub Topics           │   │
│                  │ (Presidio)   │    │         │   │  - classify                  │   │
│                  └──────┬───────┘    │         │   │  - extract-entities          │   │
│                         │            │         │   │  - summarize                 │   │
│                         ▼            │         │   │  - index-search              │   │
│                  ┌──────────────┐    │         │   └──────────────┬───────────────┘   │
│                  │ Metadata     │    │         │                  │                   │
│                  │ Extractor    │    │         │                  ▼                   │
│                  └──────┬───────┘    │         │   ┌──────────────────────────────┐   │
│                         │            │         │   │  Worker Services (Cloud Run) │   │
│                         ▼            │         │   │  - Classifier                │   │
│                  ┌──────────────┐    │  mTLS   │   │  - Entity Extractor          │   │
│                  │ Artifact     │─────────────┼─▶ │  - Summarizer (Ollama LLM)   │   │
│                  │ Packager     │    │  GCS    │   │  - Indexer                   │   │
│                  └──────┬───────┘    │         │   └──────────────┬───────────────┘   │
│                         │            │         │                  │                   │
│                         ▼            │         │                  ▼                   │
│                  ┌──────────────┐    │         │   ┌──────────────────────────────┐   │
│                  │ Local Store  │    │         │   │  Search Index (Postgres FTS, │   │
│                  │ (encrypted)  │    │         │   │  later: Vertex AI Search)     │   │
│                  └──────────────┘    │         │   └──────────────────────────────┘   │
│                                      │         │                                      │
└──────────────────────────────────────┘         └──────────────────────────────────────┘
```

---

## Key Design Decisions (and the alternatives we rejected)

### 1. One-way artifact sync over request/response APIs
- **Chosen:** Enterprise pushes a signed artifact envelope to a cloud ingestion endpoint.
- **Rejected:** Cloud "calls back" to enterprise for processing. (Leaks request patterns;
  requires inbound connectivity from cloud to enterprise; defeats air-gap story.)

### 2. Synchronous enterprise processing, asynchronous cloud processing
- Enterprise side is **request/response** (a clinician/researcher is waiting for a job ID).
- Cloud side is **fully async** via Pub/Sub. The orchestrator owns workflow state.
- **Rejected:** Synchronous cloud APIs — kills elasticity, creates tight coupling.
- **Rejected:** Async enterprise side — adds complexity where it isn't needed (single
  enterprise, predictable load).

### 3. Local LLM (Ollama) in MVP, hosted LLM (Vertex AI) in production
- Same Spring Boot worker code; only the `LlmClient` bean changes.
- **Rejected:** Mandate a hosted LLM from day one. (Couples dev loop to API keys/quota;
  raises cost; doesn't change architecture.)

### 4. PostgreSQL as the state store AND the search index (MVP)
- One DB, `tsvector` for full-text, a JSONB column for entities. Plenty for the demo.
- **Rejected:** Elasticsearch/OpenSearch from day one. (Operational overhead with no payoff
  at MVP scale; can be added behind the same `SearchIndex` interface later.)

### 5. Workflow orchestration = small state machine in Postgres + Pub/Sub fan-out
- A `workflow_step` table tracks `pending|running|done|failed` for each (document, step).
- Pub/Sub delivers fan-out; workers update state on completion.
- **Rejected:** Temporal / Cadence. (Heavy, JVM-friendly but a new operational dependency;
  overkill for ≤ 5 step types. We can revisit later via the same `WorkflowStep` interface.)

### 6. Redaction uses Presidio locally
- Microsoft Presidio runs as a Python sidecar invoked by the Spring Boot redaction service.
- We wrap it behind a `Redactor` interface so we can swap to a different engine.
- **Rejected:** Cloud-based DLP API. (Sends text to a third party — exactly what we're
  trying to avoid. For the demo we *can* document the DLP-API-as-additional-layer option
  for low-sensitivity artifacts.)

### 7. OCR runs on the enterprise side
- Tesseract via a local CLI / sidecar. Files never leave the host pre-OCR.
- **Rejected:** Cloud Vision API. (Would require sending the raw image — PHI leak.)

---

## Failure & Trust Boundaries

| Boundary | What's at risk | Mitigation |
|---|---|---|
| Enterprise → Cloud network | Eavesdropping, replay | mTLS, signed artifact envelopes, short-lived tokens |
| Enterprise OCR/Redaction process | Bug ships raw text into artifact | Schema-validated envelope + redaction test fixtures in CI |
| Cloud worker | LLM hallucinates PHI that was actually redacted | Redaction is upstream; cloud workers only see `[REDACTED:NAME]` tokens; log redaction stats |
| Cloud → Enterprise (none) | N/A | There is no such path. By design. |
| LLM provider | None in MVP (local); later risk if Vertex AI is used | Local-only in MVP; document opt-in policy before any hosted LLM |

---

## Data Classification Model

| Class | Examples | Where it may exist |
|---|---|---|
| **Raw** | Original PDF, scanned image, full OCR text with PHI | Enterprise only |
| **Redacted** | OCR text with `[REDACTED:TYPE]` tokens | Enterprise + Cloud (artifact body) |
| **Derived** | Classification label, entity list, summary, embeddings | Cloud only |
| **Workflow** | `document_id`, timestamps, step statuses, trace IDs | Cloud only |

The artifact envelope the cloud receives contains **Redacted + Derived** content, never Raw.
