# 04 — API Design

## Design Principles

1. **One-way artifact API.** Cloud APIs accept *artifacts*, not *documents*. There is no
   `GET /documents/{id}/file` on the cloud side, ever.
2. **Idempotency.** Every write takes a client-generated `idempotency_key`. Retries are safe.
3. **Schema-first.** All payloads are JSON Schema–validated at the cloud boundary.
4. **Status over polling, where possible.** Cloud emits workflow step events to Pub/Sub; a
   thin status endpoint exists for the operator UI.
5. **Versioned paths.** `/v1/...` everywhere from day one.
6. **Trace IDs.** Every request has a `traceparent` header (W3C). We log it everywhere.

---

## A. Enterprise-side APIs (intra-enterprise, not internet-exposed)

### A1. `POST /v1/documents` — Ingest a document

```http
POST /v1/documents
Authorization: Bearer <internal-token>
Content-Type: multipart/form-data
Idempotency-Key: <uuid>
```

Body: the file (PDF / PNG / JPEG / TIFF).

**202 Accepted**

```json
{
  "document_id": "doc_01HZX...",
  "job_id":     "job_01HZY...",
  "status":     "QUEUED",
  "trace_id":   "8f1c..."
}
```

The operator UI polls `GET /v1/documents/{document_id}` for terminal state.

### A2. `GET /v1/documents/{document_id}`

```json
{
  "document_id": "doc_01HZX...",
  "status": "ENRICHED",                // QUEUED | PROCESSING | REDACTED | SYNCED | ENRICHED | FAILED
  "created_at": "2026-06-09T15:00:00Z",
  "redaction_stats": {
    "names": 4, "ssn": 1, "mrn": 1, "dates": 7, "addresses": 2
  },
  "cloud_status": "ENRICHED",          // null until synced
  "trace_id": "8f1c..."
}
```

> Important: this endpoint returns **stats and IDs only**, never the raw text or file. The
> raw file is only accessible via the local admin API guarded by an internal role.

### A3. `POST /v1/admin/documents/{document_id}/sync` (enterprise → cloud)

Manually trigger an artifact push (normally automatic once REDACTED). Used in tests.

### A4. `GET /v1/admin/documents/{document_id}/raw` (LOCAL-ONLY, admin role)

For debugging. Not exposed in the cloud network. Disabled in production builds.

---

## B. Cloud-side APIs (the only thing the enterprise ever calls on the cloud)

### B1. `POST /v1/artifacts` — Push an artifact (the one and only write endpoint)

```http
POST /v1/artifacts
Content-Type: application/json
X-Client-Cert: <mTLS>
X-Idempotency-Key: <uuid>
```

```json
{
  "schema_version": "1.0",
  "document_id":    "doc_01HZX...",
  "trace_id":       "8f1c...",
  "redaction": {
    "policy_id":     "phi-strict-v1",
    "redactor":      "presidio-2.2",
    "stats":         { "names": 4, "ssn": 1, "mrn": 1 }
  },
  "metadata": {
    "source_system": "legacy-fs",
    "doc_type_hint": "clinical-note",
    "page_count":    3,
    "language":      "en",
    "captured_at":   "2026-06-09T14:55:00Z"
  },
  "artifact": {
    "redacted_text": "Patient [REDACTED:NAME] was seen on [REDACTED:DATE] ...",
    "ocr_engine":    "tesseract-5.3",
    "ocr_confidence": 0.92
  }
}
```

**201 Created**

```json
{
  "workflow_id": "wf_01HZZ...",
  "status":      "ACCEPTED",
  "steps_planned": ["classify", "extract-entities", "summarize", "index-search"]
}
```

**Failure modes (all return problem+json):**
- `400` schema validation failure (artifact shape wrong).
- `401`/`403` mTLS / token failure.
- `409` idempotency conflict.
- `413` artifact body too large (limit: 1 MB text; bigger → caller should chunk + reference GCS).

### B2. `GET /v1/workflows/{workflow_id}`

```json
{
  "workflow_id":  "wf_01HZZ...",
  "document_id":  "doc_01HZX...",
  "status":       "ENRICHED",        // ACCEPTED | RUNNING | ENRICHED | PARTIAL | FAILED
  "steps": [
    { "name": "classify",        "status": "DONE",    "started_at": "...", "finished_at": "..." },
    { "name": "extract-entities","status": "DONE",    "started_at": "...", "finished_at": "..." },
    { "name": "summarize",       "status": "DONE",    "started_at": "...", "finished_at": "..." },
    { "name": "index-search",    "status": "DONE",    "started_at": "...", "finished_at": "..." }
  ],
  "results": {
    "classification": { "label": "clinical-note",  "confidence": 0.91 },
    "entities":       [ { "type": "MEDICATION", "value": "metformin" } ],
    "summary":        "Visit for diabetes follow-up; A1C discussed.",
    "indexed_at":     "2026-06-09T15:01:12Z"
  }
}
```

### B3. `GET /v1/search?q=<query>&type=<entity>&limit=20`

Query the enriched index.

```json
{
  "hits": [
    {
      "document_id":    "doc_01HZX...",
      "workflow_id":    "wf_01HZZ...",
      "classification": "clinical-note",
      "snippet":        "...discussed [REDACTED:NAME]'s A1C...",
      "entities":       [ { "type": "MEDICATION", "value": "metformin" } ],
      "summary":        "Visit for diabetes follow-up; A1C discussed."
    }
  ],
  "total": 1
}
```

> Note the snippet still contains `[REDACTED:NAME]` tokens — proof that no raw PHI leaks
> through search. This is a feature, not a bug; it doubles as a visible test signal.

### B4. `GET /v1/health`, `GET /v1/ready`

Standard. `/ready` checks DB, Pub/Sub, and (for workers) the LLM dependency.

---

## C. Internal Pub/Sub message contracts

Each topic carries a single envelope. `trace_id` is required on every message.

```json
// topic: doc.classify
{
  "workflow_id": "wf_01HZZ...",
  "document_id": "doc_01HZX...",
  "trace_id":    "8f1c...",
  "artifact_ref": "gcs://bucket/artifacts/doc_01HZX....json",
  "attempt":     1,
  "scheduled_at":"2026-06-09T15:00:05Z"
}
```

The same envelope is reused for `extract-entities`, `summarize`, `index-search`.

> Workers fetch the artifact body from `artifact_ref` (a GCS object) rather than inlining
> the text in the message. This keeps Pub/Sub messages small and lets us attach provenance
> (e.g., signed URLs) cleanly.

### Idempotency

Each envelope carries `message_id` (UUID v7). Workers record `message_id` in a
`processed_messages` table (or use Pub/Sub exactly-once delivery in production). On
duplicate delivery, the worker re-reads the workflow step and skips if already DONE.

---

## D. Webhook (optional, post-MVP)

Enterprise may subscribe to `cloud.workflow.completed` to update its local store.
This is a one-way callback with **derived data only**; never raw artifacts.

---

## E. Error Model (problem+json, RFC 7807)

```json
{
  "type":     "https://errors.hybrid-ai.example/schema-validation",
  "title":    "Artifact schema validation failed",
  "status":   400,
  "detail":   "Field 'redaction.policy_id' is required",
  "trace_id": "8f1c...",
  "errors": [
    { "pointer": "/redaction/policy_id", "code": "required" }
  ]
}
```

Every error response carries `trace_id` so a single grep across both sides of the boundary
gives you the full story.
