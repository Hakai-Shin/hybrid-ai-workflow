---
title: "[intake-M0] Bootstrap enterprise-service Spring Boot module"
labels: area:intake, milestone:M0, type:setup
assignees: ""
---

## Task

Create the `enterprise-service` Maven module (Spring Boot 3 + Java 21) module within the monorepo. This is the foundation for all intake agent work.

## Contract files to read (do not edit)

- `shared/contracts/artifact.v1.json`
- `shared/contracts/error.v1.json`

## Design docs to read

- `docs/04-api-design.md` (enterprise API section A)
- `docs/06-data-models.md` (enterprise DB schema section E)

## Worktree

`worktrees/intake/` on branch `agent/intake`.

## Files you may edit

```
enterprise-service/**
```

## Acceptance

1. `mvn -pl enterprise-service -am verify` passes.
2. Module `pom.xml` depends on:
   - `spring-boot-starter-web`
   - `spring-boot-starter-actuator`
   - `spring-boot-starter-data-jpa`
   - `postgresql` (PostgreSQL JDBC driver)
   - `HikariCP`
   - `flyway-core`, `flyway-database-postgresql`
3. `GET /v1/health` returns `{"status":"UP"}`.
4. A `Dockerfile` exists at `enterprise-service/Dockerfile` that:
   - Builds with Maven (`mvn -pl enterprise-service -am package -DskipTests`)
   - Uses `eclipse-temurin:21-jre-alpine` as runtime base
   - Exposes port 8080
5. `application.yml` (or `.properties`) configures:
   - PostgreSQL connection (defaults that work against `enterprise-db:5432`)
   - Flyway migration location
   - Actuator endpoints
6. No code under `shared/`, `cloud-services/`, `boundary/`, or `docs/`.

## Definition of Done

- [ ] Code compiles
- [ ] Unit tests pass
- [ ] `GET /v1/health` returns 200
- [ ] PR touches ONLY `enterprise-service/**`
- [ ] PR title: `[intake-M0] Bootstrap enterprise-service Spring Boot module`