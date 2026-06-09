# Intake Agent — M0, M1, M2 (Ready to paste)

> Paste this into the Copilot Cline extension or your Ollama agent interface.

## Context

You are **Agent Intake** working on the **Hybrid AI Workflow** project.
You are working in the worktree at `C:\workspace\hybrid-ai-workflow\worktrees\intake\`.
Your branch is `agent/intake`.

## Your Three Tasks (complete in order)

### Issue #1 — [intake-M0] Bootstrap enterprise-service Spring Boot module
### Issue #2 — [intake-M1] DocumentWatcher + IngestController + Tesseract OcrEngine
### Issue #3 — [intake-M2] Redactor interface + RegexRedactor + PresidioRedactor + ArtifactPackager

## Files you may edit

```
enterprise-service/**
frontend/admin/.gitignore
```

## Files you may NOT edit

```
shared/contracts/**
AGENT_RULES.md
docs/**
cloud-services/**
boundary/**
compose.yaml
Makefile
certs/**
bin/**
pom.xml (root)
```

## Contracts to import (read from `shared/contracts/`)

- `artifact.v1.json` — the artifact envelope shape
- `error.v1.json` — RFC 7807 problem+json
- `redaction-policy.v1.json` — policy identifier + engine list

Also read:
- `docs/04-api-design.md` (enterprise-side API section A)
- `docs/06-data-models.md` (enterprise DB schema section E)

## Definition of Done (all 3 issues)

1. ✅ Code compiles: `mvn -pl enterprise-service -am verify` passes.
2. ✅ Unit tests pass.
3. ✅ M0: `GET /v1/health` returns 200.
4. ✅ M1: Drop `fixtures/sample.png` into `./intake/` → `GET /v1/documents/{id}` returns `status=REDACTED`.
5. ✅ M2: Same document shows `redaction_stats` with non-zero counts + `[REDACTED:NAME]` in the raw-text API.
6. ✅ M2: The redaction fixture test passes (a test file with fake SSN/name/MRN proves none of the originals appear in output).
7. ✅ You touched ONLY files under `enterprise-service/**`.
8. ✅ PR titles: `[intake-M0]`, `[intake-M1]`, `[intake-M2]`.

## M0 Acceptance (Issue #1)

- `mvn -pl enterprise-service -am verify` passes.
- Module `enterprise-service` has `pom.xml` depending on `spring-boot-starter-web`, `spring-boot-starter-actuator`, `postgresql`, `HikariCP`.
- `GET /v1/health` returns `{"status":"UP"}`.
- A `Dockerfile` exists that builds the JAR with Maven.

## M1 Acceptance (Issue #2)

- `DocumentWatcher` polls `./intake/` folder every 5 seconds.
- `IngestController` accepts `POST /v1/documents` (multipart: PDF/PNG/JPEG/TIFF).
- `OcrEngine` interface wraps Tesseract CLI (sidecar container at `tesseract:5000` or direct CLI).
- OCR text is stored in the `documents` table + `raw_blobs` (encrypted).
- Status flow: `QUEUED → PROCESSING → REDACTED → FAILED`.
- Integration test: a sample PNG → row in `documents` with non-null `status`.

## M2 Acceptance (Issue #3)

- `Redactor` interface with two implementations:
  - `RegexRedactor`: catches SSN, MRN, email, phone, US ZIP.
  - `PresidioRedactor`: calls Presidio analyzer sidecar at `presidio:5000`, catches PERSON, LOCATION, DATE_TIME.
- Configurable policy: `phi-strict-v1` (both) / `phi-minimal-v1` (regex only).
- `ArtifactPackager` produces a `document_artifacts` row with AES-GCM-encrypted `redacted_text_cipher`.
- Redaction stats per document are recorded.
- **Critical CI test:** Run redactor on a fixture with SSN `123-45-6789`, name `John Smith`, MRN `MRN-987654`. Assert none appear in output. Assert `stats.names==1, stats.ssn==1, stats.mrn==1`.

## When stuck

1. Re-read this brief.
2. Re-read `docs/04-api-design.md` (enterprise APIs).
3. Re-read `docs/06-data-models.md` (enterprise DB tables).
4. If still stuck after 30 min, comment on the issue tagging @manager.