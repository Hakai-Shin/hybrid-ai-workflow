# 03 — Component Diagrams

All diagrams are ASCII so they survive in `git diff` and render in any markdown viewer.

---

## C1 — Container View (5 components)

```
                         ┌───────────────────────────────────────────┐
                         │            ENTERPRISE (on-prem)           │
                         │                                           │
   Legacy / FS / S3 ────▶│  ┌──────────┐    ┌──────────────────┐    │
                         │  │ Ingest   │───▶│ Processing Svc   │    │
   Clinician / Operator  │  │ API      │    │ (OCR + Redact +  │    │
   ───── REST ─────────▶│  │ (Spring) │    │  Meta Extract)   │    │
                         │  └──────────┘    └────────┬─────────┘    │
                         │                           │              │
                         │                           ▼              │
                         │                   ┌───────────────┐      │
                         │                   │ Artifact Pkg  │      │
                         │                   │ + Local Store │      │
                         │                   └───────┬───────┘      │
                         │                           │              │
                         └───────────────────────────┼──────────────┘
                                                     │ mTLS, one-way
                                                     ▼
                         ┌───────────────────────────────────────────┐
                         │                  CLOUD                    │
                         │                                           │
                         │  ┌──────────────┐                         │
                         │  │ Ingestion    │  Cloud Run              │
                         │  │ API          │  POST /artifacts        │
                         │  └──────┬───────┘                         │
                         │         │                                 │
                         │         ▼                                 │
                         │  ┌──────────────┐     ┌────────────────┐  │
                         │  │ Orchestrator │────▶│  Pub/Sub       │  │
                         │  │ (State Mach.)│     │  topics        │  │
                         │  └──────────────┘     └────────┬───────┘  │
                         │                                │          │
                         │                                ▼          │
                         │  ┌────────────────────────────────────┐  │
                         │  │     Worker Services (Cloud Run)    │  │
                         │  │  ┌─────────┐ ┌────────┐ ┌───────┐  │  │
                         │  │  │Classifier│ │Entity │ │Summarizer│  │
                         │  │  └─────────┘ └────────┘ └───────┘  │  │
                         │  │  ┌─────────┐                       │  │
                         │  │  │ Indexer  │                       │  │
                         │  │  └─────────┘                       │  │
                         │  └─────────────────┬──────────────────┘  │
                         │                    │                     │
                         │                    ▼                     │
                         │  ┌────────────────────────────────────┐  │
                         │  │  Postgres (state + FTS index)      │  │
                         │  └────────────────────────────────────┘  │
                         └───────────────────────────────────────────┘
```

---

## C2 — Component View (Enterprise Side)

```
                       ┌─────────────────────────────────────────────┐
                       │  enterprise-service  (Spring Boot, JVM)     │
                       │                                             │
   Watch folder ──────▶│  DocumentWatcher ──┐                        │
                       │                    │                        │
   POST /documents ───▶│  IngestController ─┤                        │
                       │                    ▼                        │
                       │             DocumentIntakeService            │
                       │                    │                        │
                       │                    ▼                        │
                       │             ProcessingPipeline               │
                       │       ┌────────────┼────────────┐           │
                       │       ▼            ▼            ▼           │
                       │  ┌─────────┐ ┌──────────┐ ┌──────────┐     │
                       │  │ OcrCli  │ │ Redactor │ │ Metadata │     │
                       │  │(Tess)   │ │(Presidio)│ │ Extractor│     │
                       │  └────┬────┘ └─────┬────┘ └─────┬────┘     │
                       │       └────────────┼────────────┘           │
                       │                    ▼                        │
                       │           ArtifactPackager                  │
                       │                    │                        │
                       │                    ▼                        │
                       │           CloudSyncClient (mTLS, GCS)       │
                       │                    │                        │
                       │                    ▼                        │
                       │           LocalEncryptedStore (AES-GCM)     │
                       └─────────────────────────────────────────────┘
```

**Interfaces (Java):**

```java
interface OcrEngine    { OcrResult ocr(StoredDocument doc); }
interface Redactor     { RedactionResult redact(OcrResult text); }
interface MetadataExtractor { Metadata extract(RedactionResult r); }
interface ArtifactPackager  { ArtifactEnvelope package(RedactionResult r, Metadata m); }
interface CloudSyncClient   { void push(ArtifactEnvelope env); }
```

Each is a Spring bean; swap for tests or alternative implementations.

---

## C3 — Component View (Cloud Side)

```
                       ┌─────────────────────────────────────────────┐
                       │  cloud-services  (multi-module Spring Boot) │
                       │                                             │
   mTLS POST ────────▶│  ArtifactIngestController                    │
                       │       │                                     │
                       │       ▼                                     │
                       │  ArtifactValidator (JSON schema)            │
                       │       │                                     │
                       │       ▼                                     │
                       │  WorkflowOrchestrator                        │
                       │       │ creates WorkflowStep rows            │
                       │       │ publishes step events                │
                       │       ▼                                     │
                       │  PubSubPublisher ──▶ topics:                 │
                       │       │           - classify                 │
                       │       │           - extract-entities         │
                       │       │           - summarize                │
                       │       │           - index-search             │
                       │       ▼                                     │
                       │  Worker Subscribers (one Spring Boot app,    │
                       │  multiple @PubSubListener methods):         │
                       │       │                                     │
                       │       ├── ClassifierWorker  (rules + small ML)│
                       │       ├── EntityWorker      (spaCy / Ollama) │
                       │       ├── SummarizerWorker  (Ollama llama3)  │
                       │       └── IndexerWorker     (Postgres FTS)   │
                       │                                             │
                       │  Query API  GET /search?q=...                │
                       │       │                                     │
                       │       ▼                                     │
                       │  Postgres (state + FTS index)               │
                       └─────────────────────────────────────────────┘
```

---

## C4 — Sequence: End-to-End Document Processing

```
Operator         Ingest API       Pipeline         Cloud Ingest   Orchestrator   PubSub       Workers         Index
   │  POST /docs   │                │                    │              │            │            │             │
   │──────────────▶│                │                    │              │            │            │             │
   │               │ enqueue job    │                    │              │            │            │             │
   │               │───────────────▶│                    │              │            │            │             │
   │               │                │ OCR                │              │            │            │             │
   │               │                │ Redact             │              │            │            │             │
   │               │                │ Metadata           │              │            │            │             │
   │               │                │ Pack               │              │            │            │             │
   │  202 Accepted │                │                    │              │            │            │             │
   │◀──────────────│                │                    │              │            │            │             │
   │  {doc_id}     │                │ mTLS push artifact │              │            │            │             │
   │               │                │───────────────────▶│              │            │            │             │
   │               │                │                    │ validate    │            │            │             │
   │               │                │                    │ create wf   │            │            │             │
   │               │                │                    │─────────────▶            │            │             │
   │               │                │                    │              │ publish[4]│             │             │
   │               │                │                    │              │──────────▶│             │             │
   │               │                │                    │              │            │ classify    │             │
   │               │                │                    │              │            │────────────▶│             │
   │               │                │                    │              │            │ entities    │             │
   │               │                │                    │              │            │────────────▶│             │
   │               │                │                    │              │            │ summarize   │             │
   │               │                │                    │              │            │────────────▶│             │
   │               │                │                    │              │            │ index       │             │
   │               │                │                    │              │            │──────────────────────────▶│
   │               │                │                    │              │  step=done │             │             │
   │               │                │                    │              │◀──────────│             │             │
   │  GET /docs/{id}/status (poll)  │                    │              │            │             │             │
   │─────────────────────────────────────────────────── │ ─ ─ ─ ─ ─ ─▶│            │             │             │
   │  200 {status: "ENRICHED", summary, entities, label} │              │            │             │             │
   │◀────────────────────────────────────────────────── │ ─ ─ ─ ─ ─ ─ │            │             │             │
```

---

## C5 — Deployment View (logical)

```
LOCAL MACHINE (docker compose)                GCP (real or emulated)
─────────────────────────────                 ─────────────────────
                                               
network: enterprise (no egress to "cloud")    network: cloud
┌─────────────────────────────┐               ┌─────────────────────────────┐
│ enterprise-service          │               │ artifact-ingest  (Cloud Run)│
│ tesseract sidecar           │  ─ mTLS ────▶ │ orchestrator      (Cloud Run)│
│ presidio-analyzer sidecar   │   artifact    │ worker-classify  (Cloud Run)│
│ postgres-enterprise         │   push only   │ worker-entities  (Cloud Run)│
└─────────────────────────────┘               │ worker-summarize (Cloud Run)│
                                                │ worker-index     (Cloud Run)│
                                                │ ollama           (Compute)  │
                                                │ postgres-cloud + GCS bucket │
                                                │ pubsub (emulator or real)   │
                                                └─────────────────────────────┘
```

For the MVP we run both networks in one `docker compose` file with **isolated networks**
(`enterprise_net` and `cloud_net`) and a one-way bridge that only allows the artifact push.
This is the demo's "data classification boundary."
