---
title: "[cloud-M2] WorkflowOrchestrator + ClassifierWorker + EntityExtractorWorker"
labels: area:cloud, milestone:M2, type:feature
assignees: ""
---

## Task

Implement the workflow orchestrator and the first two worker services. When an artifact is accepted, the orchestrator creates workflow state and publishes a `doc.classify` message. The ClassifierWorker reads it, calls Ollama, and returns a label. The EntityExtractorWorker extracts typed entities.

## Contracts to read

- `shared/contracts/pubsub-step-envelope.v1.json`
- `shared/contracts/workflow-step.v1.json`
- `shared/contracts/artifact.v1.json`

## Design docs to read

- `docs/05-cloud-workflows.md` (steps 1 and 2, orchestrator mechanics)
- `docs/06-data-models.md` (cloud DB: `workflows`, `workflow_steps`)
- `docs/04-api-design.md` (section B2, Pub/Sub section C)

## Worktree

`worktrees/cloud/` on branch `agent/cloud`.

## Files you may edit

```
cloud-services/**
```

## Acceptance

1. `POST /v1/artifacts` creates: 1 `workflows` row + 4 `workflow_steps` rows (PENDING).
2. `PubSubPublisher` publishes one message to `doc.classify` topic.
3. `ClassifierWorker` (SERVICE_ROLE=worker-classify):
   - Subscribes to `doc.classify`.
   - Calls Ollama at `http://ollama:11434/api/generate` with prompt: "Classify this document into one of: clinical-note, lab-report, discharge-summary, correspondence, insurance-claim, other. Return JSON {label, confidence}."
   - Writes `workflow_steps.status = DONE` with result JSON.
   - Publishes `cloud.workflow.step.completed` with step result.
4. `WorkflowStepAdvancer` (SERVICE_ROLE=orchestrator):
   - Subscribes to `cloud.workflow.step.completed`.
   - On `classify` DONE: publishes `doc.extract-entities`.
5. `ExtractEntitiesWorker` (SERVICE_ROLE=worker-entities):
   - Subscribes to `doc.extract-entities`.
   - Calls Ollama with entity extraction prompt.
   - Writes result and publishes step.completed.
6. Failure handling: 3 retries with exponential backoff. On final failure → workflow PARTIAL.
7. Integration test: POST valid artifact → classify worker produces a label.

## Definition of Done

- [ ] Orchestrator creates workflow state + publishes classify message
- [ ] ClassifierWorker returns a label from Ollama
- [ ] EntityExtractorWorker returns typed entities
- [ ] Step advancement works: classify → extract-entities
- [ ] PR title: `[cloud-M2] Orchestrator + Classifier + EntityExtractor`