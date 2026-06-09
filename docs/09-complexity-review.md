# 09 — Complexity Review (What We're Deliberately *Not* Building)

This document is a working list of features/technologies a reviewer might expect — and
why we're shipping without them. It's also a checklist for the principal-engineer-in-me
to push back on the engineer-in-me when scope creep shows up.

If a proposed addition isn't on this list AND can't be justified by a resume bullet,
it's almost certainly out of scope for v1.

---

## 1. No chatbot / no chat endpoint

**Tempting because:** AI + LLM → "add a chat UX."
**Rejected because:** The brief explicitly excludes it. The architecture is a
document-intelligence *platform*, not an assistant. A chat UX would force us to expose
raw artifact bodies to a frontend, which collides with the data-classification boundary.
**Instead:** a search API + a results viewer is enough. If we want conversational
querying later, it goes against the same search index through a separate,
strictly-permissioned service.

## 2. No custom vector database

**Tempting because:** LLM + "embeddings" feels table-stakes.
**Rejected because:** Building a vector DB is its own project. We don't need semantic
search for v1; Postgres FTS with `tsvector` (and redaction tokens as the only PII) is
sufficient and dramatically simpler.
**Instead:** store `embedding` (JSONB array) per document if we want; if/when we need
real semantic search we add **Vertex AI Search** (or pgvector) behind the existing
`SearchIndex` interface. The interface change is one PR.

## 3. No workflow engine clone

**Tempting because:** The chain has multiple steps, so why not Temporal/Cadence/Conductor?
**Rejected because:** Those are full-blown workflow products; we'd be paying for
durability, signals, timers, and child workflows we don't need. A 150-line state
machine in Postgres + a `step.completed` topic is ~10× less code and 0 new ops surface.
**Instead:** the `WorkflowStepAdvancer` is intentionally small enough that swapping to
Temporal later is a contained rewrite of one class. Workers are unaffected.

## 4. No custom storage system

**Tempting because:** "Enterprise document repository" feels like it should be custom.
**Rejected because:** The on-prem side already has a storage system — that's the
*legacy* one we're integrating with. Our job is to *read* from it (a folder/S3/SharePoint
mount), not replace it. Cloud side uses GCS. We build a connector, not a store.

## 5. No FHIR / no clinical data model

**Tempting because:** Clinical notes suggest FHIR.
**Rejected because:** A FHIR server is a multi-month, multi-engineer project in its own
right. The brief explicitly excludes it. Our entities are *typed strings* returned by
the LLM, not a validated FHIR resource graph.
**Instead:** the entity type vocabulary is small and stable. If a real customer wants
FHIR, we add a `FhirMapper` bean that consumes `entities` and emits `Bundle` resources —
it's a new module, not a re-architecture.

## 6. No frontend-heavy application

**Tempting because:** Demos look better with a UI.
**Rejected because:** Frontend work is a different skill set and would eat 30–40% of
our time for a portfolio project where the backend is the story. A single-page React
admin page with an upload button + status poll + search box is plenty.
**Instead:** `frontend/admin/` is a 4-component SPA. No build pipeline beyond Vite.

## 7. No event-sourced design

**Tempting because:** Pub/Sub + workflows + "audit" → "let's event-source everything."
**Rejected because:** Event sourcing adds a new mental model (projections, replays,
versioned events) that is overkill for a 4-step workflow. The `audit_log` table already
gives us an append-only record.
**Instead:** plain mutable `workflow_steps` rows + an append-only `audit_log`. Operators
get the audit story without paying the event-sourcing tax.

## 8. No service mesh

**Tempting because:** Microservices → Istio/Linkerd.
**Rejected because:** We have 7 services that talk via Pub/Sub. There is no east-west
RPC. A service mesh is solving a problem we don't have.
**Instead:** Cloud Run handles ingress, mTLS at the boundary is terminated by the load
balancer. If we add direct service-to-service calls later, we re-evaluate.

## 9. No Kubernetes on the cloud side

**Tempting because:** It's the "real" deployment.
**Rejected because:** Cloud Run *is* the deployment target and it removes 80% of the
k8s operational burden. The resume value comes from "deployed to Cloud Run with Pub/Sub
and Cloud SQL" — not "ran our own k8s cluster."
**Exception:** the on-prem side may run on k8s in real customer deployments; that
deployment is documented in `docs/07-deployment-architecture.md` but not built.

## 10. No multi-tenant enterprise support

**Tempting because:** Cloud projects make this easy.
**Rejected because:** The first customer is one enterprise. Multi-tenant adds
row-level security, tenant-aware key derivation, and per-tenant rate limits — none of
which matter when N=1. **Add this only when the second customer shows up.**
**Instead:** `documents.tenant_id` is a column in v1 (cheap) but we don't enforce
isolation beyond the application layer yet. Documented as a known limit.

## 11. No fine-tuned models

**Tempting because:** "Domain-specific accuracy" sounds impressive.
**Rejected because:** We don't have labeled data, and the prompt-engineered zero-shot
approach is good enough to demo. Fine-tuning is a project, not a feature.
**Instead:** swap to fine-tuned models behind the same `DocumentClassifier` interface
once labeled data exists.

## 12. No GraphQL / gRPC

**Tempting because:** Microservices often use one of these.
**Rejected because:** REST + JSON is what the brief implicitly asks for, what every
junior reviewer can read, and what every Spring Boot interview covers. gRPC would buy
us a tiny perf win and cost us a week of `protoc` plumbing.
**Exception:** internal Pub/Sub payloads are JSON (we already version them with a
schema). No gRPC anywhere.

## 13. No Kubernetes-style rolling deploys in dev

**Tempting because:** "Production-like" is appealing.
**Rejected because:** Cloud Run deploys are the production-like thing. Adding Helm +
Argo for the dev demo is a tax with no buyer.
**Instead:** Cloud Run source deploys in CI; k8s manifests documented in
`infra/k8s-reference/` for customer-side reference only.

## 14. No secrets in Vault for the demo

**Tempting because:** "Secrets in env vars is bad."
**Rejected because:** Adding Vault (or even a proper Secret Manager binding) to the
local demo adds a sidecar and a 20-minute setup. We use a local `.env` file and
explicitly call it out in the README: "DO NOT use this in production."
**Production:** Secret Manager is wired up in M8.

## 15. No multi-region / DR

**Tempting because:** "Cloud-grade" usually means multi-region.
**Rejected because:** Single-region with Cloud SQL HA and a cross-region read replica
is plenty for a portfolio. Multi-region active-active is 3× the work for a 0.01%
revenue impact at this stage.
**ADR:** "Why we ship single-region for v1."

---

## Push-Back Heuristics

When a new idea comes up, ask:

1. **Does it advance a resume bullet?** If no, defer.
2. **Does it change the architectural thesis?** If yes, big discussion.
3. **Can it be added later behind an existing interface?** If yes, defer.
4. **Is it visible in the demo?** If no, low priority.
5. **Does it have a free/cheap local equivalent?** If yes, use that.

If three of five are "defer" or "no," it's not in v1.
