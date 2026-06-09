# 05 — Cloud Workflow Design

## Workflow: `document.enrichment`

The single end-to-end workflow for the MVP. Triggered by `POST /v1/artifacts`.

```
   ┌──────────┐    ┌────────────────┐    ┌──────────────────┐    ┌──────────────┐
   │ accepted │───▶│ classify       │───▶│ extract-entities │───▶│ summarize    │
   └──────────┘    └────────────────┘    └──────────────────┘    └──────┬───────┘
        │                                                              │
        │                                                              ▼
        │                                                       ┌──────────────┐
        │                                                       │ index-search │
        │                                                       └──────┬───────┘
        │                                                              │
        ▼                                                              ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │ workflow.status = ENRICHED (or PARTIAL if any non-critical step      │
   │                          failed, FAILED if classify failed)         │
   └──────────────────────────────────────────────────────────────────────┘
```

- `classify` is **critical** (its output drives downstream branching later).
- `extract-entities`, `summarize` are **best-effort** (failures → PARTIAL).
- `index-search` is **critical** (no point having results you can't find).

---

## Step Definitions

### Step 1 — `classify`
- **Input:** redacted text + metadata hints (`doc_type_hint`, `language`).
- **Processing (MVP):** zero-shot LLM via Ollama. Prompt: *"Classify this document into
  one of: clinical-note, lab-report, discharge-summary, correspondence, insurance-claim,
  other. Return JSON {label, confidence}."* JSON-mode constrained decoding.
- **Output:** `{ "label": "clinical-note", "confidence": 0.91, "model": "llama3:8b" }`
- **Failure policy:** 3 retries with exponential backoff. Final failure → workflow FAILED.
- **Why LLM and not a small classifier?** At MVP scale the LLM doubles as a generic
  text-classifier; we can swap in a fine-tuned small model later behind the same
  `DocumentClassifier` interface.

### Step 2 — `extract-entities`
- **Input:** redacted text + classification.
- **Processing (MVP):** LLM extraction with a typed entity list driven by classification
  (clinical-note → MEDICATION, CONDITION, DOSAGE, etc.; insurance-claim → PROVIDER,
  CPT_CODE, BILLED_AMOUNT, etc.).
- **Output:** `[ { "type": "MEDICATION", "value": "metformin", "span": [120,129] }, ... ]`
- **Failure policy:** 3 retries; on final failure → workflow PARTIAL, mark step FAILED.

### Step 3 — `summarize`
- **Input:** redacted text + classification.
- **Processing (MVP):** LLM, prompt constrained to ≤ 80 words, no PHI references.
- **Output:** string.
- **Failure policy:** same as step 2.
- **Safety net:** a post-generation regex check rejects summaries that contain
  any `[REDACTED:SSN]` / `[REDACTED:MRN]` style token *unbalanced* — we allow them
  (it's fine to say "patient [REDACTED:NAME]...") but we count occurrences and assert
  they're consistent with the redaction stats.

### Step 4 — `index-search`
- **Input:** redacted text, classification label, entity list, summary.
- **Processing:** writes one row to `enriched_documents` with a `tsvector` column built
  from `(redacted_text + summary + entity_values)`.
- **Output:** Postgres row.
- **Failure policy:** 5 retries; final failure → workflow FAILED (operators need to know).

---

## Orchestrator Mechanics

A `WorkflowOrchestrator` service does the following in one Postgres transaction on
artifact acceptance:

```sql
INSERT INTO workflows (workflow_id, document_id, status, created_at)
VALUES (?, ?, 'ACCEPTED', now());

INSERT INTO workflow_steps (workflow_id, step_name, step_order, status, attempt)
VALUES
  (?, 'classify',         1, 'PENDING', 0),
  (?, 'extract-entities', 2, 'PENDING', 0),
  (?, 'summarize',        3, 'PENDING', 0),
  (?, 'index-search',     4, 'PENDING', 0);
```

Then publishes one message to `doc.classify` to start the chain.

A `WorkflowStepAdvancer` listens to `cloud.workflow.step.completed` (a separate fan-in
topic) and, for each completed step, publishes the *next* step's message and updates
`workflow_steps.status`. This is the "thin state machine."

**Why a separate fan-in topic?** Decouples the worker (which only knows its own step) from
the orchestrator (which knows the chain). Workers publish `{workflow_id, step_name, status,
result}`; the advancer is the only thing that knows the next step.

### Concurrency & Ordering

- Steps for the same `document_id` are *not* required to run in order — they are independent
  except for the chain dependency. We tolerate slight reordering by making the advancer
  idempotent ("is the next step still PENDING? then run it; else skip").
- Different documents fan out fully in parallel. Pub/Sub handles this.
- Per-step concurrency is controlled by worker concurrency settings (Cloud Run max
  instances + Pub/Sub subscriber `maxOutstandingMessages`).

### Failure Semantics

| Outcome | Workflow status | Operator sees |
|---|---|---|
| All steps DONE | `ENRICHED` | summary, entities, classification visible |
| classify FAILED, others skipped | `FAILED` | redacted artifact available for re-processing |
| One of entities/summarize FAILED | `PARTIAL` | other results visible, failed step marked |
| index-search FAILED | `FAILED` | step will be retried 5×; then DLQ topic `cloud.workflow.dlq` |
| Artifact schema invalid | `REJECTED` | redaction bug suspected; immediate alert |

DLQ messages are kept 7 days. A small re-driver CLI (`./bin/replay-dlq.sh`) replays them.

---

## Versioning the Workflow

The workflow definition is code (not config). Adding a step = adding a row to the
seed-data + adding a worker. Removing a step is a migration that backfills `status='SKIPPED'`
for in-flight workflows. We never edit a step in place — we ship a new step version and
branch on `workflows.workflow_version`.

---

## Observability Hooks

- Every worker emits a Micrometer counter `workflow_step_total{step,outcome}`.
- A `workflow_duration_seconds` timer tagged by step.
- A `redaction_token_count` gauge per workflow (cloud side, derived from artifact).
- Logs are JSON, include `trace_id`, `workflow_id`, `document_id`.
- One Cloud Logging query per step type: latency p50/p95, error rate.

---

## Why Not Temporal / Step Functions / Airflow?

| Option | Verdict | Reason |
|---|---|---|
| **Pub/Sub + small state machine (chosen)** | ✅ | ~150 LoC of orchestrator; zero new deps; trivially Cloud Run-native |
| Temporal | ❌ (post-MVP) | Heavy runtime, requires a dedicated cluster; 3× the code for the same outcome at our scale |
| AWS Step Functions / GCP Workflows | ⚠️ | Reasonable, but we'd lose the Spring Boot worker symmetry and the resume-friendly local emulator story. Revisit at production scale. |
| Airflow | ❌ | DAG model is for batch scheduling, not per-document event orchestration |
| Camunda | ❌ | BPMN complexity not justified for 4 step types |

The design intentionally leaves the door open: swap in Temporal by replacing
`WorkflowStepAdvancer` with a Temporal workflow — workers stay the same.
