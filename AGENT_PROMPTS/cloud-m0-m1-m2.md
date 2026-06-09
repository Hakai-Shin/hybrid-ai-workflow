# Cloud Agent — M0, M1, M2 (Ready to paste)

> Paste this into the Copilot Cline extension or your Ollama agent interface.

## Context

You are **Agent Cloud** working on the **Hybrid AI Workflow** project.
You are working in the worktree at `C:\workspace\hybrid-ai-workflow\worktrees\cloud\`.
Your branch is `agent/cloud`.

**Important:** You own BOTH the cloud-services module AND the boundary/forwarder module,
the compose file, Makefile, and certs. The boundary is the cloud's front door.

## Your Six Tasks (complete in order)

- **Issue #4 — [cloud-M0]** Bootstrap cloud-services Spring Boot + boundary/forwarder + compose.yaml
- **Issue #5 — [cloud-M1]** Forwarder: enforce POST /v1/artifacts only, mTLS
- **Issue #6 — [cloud-M1]** POST /v1/artifacts ingest + validate
- **Issue #7 — [cloud-M2]** Orchestrator + first 2 workers
- **Issue #8 — [cloud-M2]** End-to-end forwarder harness
- **Issue #9 — [cloud-M2]** Worker indexer + query API

## Files you may edit

```
cloud-services/**
boundary/forwarder/**
compose.yaml
Makefile
certs/**
bin/**
```

## Files you may NOT edit

```
shared/contracts/**
AGENT_RULES.md
docs/**
enterprise-service/**
frontend/**
pom.xml (root)
```

## Contracts to import (read from `shared/contracts/`)

- `artifact.v1.json` — the artifact envelope shape (validate ingress)
- `pubsub-step-envelope.v1.json` — Pub/Sub step message format
- `workflow-step.v1.json` — step names and statuses
- `error.v1.json` — RFC 7807 problem+json
- `redaction-policy.v1.json` — policy identifier

Also read:
- `docs/04-api-design.md` (cloud-side API section B + Pub/Sub section C)
- `docs/05-cloud-workflows.md` (the 4-step enrichment workflow)
- `docs/06-data-models.md` (cloud DB schema section C)
- `docs/07-deployment-architecture.md` (local compose section)

## Definition of Done (all 6 issues)

1. ✅ Code compiles: `mvn -pl cloud-services -am verify` passes.
2. ✅ Unit tests pass.
3. ✅ M0: `make up` brings up 2 DBs + Pub/Sub emulator + fake-gcs + forwarder + cloud-services health.
4. ✅ M1: Forwarder accepts POST /v1/artifacts with mTLS, rejects all other paths.
5. ✅ M1: POST /v1/artifacts validates against artifact.v1.json schema; 400 on failure, 201 on success.
6. ✅ M2: Full workflow chain: ingest → 4 step rows → classify published → 4 workers do their thing.
7. ✅ M2: `GET /v1/search?q=...` returns enriched hits.
8. ✅ You touched ONLY files in your allow-list.
9. ✅ PR titles: `[cloud-M0]`, `[cloud-M1]`, `[cloud-M2]`.

## M0 Acceptance (Issue #4)

- `mvn -pl cloud-services -am verify` passes with 3 profiles: `ingest`, `orchestrator`, `worker-classify`.
- `SERVICE_ROLE=ingest` boots with `/v1/health` 200.
- `SERVICE_ROLE=worker-classify` boots with health endpoint only (no HTTP controllers).
- `SERVICE_ROLE=invalid` exits with error log.
- `boundary/forwarder/Dockerfile` exists (a simple HTTP forwarder, e.g. NGINX or tiny Go).
- `compose.yaml` defines:
  - Two networks: `enterprise_net` and `cloud_net`.
  - `enterprise-db` (postgres:16) on enterprise_net.
  - `cloud-db` (postgres:16) on cloud_net.
  - `pubsub-emulator` on cloud_net.
  - `cloud-storage-emulator` (fake-gcs-server) on cloud_net.
  - `cloud-services` on cloud_net.
  - `boundary/forwarder` on BOTH networks (the bridge).
- `Makefile` has `make up`, `make logs`, `make down`, `make certs`.

## M1 BOUNDARY Acceptance (Issue #5)

- `POST /v1/artifacts` with valid mTLS client cert → forwarded to cloud-services (expect 404 first, then 201 after Issue #6).
- `POST /v1/artifacts` without mTLS → 401.
- `GET /v1/any-path` → 403 (path not allowed).
- `POST /v1/documents` → 403 (path not allowed, even though it's POST).
- The `Host`, `Cookie`, `Authorization` headers are stripped before forwarding.
- `make certs` generates: CA cert, forwarder server cert, client cert (for enterprise to present).

## M1 CLOUD Acceptance (Issue #6)

- `POST /v1/artifacts` validates body against `artifact.v1.json` schema.
- 400 with problem+json on: missing required field, wrong `schema_version`, invalid `document_id` pattern.
- 401/403 on mTLS failure.
- 409 on duplicate `Idempotency-Key`.
- 201 Created with `{"workflow_id": "wf_01HZZ..."}` on success.
- `ArtifactValidator` is a Spring bean using a JSON Schema validator library.
- Integration test: `POST /v1/artifacts` with valid body → 201.

## M2 ORCHESTRATOR + WORKERS Acceptance (Issue #7, #8, #9)

- `POST /v1/artifacts` creates: 1 `workflows` row (status=ACCEPTED) + 4 `workflow_steps` rows (PENDING).
- One Pub/Sub message published to `doc.classify`.
- `ClassifierWorker` consumes `doc.classify`, calls LLM (Ollama at `ollama:11434`), writes DONE.
- Step completion publishes to `cloud.workflow.step.completed` fan-in topic.
- `WorkflowStepAdvancer` advances the chain: classify DONE → publish `doc.extract-entities`, etc.
- `ExtractEntitiesWorker` and `SummarizeWorker` follow the same pattern.
- `IndexWorker` writes `enriched_documents` row with `tsvector`.
- `Query API` exposes `GET /v1/search?q=...` over the GIN index.

## When stuck

1. Re-read this brief.
2. Re-read `docs/04-api-design.md` (cloud APIs + Pub/Sub).
3. Re-read `docs/05-cloud-workflows.md` (step definitions).
4. Re-read `docs/06-data-models.md` (cloud DB schema).
5. If still stuck after 30 min, comment on the issue tagging @manager.