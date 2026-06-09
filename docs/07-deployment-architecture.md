# 07 — Deployment Architecture

## Two Deployment Targets, One Source Tree

We use a **monorepo** (multi-module Maven) so the source of truth is one place; the
deployment artifacts are two distinct images. CI builds:

- `enterprise-service:<sha>` — pushed to an **internal/artifact registry** the cloud
  cannot reach.
- `cloud/artifact-ingest:<sha>`, `cloud/orchestrator:<sha>`, `cloud/worker-classify:<sha>`,
  `cloud/worker-entities:<sha>`, `cloud/worker-summarize:<sha>`, `cloud/worker-index:<sha>`,
  `cloud/query-api:<sha>` — pushed to **Artifact Registry** in the cloud project.

A single Spring Boot *image* is shared by all cloud services; the *role* is selected at
boot via `SERVICE_ROLE` env var. This keeps the build simple and the runtime surface
small. (Six images of the same JAR is not worth it.)

```
SERVICE_ROLE=orchestrator   -> boots orchestrator beans
SERVICE_ROLE=worker-classify -> boots only the classifier subscriber
SERVICE_ROLE=worker-summarize -> boots only the summarizer subscriber
...
```

---

## Local Dev — `docker compose`

The most important deliverable for a portfolio reviewer. One `docker compose up` brings
up the entire system on a laptop. We simulate the data-classification boundary with two
isolated Docker networks and a one-way bridge.

```yaml
# compose.yaml (illustrative)
networks:
  enterprise_net:  # the "on-prem" world
  cloud_net:       # the "cloud" world
  boundary_bridge: # only artifact push is allowed across

volumes:
  enterprise_pg:
  cloud_pg:
  ollama_models:

services:
  # --- ENTERPRISE ---
  tesseract:
    image: tesseractshadow/tesseract4re:latest
    networks: [enterprise_net]
  presidio:
    image: mcr.microsoft.com/presidio-analyzer:latest
    networks: [enterprise_net]
  enterprise-service:
    build: ./enterprise-service
    networks: [enterprise_net, boundary_bridge]
    environment:
      REDACTOR_URL: http://presidio:5000
      OCR_CLI:     tesseract
      CLOUD_INGEST_URL: https://artifact-ingest:8080  # resolves via boundary_bridge
      CLOUD_TLS_CA_FILE: /certs/cloud-ca.pem
    depends_on: [tesseract, presidio, enterprise-db]
  enterprise-db:
    image: postgres:16
    networks: [enterprise_net]
    volumes: [enterprise_pg:/var/lib/postgresql/data]

  # --- BOUNDARY ---
  # A tiny forward-proxy that ONLY forwards POST /v1/artifacts
  # and strips all other headers. Demonstrates the one-way principle.
  artifact-forwarder:
    build: ./boundary/forwarder
    networks: [boundary_bridge, cloud_net]
    environment:
      ALLOW_PATH: /v1/artifacts
      ALLOW_METHOD: POST

  # --- CLOUD ---
  artifact-ingest:
    build: ./cloud-services
    command: ["--server.port=8080"]
    environment: { SERVICE_ROLE: ingest }
    networks: [cloud_net]
  orchestrator:
    command: ["--server.port=8080"]
    environment: { SERVICE_ROLE: orchestrator }
    networks: [cloud_net]
  worker-classify:
    environment: { SERVICE_ROLE: worker-classify, OLLAMA_URL: http://ollama:11434 }
    networks: [cloud_net]
  worker-entities:
    environment: { SERVICE_ROLE: worker-entities }
    networks: [cloud_net]
  worker-summarize:
    environment: { SERVICE_ROLE: worker-summarize }
    networks: [cloud_net]
  worker-index:
    environment: { SERVICE_ROLE: worker-index }
    networks: [cloud_net]
  query-api:
    environment: { SERVICE_ROLE: query }
    networks: [cloud_net]
  pubsub-emulator:
    image: gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators
    command: ["gcloud", "beta", "emulators", "pubsub", "start", "--host-port=0.0.0.0:8085"]
    networks: [cloud_net]
  ollama:
    image: ollama/ollama:latest
    networks: [cloud_net]
    volumes: [ollama_models:/root/.ollama]
    # Pre-pull llama3:8b on first boot via an init container.
  cloud-storage-emulator:
    image: fsouza/fake-gcs-server
    networks: [cloud_net]
  cloud-db:
    image: postgres:16
    networks: [cloud_net]
    volumes: [cloud_pg:/var/lib/postgresql/data]

  # --- OBSERVABILITY ---
  prometheus:
    image: prom/prometheus
    networks: [cloud_net]
  grafana:
    image: grafana/grafana
    networks: [cloud_net]
```

Key properties:

- `enterprise-service` has an interface on **both** `enterprise_net` and `boundary_bridge`.
  Nothing else is on `boundary_bridge`. The forwarder is the only thing on `cloud_net`
  reachable from the enterprise side.
- The forwarder enforces `path == /v1/artifacts` and `method == POST` at the L4/L7 level.
  This is the "no API for getting raw documents" rule, enforced by the network.
- The Pub/Sub emulator + fake-gcs-server keep the demo free of GCP credentials.
- Ollama runs locally; the worker-summarize service talks to it over HTTP.

---

## Production on GCP (post-MVP)

```mermaid (described as text)
                          Internet
                              │
                              ▼
                   ┌──────────────────────┐
                   │  Cloud Armor + LB    │
                   └──────────┬───────────┘
                              │
                              ▼
                   ┌──────────────────────┐
                   │  artifact-ingest     │  Cloud Run (private, mTLS)
                   │  (Cloud Run)         │
                   └──────────┬───────────┘
                              │
                              ▼
                   ┌──────────────────────┐
                   │   Pub/Sub topics     │
                   └──────────┬───────────┘
                              │
       ┌──────────────┬───────┴───────┬──────────────┐
       ▼              ▼               ▼              ▼
  worker-classify  worker-entities  worker-summarize  worker-index
   (Cloud Run)     (Cloud Run)      (Cloud Run +      (Cloud Run)
                                     Ollama on GCE
                                     or Vertex AI)
       └──────────────┴───────┬───────┴──────────────┘
                              ▼
                   ┌──────────────────────┐
                   │  Cloud SQL (PG)      │  private IP, CMEK
                   └──────────────────────┘

                   ┌──────────────────────┐
                   │  Cloud Storage       │  uniform bucket-level
                   │  (artifacts, dlq)    │  access, CMEK, VPC-SC
                   └──────────────────────┘

                   ┌──────────────────────┐
                   │  Cloud Logging/Trace │  OTLP → Cloud Trace
                   └──────────────────────┘
```

### Production-only concerns (not in MVP)

- **VPC Service Controls** around the cloud project so data exfil is constrained.
- **CMEK** on Cloud Storage, Pub/Sub, Cloud SQL.
- **Workload Identity** for Cloud Run → GCS / Pub/Sub / Cloud SQL.
- **Secret Manager** for the mTLS certs the enterprise side presents.
- **Cloud Armor** WAF in front of `artifact-ingest` (only mTLS + IP allow-list anyway).
- **Org Policy**: restrict service account key creation; enforce uniform bucket-level
  access.
- **Binary Authorization** on Cloud Run.
- **SCC / Security Command Center** findings dashboard.

### Enterprise-side production deployment

- Run on a Kubernetes cluster (or VMs) inside the customer's VPC.
- Image pulled from the customer's internal Harbor/Artifact Registry (mirror of our GHCR).
- KMS provider is the customer's (HashiCorp Vault, AWS KMS, or Azure Key Vault via CSI).
- Ingress is internal-only; egress is restricted to the cloud `artifact-ingest` URL +
  the cert revocation list endpoint.
- File watcher runs as a separate `DaemonSet` per node holding the legacy share.
- No public IP on any enterprise workload.

---

## CI/CD (GitHub Actions)

Pipelines:

- `ci.yml` — on every PR: `mvn verify`, schema-validate fixtures, redaction test,
  integration test (Testcontainers).
- `build-enterprise.yml` — on merge to main: build & push enterprise image to GHCR.
- `build-cloud.yml` — on merge to main: build cloud image, sign with `cosign`,
  push to Artifact Registry, deploy to dev Cloud Run.
- `promote.yml` — manual: tag-based promotion to staging → prod.
- `release.yml` — tags trigger a GitHub release with the schema bundle.

Branch protection: `main` requires 1 review, green CI, and the schema-validate job.

---

## Environment Matrix

| Env | Enterprise DB | Cloud DB | Pub/Sub | GCS | LLM |
|---|---|---|---|---|---|
| Local dev | Docker pg | Docker pg | Emulator | fake-gcs-server | Ollama |
| Dev (GCP) | Cloud SQL (dev) | Cloud SQL (dev) | Real | Real (dev bucket) | Ollama (GCE) |
| Staging | Cloud SQL (stg) | Cloud SQL (stg) | Real | Real (stg bucket, CMEK) | Ollama (GCE) or Vertex |
| Prod | Customer-managed | Cloud SQL (prod, CMEK, PITR) | Real | Real (prod, CMEK, retention) | Vertex AI (after DPIA) |

---

## Cost Envelope (post-MVP target)

- Cloud SQL: db-f1-micro for dev, db-n1-standard-2 for prod.
- Cloud Run: min instances 0, max 10 per worker; ~$30/mo at low traffic.
- Pub/Sub: first 10 GB/mo free; negligible.
- GCS: Standard, lifecycle to Nearline after 30 days.
- LLM: Ollama on a single `n1-standard-4` with a quantized 8B model ≈ $100/mo.
- Total target: **< $200/mo for a meaningful workload** (10k docs/mo).
