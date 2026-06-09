---
title: "[cloud-M0] Bootstrap cloud-services Spring Boot + boundary/forwarder + compose.yaml"
labels: area:cloud, milestone:M0, type:setup
assignees: ""
---

## Task

Scaffold the cloud side: the cloud-services Spring Boot module, the boundary forwarder, the docker-compose environment, and the tooling.

## Contracts to read

- `shared/contracts/error.v1.json`
- `shared/contracts/workflow-step.v1.json`

## Design docs to read

- `docs/04-api-design.md` (cloud API section B)
- `docs/07-deployment-architecture.md` (local compose section)

## Worktree

`worktrees/cloud/` on branch `agent/cloud`.

## Files you may edit

```
cloud-services/**
boundary/forwarder/**
compose.yaml
Makefile
certs/**
bin/**
```

## Acceptance

1. `mvn -pl cloud-services -am verify` passes with 3 profiles:
   - `SERVICE_ROLE=ingest` boots with `/v1/health` 200.
   - `SERVICE_ROLE=orchestrator` boots (no HTTP controllers, but health endpoint).
   - `SERVICE_ROLE=worker-classify` boots with health endpoint only.
2. `SERVICE_ROLE=invalid` exits with error log.
3. `boundary/forwarder/Dockerfile` exists (NGINX or simple Go/Java forwarder).
4. `compose.yaml` defines:
   - Two Docker networks: `enterprise_net` and `cloud_net`.
   - `enterprise-db` (postgres:16) on `enterprise_net`.
   - `cloud-db` (postgres:16) on `cloud_net`.
   - `pubsub-emulator` (google/cloud-sdk:emulators) on `cloud_net`.
   - `cloud-storage-emulator` (fsouza/fake-gcs-server) on `cloud_net`.
   - `cloud-services` on `cloud_net`.
   - `boundary/forwarder` on BOTH networks (the bridge).
5. `Makefile` has: `up`, `logs`, `down`, `certs`, `test`.
6. `make certs` generates: CA cert, server cert, and a client cert (self-signed, for mTLS).
7. `Dockerfile` at `cloud-services/Dockerfile` (eclipse-temurin:21-jre-alpine based).

## Definition of Done

- [ ] `mvn -pl cloud-services -am verify` passes
- [ ] `make up` brings up all services without errors
- [ ] PR touches ONLY files in allow-list
- [ ] PR title: `[cloud-M0] Bootstrap cloud-services + forwarder + compose`