# Agent Prompt Template (Manager fills in per-issue)

> Paste this into the Copilot Cline extension / Ollama agent to spin up.

## Context

You are Agent `<AGENT_NAME>` working on the **Hybrid AI Workflow** project.
You are working in the worktree at `C:\workspace\hybrid-ai-workflow\worktrees\<AGENT_NAME>\`.
Your branch is `agent/<AGENT_NAME>`.

## Task

Complete **Issue #<ISSUE_NUMBER>** — `<ISSUE_TITLE>`

## Files you may edit

```
<ALLOW_LIST_PATH1>/**
<ALLOW_LIST_PATH2>/**
```

## Files you may NOT edit

```
shared/contracts/**
AGENT_RULES.md
docs/**
<the other agent's module roots>
pom.xml (root)
```

## Contracts to import (read from `shared/contracts/`)

- `artifact.v1.json`
- `pubsub-step-envelope.v1.json`
- `workflow-step.v1.json`
- `error.v1.json`
- `redaction-policy.v1.json`

## Definition of Done

1. Code compiles.
2. Unit tests pass.
3. Acceptance commands from the issue pass.
4. You touched ONLY the files in your allow-list.
5. PR title format: `[<AREA>-M<N>] <summary>`
6. Commit message format: `<area>(<scope>): <summary>` with `Refs: #<issue>`.

## Acceptance

```
<ISSUE_ACCEPTANCE>

```

## When stuck

1. Re-read this brief.
2. Re-read the contracts.
3. If still stuck after 30 min, comment on the issue tagging @manager.