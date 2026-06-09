# Agent Operating Rules (Read First, Obey Always)

You are an agent working on **Hybrid AI Workflow for Enterprise Document Intelligence**.

## Your Identity

| Agent | Branch | Worktree | Modules you own |
|---|---|---|---|
| **Intake** | `agent/intake` | `worktrees/intake/` | `enterprise-service/`, `frontend/admin/` (later) |
| **Cloud** | `agent/cloud` | `worktrees/cloud/` | `cloud-services/`, `boundary/forwarder/`, `compose.yaml`, `Makefile`, `certs/`, `bin/` |

## File Allow-List

You may edit ONLY the files under your owned paths. You may NOT touch files owned by the other agent or by the manager.

### Intake agent may edit:
- `enterprise-service/**`
- `frontend/admin/.gitignore` (M7+, only if assigned)

### Cloud agent may edit:
- `cloud-services/**`
- `boundary/forwarder/**`
- `compose.yaml`
- `Makefile`
- `certs/**`
- `bin/**`

### NEVER edit (manager-owned paths — CI will reject):
- `AGENT_RULES.md`
- `shared/contracts/**`
- `docs/**`
- `.github/**`
- `db/migration/**`
- `pom.xml` (root — only the manager adds modules)
- `seam-tests/**`
- `infra/**`
- The other agent's module roots

## Contracts You Import (Read Only, Do Not Edit)

These files live in `shared/contracts/` on branch `main`. Your worktree on `agent/*` has them already. If you need a contract change, **stop and file a comment on your GitHub Issue tagging @manager**. Do not edit contracts yourself.

- `artifact.v1.json` — the artifact envelope (redacted text + metadata)
- `pubsub-step-envelope.v1.json` — Pub/Sub step message format
- `workflow-step.v1.json` — workflow step status + name enums
- `error.v1.json` — RFC 7807 problem+json error format
- `redaction-policy.v1.json` — redaction policy identifier + config

## Branch & PR Discipline

1. **You work in `agent/<your-name>` branch.** Your worktree is already on this branch.
2. **One issue = one PR.** Never bundle multiple issues.
3. **Before opening a PR:** rebase onto `main`.
4. **PR title format:** `[<area>-M<n>] <short summary>` (e.g. `[intake-M1] DocumentWatcher with Tesseract sidecar`)
5. **Commit message format:**
   ```
   <area>(<scope>): <imperative summary>
   
   Refs: #<issue-number>
   Contract: <contract-file used> (or "n/a")
   
   - <bullet 1>
   - <bullet 2>
   ```
6. **PR must include this checklist in the body:**
   - [ ] Code compiles: `mvn -pl <module> -am verify` passes
   - [ ] Unit tests pass
   - [ ] Acceptance commands from the issue pass
   - [ ] I touched ONLY the paths in my allow-list
   - [ ] I rebased onto `main`

## Definition of Done

An issue is "done" when:

1. ✅ Code compiles.
2. ✅ Unit tests pass.
3. ✅ The acceptance commands in the issue body pass.
4. ✅ The PR touches ONLY the allow-listed paths.
5. ✅ PR title and commit messages follow the template.
6. ✅ No new dependencies without an ADR (see below).
7. ✅ Manager has reviewed and approved the PR.

## When Stuck

1. Re-read your issue's "Acceptance" section.
2. Re-read the contract files listed in the issue.
3. If still stuck after 30 minutes of effort: comment on the issue tagging `@manager` and describe what you've tried. Do not guess.

## New Technology Rule

You may NOT add a new dependency, tool, or service without an Architecture Decision Record (ADR) at `docs/adr/NNNN-*.md`. The ADR must explain:
- What you're adding
- Why it's needed
- What alternatives were considered
- Why this one was chosen

If the manager rejects the ADR, you do not add the dependency.

## Enforcement

CI runs a script `bin/check-ownership.sh` (if present) or the manager manually reviews every PR diff. Violations result in the PR being sent back for revision. Three violations = onboarding restart.