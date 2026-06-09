# 06 — Data Models

Two databases, deliberately separated. The enterprise DB is the system of record for raw
documents; the cloud DB is the system of record for derived artifacts. **There is no
shared schema, no foreign keys across the boundary.** The link is `document_id` (a ULID
generated enterprise-side).

---

## E. Enterprise DB (PostgreSQL)

### `documents`
```sql
CREATE TABLE documents (
  document_id      TEXT PRIMARY KEY,        -- ULID, e.g. doc_01HZX...
  source_system    TEXT NOT NULL,           -- 'legacy-fs' | 'sharepoint' | 's3-legacy'
  source_uri       TEXT NOT NULL,           -- opaque reference into the legacy system
  filename         TEXT NOT NULL,
  mime_type        TEXT NOT NULL,
  byte_size        BIGINT NOT NULL,
  sha256           BYTEA NOT NULL,          -- integrity
  status           TEXT NOT NULL,           -- QUEUED|PROCESSING|REDACTED|SYNCED|ENRICHED|FAILED
  trace_id         TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### `document_artifacts` (redacted text + metadata, what gets sent to the cloud)
```sql
CREATE TABLE document_artifacts (
  artifact_id        TEXT PRIMARY KEY,      -- ULID
  document_id        TEXT NOT NULL REFERENCES documents(document_id),
  redaction_policy   TEXT NOT NULL,         -- 'phi-strict-v1'
  redactor_engine    TEXT NOT NULL,         -- 'presidio-2.2'
  redaction_stats    JSONB NOT NULL,        -- {names:4, ssn:1, ...}
  doc_type_hint      TEXT,
  language           TEXT,
  page_count         INT,
  ocr_engine         TEXT NOT NULL,
  ocr_confidence     REAL,
  redacted_text      TEXT NOT NULL,         -- sensitive even after redaction; stored encrypted
  redacted_text_cipher BYTEA NOT NULL,      -- AES-GCM ciphertext
  redacted_text_iv   BYTEA NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON document_artifacts (document_id);
```

### `sync_log` (one row per push to cloud)
```sql
CREATE TABLE sync_log (
  sync_id        BIGSERIAL PRIMARY KEY,
  document_id    TEXT NOT NULL REFERENCES documents(document_id),
  artifact_id    TEXT NOT NULL REFERENCES document_artifacts(artifact_id),
  status         TEXT NOT NULL,             -- PUSHED|ACK|FAILED
  http_status    INT,
  error          TEXT,
  attempt        INT NOT NULL DEFAULT 1,
  pushed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON sync_log (document_id, pushed_at DESC);
```

### `raw_blobs` (encrypted at rest; never sent to cloud)
```sql
CREATE TABLE raw_blobs (
  document_id   TEXT PRIMARY KEY REFERENCES documents(document_id),
  blob_cipher   BYTEA NOT NULL,
  blob_iv       BYTEA NOT NULL,
  byte_size     BIGINT NOT NULL
);
```

---

## C. Cloud DB (PostgreSQL)

### `workflows`
```sql
CREATE TABLE workflows (
  workflow_id     TEXT PRIMARY KEY,         -- ULID
  document_id     TEXT NOT NULL,            -- mirror of enterprise id
  workflow_version INT NOT NULL DEFAULT 1,
  status          TEXT NOT NULL,            -- ACCEPTED|RUNNING|ENRICHED|PARTIAL|FAILED|REJECTED
  trace_id        TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON workflows (document_id);
CREATE INDEX ON workflows (status, created_at);
```

### `workflow_steps`
```sql
CREATE TABLE workflow_steps (
  workflow_id   TEXT NOT NULL REFERENCES workflows(workflow_id),
  step_name     TEXT NOT NULL,              -- 'classify' | 'extract-entities' | 'summarize' | 'index-search'
  step_order    INT  NOT NULL,
  status        TEXT NOT NULL,              -- PENDING|RUNNING|DONE|FAILED|SKIPPED
  attempt       INT  NOT NULL DEFAULT 0,
  started_at    TIMESTAMPTZ,
  finished_at   TIMESTAMPTZ,
  error         TEXT,
  result        JSONB,                      -- step-specific output
  PRIMARY KEY (workflow_id, step_name)
);
```

### `enriched_documents` (the search index, MVP)
```sql
CREATE TABLE enriched_documents (
  document_id     TEXT PRIMARY KEY,         -- no FK across boundary; uniqueness only
  workflow_id     TEXT NOT NULL,
  classification  TEXT,
  classification_confidence REAL,
  entities        JSONB NOT NULL DEFAULT '[]',
  summary         TEXT,
  redacted_text   TEXT NOT NULL,
  search_tsv      tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(summary,'')), 'A') ||
    setweight(to_tsvector('english', coalesce(redacted_text,'')), 'B') ||
    setweight(to_tsvector('english', array_to_string(
      (SELECT array_agg(value) FROM jsonb_array_elements_text(entities)), ' ')), 'A')
  ) STORED,
  indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX enriched_search_idx ON enriched_documents USING GIN (search_tsv);
CREATE INDEX enriched_class_idx  ON enriched_documents (classification);
```

> A `GENERATED` tsvector keeps the index correct for free; the indexer worker just writes
> the row and Postgres maintains the FTS index. No Elasticsearch needed at MVP.

### `processed_messages` (worker idempotency)
```sql
CREATE TABLE processed_messages (
  message_id   TEXT PRIMARY KEY,
  workflow_id  TEXT NOT NULL,
  step_name    TEXT NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### `audit_log`
```sql
CREATE TABLE audit_log (
  audit_id     BIGSERIAL PRIMARY KEY,
  workflow_id  TEXT,
  document_id  TEXT,
  actor        TEXT,                        -- 'worker:classifier' | 'orchestrator' | etc.
  event        TEXT NOT NULL,               -- 'workflow.accepted' | 'step.completed' | 'dlq'
  payload      JSONB,
  occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON audit_log (workflow_id, occurred_at);
```

---

## GCS Object Layout (cloud side)

```
gs://<bucket>/
  artifacts/
    <document_id>/
      <artifact_id>.json          # signed on push; contains redacted_text + metadata
  dlq/
    <workflow_id>/
      <step_name>-<ts>.json
```

The artifact body is also persisted in GCS even though we ship it inline. Reasons:
1. Workers can re-read the source of truth if the message is replayed days later.
2. The body may exceed the 1 MB inline limit; we fall back to a GCS reference.
3. Easier to produce an audit pack for compliance review.

---

## Cross-Boundary Identifiers

- `document_id`: ULID, generated enterprise-side. Globally unique. Cloud uses it as the
  only correlation key.
- `workflow_id`: ULID, generated cloud-side on artifact acceptance. Cloud-internal.
- `trace_id`: W3C `traceparent`, generated by the first request and propagated.

No PII (names, SSNs, MRNs) ever appears in IDs, log lines, or filenames. Test fixtures
include negative tests for this.

---

## Migration & Versioning

- **Schema migrations** use Flyway on both DBs from day one. Migrations live in
  `db/migration/V{n}__...sql` and are applied automatically at service start.
- **Backwards compatibility** for the artifact envelope is the JSON schema's job
  (`schema_version`). Cloud always supports the previous N versions for 90 days.
- **Idempotent migrations** only — never destructive on first run.
