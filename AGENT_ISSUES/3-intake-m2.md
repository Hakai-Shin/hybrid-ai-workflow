---
title: "[intake-M2] Redactor interface + RegexRedactor + PresidioRedactor + ArtifactPackager"
labels: area:intake, milestone:M2, type:feature
assignees: ""
---

## Task

Implement PHI/PII redaction and artifact packaging on the enterprise side. This is the most critical M2 issue — it proves the data-classification boundary works.

## Contracts to read

- `shared/contracts/artifact.v1.json` (artifact envelope shape)
- `shared/contracts/redaction-policy.v1.json` (policy identifier)
- `shared/contracts/error.v1.json`

## Design docs to read

- `docs/04-api-design.md` (section A2, admin endpoints)
- `docs/06-data-models.md` (enterprise DB table `document_artifacts`)

## Worktree

`worktrees/intake/` on branch `agent/intake`.

## Acceptance

1. `Redactor` interface with two implementations:
   - `RegexRedactor`: catches SSN (\\d{3}-\\d{2}-\\d{4}), MRN (MRN-\\d{6}), email, phone, US ZIP.
   - `PresidioRedactor`: calls Presidio analyzer at `http://presidio:5000`, catches PERSON, LOCATION, DATE_TIME.
2. Configurable policy: `phi-strict-v1` enables both; `phi-minimal-v1` enables regex only.
3. `ArtifactPackager` produces `document_artifacts` row with AES-GCM-encrypted `redacted_text_cipher`.
4. Redaction stats recorded per document: `{"names": 2, "ssn": 1, "mrn": 1, "dates": 3}`.
5. **CRITICAL CI TEST** (this gates every PR): Run redactor on a fixture containing:
   - SSN `123-45-6789`
   - Name `John Smith`
   - MRN `MRN-987654`
   - Assert none of the originals appear in the output.
   - Assert `stats.names >= 1, stats.ssn >= 1, stats.mrn >= 1`.
6. `Flyway` migration `V3__document_artifacts.sql`.
7. Admin endpoint `GET /v1/admin/documents/{id}/raw` returns the redacted text (for debugging).

## Definition of Done

- [ ] Redaction fixture test passes (no PHI leak)
- [ ] `mvn verify` green
- [ ] PR touches ONLY `enterprise-service/**`
- [ ] PR title: `[intake-M2] Redactor + RegexRedactor + PresidioRedactor + ArtifactPackager`