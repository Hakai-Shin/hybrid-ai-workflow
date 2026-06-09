---
title: "[intake-M1] DocumentWatcher + IngestController + Tesseract OcrEngine"
labels: area:intake, milestone:M1, type:feature
assignees: ""
---

## Task

Implement the document ingestion pipeline: watch a folder, accept HTTP uploads, run Tesseract OCR, and persist results to the enterprise database.

## Contracts to read

- `shared/contracts/error.v1.json`

## Design docs to read

- `docs/04-api-design.md` (section A1, A2)
- `docs/06-data-models.md` (enterprise DB tables: `documents`, `raw_blobs`)

## Worktree

`worktrees/intake/` on branch `agent/intake`.

## Acceptance

1. `DocumentWatcher` polls `./intake/` folder every 5 seconds. New files trigger processing.
2. `IngestController` accepts `POST /v1/documents` with a multipart file (PDF, PNG, JPEG, TIFF).
3. `OcrEngine` is an interface. Initial implementation wraps Tesseract CLI (`tesseract` command).
4. OCR text is stored in the `documents` table + `raw_blobs` (encrypted).
5. Status state machine: `QUEUED → PROCESSING → REDACTED → FAILED`. (Redaction is a no-op stub for M1; the full Redactor is M2.)
6. Integration test: drop a sample PNG → row in `documents` with non-null `status`.
7. `Flyway` migration creates `V1__documents.sql` and `V2__raw_blobs.sql`.
8. Commit message format: `intake(ingest): <summary>` with `Refs: #2`.

## Definition of Done

- [ ] `mvn -pl enterprise-service -am verify` passes
- [ ] Drop file in `./intake/` → row in DB within 5s
- [ ] `POST /v1/documents` (multipart) returns 202
- [ ] PR touches ONLY `enterprise-service/**`
- [ ] PR title: `[intake-M1] DocumentWatcher + IngestController + Tesseract OcrEngine`