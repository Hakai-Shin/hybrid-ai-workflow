# Hybrid AI Workflow for Enterprise Document Intelligence

A hybrid-cloud document processing system for legacy enterprise document repositories
that enables AI-driven document intelligence workflows while keeping sensitive data
(PHI/PII) inside the enterprise boundary.

> Only **derived artifacts, de-identified content, and workflow metadata** are
> transmitted to the cloud. Raw documents never leave the on-prem side. The cloud
> can perform OCR-grade AI over them, but only over their redacted, typed forms.

---

## Why this project exists

Enterprises have decades of legacy documents (clinical notes, insurance claims, scanned
forms) sitting in on-prem repositories. The people who could get value from those
documents want to use modern AI — classification, entity extraction, summarization,
search enrichment — but the documents cannot be uploaded to a cloud LLM provider
because of compliance, privacy, or operational constraints.

This system bridges that gap:

```
  on-prem                one-way mTLS push            cloud (GCP)
 ┌──────────┐  OCR  ┌────────────┐  artifact  ┌────────────────┐
 │ legacy   │──────▶│ redact +   │───────────▶│  AI workflow   │
 │ repo     │       │ package    │            │  (Pub/Sub +    │
 └──────────┘       └────────────┘            │   workers)     │
                                              └────────┬───────┘
                                                       ▼
                                              ┌────────────────┐
                                              │  search index  │
                                              └────────────────┘
```

The boundary is enforced by:
1. A network-isolated bridge that only allows `POST /v1/artifacts`.
2. A schema-validated artifact envelope that contains redacted text + metadata — never raw files.
3. No cloud-side API for retrieving raw documents. There is no `GET /documents/{id}/file` on the cloud, ever.

---

## Architecture at a glance

| | |
|---|---|
| **Enterprise side** | Spring Boot service, Tesseract OCR, Presidio redaction, local Postgres, encrypted blob store, mTLS push to cloud |
| **Cloud side** | Spring Boot multi-role service (ingest / orchestrator / workers / query), Cloud Pub/Sub, Cloud SQL (Postgres), Cloud Storage, Ollama (MVP) or Vertex AI (prod) |
| **Boundary** | A 50-line "forwarder" sidecar that only forwards `POST /v1/artifacts` and strips all other paths/headers |
| **Workflow** | A 4-step chain: `classify → extract-entities → summarize → index-search`, orchestrated by a small state machine in Postgres + a `step.completed` fan-in topic |
| **Observability** | Structured JSON logs with `trace_id` everywhere, Micrometer → Prometheus → Grafana |
| **Local demo** | One `docker compose up` simulates the boundary with two isolated networks; no GCP account required |

See `docs/03-component-diagrams.md` for the full picture.

---

## Tech stack

- **Java 21 + Spring Boot 3** — single monorepo, multi-module Maven; one image with role-based bean activation
- **PostgreSQL 16** — both sides, with Flyway migrations
- **Tesseract 5** — OCR (with PaddleOCR as a swap-in option)
- **Microsoft Presidio** — redaction, runs as a sidecar
- **Ollama + llama3:8b** — local LLM for the MVP; `LlmClient` interface for hosted swap
- **Google Cloud Platform** — Cloud Run, Pub/Sub, Cloud SQL, Cloud Storage, Cloud KMS, VPC-SC, Workload Identity
- **Docker / Docker Compose** — local demo with isolated networks
- **GitHub Actions** — CI/CD with image signing via `cosign`
- **Prometheus + Grafana** — observability
- **JSON Schema** — contract-first API design

---

## Resume bullets this project supports

> **Built a document processing platform for legacy enterprise repositories, enabling
> AI-driven document intelligence workflows while ensuring protected health information
> (PHI) remained within enterprise boundaries.**

> **Developed a service for document ingestion, OCR, de-identification, and metadata
> extraction within enterprise environments, transmitting only derived artifacts and
> workflow metadata to cloud-hosted services.**

> **Implemented cloud-orchestrated AI pipelines for document classification, entity
> extraction, summarization, and search enrichment on de-identified document artifacts
> using asynchronous task execution and horizontally scalable worker services.**

---

## Project layout

```
hybrid-ai-workflow/
├── docs/                          # ← you are here (design)
│   ├── 01-mvp-definition.md
│   ├── 02-system-architecture.md
│   ├── 03-component-diagrams.md
│   ├── 04-api-design.md
│   ├── 05-cloud-workflows.md
│   ├── 06-data-models.md
│   ├── 07-deployment-architecture.md
│   ├── 08-milestones.md
│   ├── 09-complexity-review.md
│   └── adr/                       # Architecture Decision Records (M7+)
│
├── shared/
│   └── contracts/                 # JSON schemas, Java DTOs — single source of truth
│
├── enterprise-service/            # Spring Boot app running on-prem
│   ├── src/main/java/...
│   └── src/test/...
│
├── cloud-services/                # Spring Boot app; SERVICE_ROLE picks the role
│   ├── ingest/                    #   - artifact-ingest
│   ├── orchestrator/              #   - workflow orchestrator + advancer
│   ├── workers/                   #   - classify, entities, summarize, index
│   └── query/                     #   - search API
│
├── boundary/
│   └── forwarder/                 # one-way HTTP forwarder (the "moat")
│
├── frontend/
│   └── admin/                     # minimal React + Vite admin page
│
├── infra/
│   ├── terraform/                 # GCP infra (M8+)
│   └── k8s-reference/             # reference manifests for customer-side k8s
│
├── compose.yaml                   # the demo
├── Makefile                       # make up / logs / down / test / demo
└── bin/
    └── demo.sh                    # one-shot demo script (M7+)
```

---

## Documentation index

Read the docs in this order:

1. **[MVP Definition](docs/01-mvp-definition.md)** — what "done" means, and what's out of scope.
2. **[System Architecture](docs/02-system-architecture.md)** — the architectural thesis, key decisions, and trade-offs.
3. **[Component Diagrams](docs/03-component-diagrams.md)** — container, component, sequence, and deployment views (ASCII).
4. **[API Design](docs/04-api-design.md)** — REST contracts on both sides, including Pub/Sub envelope and error model.
5. **[Cloud Workflows](docs/05-cloud-workflows.md)** — the 4-step enrichment workflow, step definitions, failure semantics.
6. **[Data Models](docs/06-data-models.md)** — Postgres schemas for both sides + GCS layout + cross-boundary ID rules.
7. **[Deployment Architecture](docs/07-deployment-architecture.md)** — local compose, GCP prod target, CI/CD, env matrix.
8. **[Milestones & Build Order](docs/08-milestones.md)** — week-by-week build plan with resume-bullet mapping.
9. **[Complexity Review](docs/09-complexity-review.md)** — what we're *deliberately not building*, and why.

---

## Status

**Design phase complete.** The `docs/` directory is the architectural source of truth.
Implementation begins at **M0** (see `docs/08-milestones.md`).

| Milestone | Status |
|---|---|
| M0 — Repo & local stack | ⬜ |
| M1 — Ingest + OCR | ⬜ |
| M2 — Redaction + artifact packaging | ⬜ |
| M3 — Cloud ingest + boundary | ⬜ |
| M4 — Classifier worker | ⬜ |
| M5 — Summarize + entity workers | ⬜ |
| M6 — Indexer + search | ⬜ |
| M7 — Observability & demo polish | ⬜ |
| M8 — Production slice (Terraform) | ⬜ |
| M9 — Vertex AI swap (optional) | ⬜ |

---

## Quick start (design review only)

```bash
# clone
git clone https://github.com/Hakai-Shin/hybrid-ai-workflow.git
cd hybrid-ai-workflow

# read the design in order
ls docs/
open docs/01-mvp-definition.md
```

When M0 lands, this section will include:

```bash
make up      # docker compose up the local stack
make demo    # run the end-to-end demo
make down    # tear it down
```

---

## License

TBD. MIT is the default unless a future employer has opinions.
