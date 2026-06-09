---
title: "[cloud-M2] End-to-end: SummarizerWorker + IndexWorker + Query API"
labels: area:cloud, milestone:M2, type:feature
assignees: ""
---

## Task

Complete the workflow chain: implement the SummarizerWorker, IndexWorker, and the search query API. Then wire the end-to-end test.

## Contracts to read

- `shared/contracts/pubsub-step-envelope.v1.json`
- `shared/contracts/workflow-step.v1.json`

## Design docs to read

- `docs/05-cloud-workflows.md` (steps 3 and 4, failure semantics)
- `docs/06-data-models.md` (cloud DB: `enriched_documents`)
- `docs/04-api-design.md` (section B3)

## Worktree

`worktrees/cloud/` on branch `agent/cloud`.

## Files you may edit

```
cloud-services/**
```

## Acceptance

1. `SummarizerWorker` (SERVICE_ROLE=worker-summarize):
   - Subscribes to `doc.summarize`.
   - Calls Ollama with prompt: "Summarize this document in ≤ 80 words, no patient identifiers."
   - Writes result, reports completion.
   - Post-generation regex check: if any `[REDACTED:SSN]` or `[REDACTED:MRN]` token appears, flag and reject the summary. (Redaction tokens in summaries are OK — we just assert we didn't miss any.)
2. `IndexWorker` (SERVICE_ROLE=worker-index):
   - Subscribes to `doc.index-search`.
   - Writes one `enriched_documents` row with: redacted_text, classification, entities (JSONB), summary, and a GENERATED tsvector column.
3. Query API (SERVICE_ROLE=query):
   - `GET /v1/search?q=<query>&limit=20` returns enriched hits.
   - Hits include: `document_id`, `classification`, `snippet`, `entities`, `summary`.
   - Search uses PostgreSQL `tsvector` + `ts_query` full-text search.
4. End-to-end workflow completes with `status=ENRICHED`.
5. Integration test: POST artifact → wait for ENRICHED → search returns results.
6. Failure handling: IndexWorker has 5 retries; final failure → workflow FAILED.

## Definition of Done

- [ ] SummarizerWorker produces a summary from Ollama
- [ ] IndexWorker writes `enriched_documents` row with tsvector
- [ ] `GET /v1/search?q=...` returns hits
- [ ] Full workflow reaches `ENRICHED` status
- [ ] End-to-end integration test passes
- [ ] PR title: `[cloud-M2] Summarizer + Indexer + Query API`