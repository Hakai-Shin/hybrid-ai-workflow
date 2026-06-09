---
title: "[cloud-M2] End-to-end: forwarder + cloud-services integration harness"
labels: area:cloud, milestone:M2, type:test
assignees: ""
---

## Task

Wire the end-to-end test that proves the forwarder, cloud-services ingest, orchestrator, and workers work together. This is the integration seam test that validates the multi-agent work integrates correctly.

## Contracts to read

- None new; read all previously used contracts.

## Worktree

`worktrees/cloud/` on branch `agent/cloud`.

## Files you may edit

```
cloud-services/**
boundary/forwarder/**
compose.yaml
bin/**
```

## Acceptance

1. A script `bin/e2e-test.sh` exists that:
   - Starts `make up` (if not already running).
   - Generates a test artifact (valid JSON matching `artifact.v1.json`).
   - Sends it via `curl --cert client.pem --key client-key.pem --cacert ca.pem -X POST https://localhost:9443/v1/artifacts -d @fixture.json`.
   - Polls `GET /v1/workflows/{workflow_id}` until status is ENRICHED or FAILED (timeout: 60s).
   - If ENRICHED, runs a search query and asserts hits > 0.
   - Prints PASS/FAIL.
2. The test verifies all 4 steps completed.
3. The test verifies the search result does NOT contain any `123-45-6789` or similar raw PHI (use a test artifact that includes "SSN: 123-45-6789" in the original text, verify only `[REDACTED:SSN]` remains).
4. A smoke test `bin/smoke-test.sh` runs a quicker version (just ingest → orchestrator, no search) in < 10s.

## Definition of Done

- [ ] `bin/e2e-test.sh` passes on a clean `make up`
- [ ] Search results show `[REDACTED:SSN]` tokens, never raw SSNs
- [ ] `bin/smoke-test.sh` passes in < 10s
- [ ] PR title: `[cloud-M2] End-to-end forwarder + ingest integration harness`