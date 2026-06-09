---
title: "[cloud-M1] ArtifactIngestController: accept and validate artifact via POST /v1/artifacts"
labels: area:cloud, milestone:M1, type:feature
assignees: ""
---

## Task

Implement `POST /v1/artifacts` on the cloud side: validate the artifact envelope against the JSON Schema, return 201 with a `workflow_id`, or 400 with problem+json on schema violations.

## Contracts to read

- `shared/contracts/artifact.v1.json`
- `shared/contracts/error.v1.json`
- `shared/contracts/workflow-step.v1.json`
- `shared/contracts/redaction-policy.v1.json`

## Design docs to read

- `docs/04-api-design.md` (section B1, B4, error model E)
- `docs/06-data-models.md` (cloud DB: `workflows`, `workflow_steps`, `audit_log`, `processed_messages`)

## Worktree

`worktrees/cloud/` on branch `agent/cloud`.

## Files you may edit

```
cloud-services/**
```

## Acceptance

1. `POST /v1/artifacts` validates the body against `artifact.v1.json` schema.
2. Returns 400 with problem+json for:
   - Missing required field.
   - Wrong `schema_version`.
   - Invalid `document_id` pattern.
3. Returns 401/403 on mTLS failure (handled by the forwarder, but the cloud-services endpoint is also behind mTLS).
4. Returns 409 on duplicate `Idempotency-Key` (idempotency key tracked in `processed_messages` table).
5. Returns 201 Created with `{"workflow_id": "wf_01HZZ...", "status": "ACCEPTED", "steps_planned": ["classify", "extract-entities", "summarize", "index-search"]}` on success.
6. `ArtifactValidator` is a Spring bean that uses a JSON Schema validator (e.g. `networknt/json-schema-validator`).
7. `Flyway` migration for cloud DB tables: `V1__workflows.sql`, `V2__workflow_steps.sql`, `V3__processed_messages.sql`, `V4__audit_log.sql`.
8. Integration test: `POST /v1/artifacts` with valid body → 201 + workflow_id.

## Definition of Done

- [ ] `POST /v1/artifacts` validates schema, returns 201 on success
- [ ] Returns 400/409/403 on failures
- [ ] Idempotency tracked in `processed_messages`
- [ ] Integration test passes
- [ ] PR title: `[cloud-M1] ArtifactIngestController with schema validation`