---
title: "[cloud-M1] Forwarder: enforce POST /v1/artifacts only, mTLS"
labels: area:cloud, milestone:M1, type:feature
assignees: ""
---

## Task

Implement the boundary forwarder that enforces the one-way artifact push. Only `POST /v1/artifacts` with valid mTLS client certs is allowed across the boundary.

## Contracts to read

- `shared/contracts/error.v1.json`

## Design docs to read

- `docs/04-api-design.md` (section B1)
- `docs/07-deployment-architecture.md` (local compose section)

## Worktree

`worktrees/cloud/` on branch `agent/cloud`.

## Files you may edit

```
boundary/forwarder/**
certs/**
bin/**
compose.yaml
Makefile
```

## Acceptance

1. `POST /v1/artifacts` with valid mTLS client cert → forwarded to `cloud-services:8080/v1/artifacts` (expect 404 or 201 depending on M1-cloud status).
2. `POST /v1/artifacts` without mTLS → 401.
3. `GET /v1/artifacts` → 403 (wrong method).
4. `POST /v1/documents` → 403 (disallowed path).
5. The `Host`, `Cookie`, `Authorization` headers are stripped from the forwarded request.
6. `make certs` generates:
   - `certs/ca.pem` (self-signed CA)
   - `certs/server.pem` + `certs/server-key.pem` (forwarder's server cert)
   - `certs/client.pem` + `certs/client-key.pem` (enterprise client cert)
7. Forwarder Dockerfile uses either:
   - NGINX with mTLS config (simpler), or
   - A tiny Go/Java reverse proxy (more code but more impressive for a resume).
   Choose the option that produces the strongest portfolio impact for a ~5hr task.

## Definition of Done

- [ ] Forwarder rejects all paths except POST /v1/artifacts
- [ ] Forwarder rejects requests without valid client cert
- [ ] Headers stripped before forwarding
- [ ] `make certs` generates the 5 cert files
- [ ] PR title: `[cloud-M1] Forwarder: enforce POST /v1/artifacts only, mTLS`