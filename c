{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:hybrid-ai:contract:artifact:v1",
  "title": "Artifact Envelope",
  "description": "The de-identified artifact pushed from enterprise to cloud. Contains only redacted text and metadata — never raw documents or PHI.",
  "type": "object",
  "required": [
    "schema_version",
    "document_id",
    "trace_id",
    "redaction",
    "metadata",
    "artifact"
  ],
  "properties": {
    "schema_version": {
      "type": "string",
      "enum": ["1.0"],
      "description": "Version of this schema the payload conforms to."
    },
    "document_id": {
      "type": "string",
      "pattern": "^doc_01[A-Z0-9]{16,26}$",
      "description": "ULID generated enterprise-side. The only correlation key across the boundary."
    },
    "trace_id": {
      "type": "string",
      "pattern": "^[0-9a-f]{32}$",
      "description": "W3C traceparent trace-id (16 hex bytes). Propagated to every downstream service."
    },
    "redaction": {
      "type": "object",
      "required": ["policy_id", "redactor", "stats"],
      "properties": {
        "policy_id": {
          "type": "string",
          "description": "The redaction policy applied. e.g. 'phi-strict-v1'."
        },
        "redactor": {
          "type": "string",
          "description": "Engine identifier, e.g. 'presidio-2.2'."
        },
        "stats": {
          "type": "object",
          "additionalProperties": {
            "type": "integer",
            "minimum": 0
          },
          "description": "Counts of redacted tokens by type, e.g. {\"names\":4,\"ssn\":1,\"mrn\":1}."
        }
      }
    },
    "metadata": {
      "type": "object",
      "required": ["source_system", "captured_at"],
      "properties": {
        "source_system": {
          "type": "string",
          "description": "Legacy system identifier, e.g. 'legacy-fs', 'sharepoint'."
        },
        "doc_type_hint": {
          "type": "string",
          "description": "Optional hint from enterprise about document type."
        },
        "page_count": {
          "type": "integer",
          "minimum": 1
        },
        "language": {
          "type": "string",
          "description": "ISO 639-1 language code."
        },
        "captured_at": {
          "type": "string",
          "format": "date-time",
          "description": "ISO 8601 timestamp of when the document was captured."
        }
      }
    },
    "artifact": {
      "type": "object",
      "required": ["redacted_text", "ocr_engine"],
      "properties": {
        "redacted_text": {
          "type": "string",
          "maxLength": 1048576,
          "description": "The OCR'd text with PHI/PII replaced by [REDACTED:TYPE] tokens."
        },
        "ocr_engine": {
          "type": "string",
          "description": "OCR engine identifier, e.g. 'tesseract-5.3'."
        },
        "ocr_confidence": {
          "type": "number",
          "minimum": 0,
          "maximum": 1
        },
        "entities": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "type": { "type": "string" },
              "value": { "type": "string" },
              "span": {
                "type": "array",
                "items": { "type": "integer" },
                "minItems": 2,
                "maxItems": 2
              }
            }
          },
          "description": "Optional pre-redaction entity annotations (for cloud-side verification)."
        }
      }
    }
  }
}