package main

import "time"

type ArtifactPackage struct {
	JobID          string                 `json:"job_id"`
	SourceID       string                 `json:"source_id"`
	DocTypeHint    string                 `json:"doc_type_hint"`
	PageCount      int                    `json:"page_count"`
	RedactedText   string                 `json:"redacted_text"`
	Metadata       map[string]interface{} `json:"metadata"`
	PhiEntityCount int                    `json:"phi_entity_count"`
	CreatedAt      time.Time             `json:"created_at"`
	EnclaveVersion string                 `json:"enclave_version"`
}
