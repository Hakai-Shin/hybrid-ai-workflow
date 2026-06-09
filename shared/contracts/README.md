# Shared Contracts — Frozen

This directory contains the JSON Schema files that define the contracts between the
enterprise side and the cloud side.

## Rules

1. **These files are frozen.** Do not edit them without manager approval.
2. **Both sides import these schemas.** The intake agent validates against them when
   packaging artifacts. The cloud agent validates against them when ingesting.
3. **Contract change requests** must be filed as an issue, not a PR. The manager
   evaluates, may write a new schema version, and bumps the version number.
4. **Backwards compatibility** is maintained for at least the previous 2 schema versions.

## Files

| File | Purpose |
|---|---|
| `artifact.v1.json` | The artifact envelope pushed from enterprise to cloud |
| `pubsub-step-envelope.v1.json` | The Pub/Sub message format for worker steps |
| `workflow-step.v1.json` | Enum definitions for step names and statuses |
| `error.v1.json` | RFC 7807 problem+json format for API errors |
| `redaction-policy.v1.json` | Redaction policy identifier and configuration |

## Schema Versioning

Schemas use `"$id": "urn:hybrid-ai:contract:<name>:v1"`. When a breaking change is
required, a new `v2` file is added beside the old one, and the old one is deprecated.
The `schema_version` field in the artifact envelope tells the cloud which schema to
validate against.