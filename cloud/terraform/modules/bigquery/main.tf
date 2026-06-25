variable "project_id" { type = string }

resource "google_bigquery_dataset" "docubrain" {
  dataset_id = "docubrain"
  location   = "US"
  project    = var.project_id
}

resource "google_bigquery_table" "jobs" {
  dataset_id = google_bigquery_dataset.docubrain.dataset_id
  table_id   = "jobs"
  project    = var.project_id
  deletion_protection = false

  schema = jsonencode([
    { name = "job_id",           type = "STRING",    mode = "REQUIRED" },
    { name = "source_id",        type = "STRING",    mode = "NULLABLE" },
    { name = "doc_type_hint",    type = "STRING",    mode = "NULLABLE" },
    { name = "page_count",       type = "INT64",     mode = "NULLABLE" },
    { name = "phi_entity_count", type = "INT64",     mode = "NULLABLE" },
    { name = "enclave_version",  type = "STRING",    mode = "NULLABLE" },
    { name = "status",           type = "STRING",    mode = "NULLABLE" },
    { name = "created_at",       type = "TIMESTAMP", mode = "NULLABLE" },
    { name = "updated_at",       type = "TIMESTAMP", mode = "NULLABLE" },
  ])
}

resource "google_bigquery_table" "classifications" {
  dataset_id = google_bigquery_dataset.docubrain.dataset_id
  table_id   = "classifications"
  project    = var.project_id
  deletion_protection = false

  schema = jsonencode([
    { name = "job_id",      type = "STRING",    mode = "REQUIRED" },
    { name = "doc_type",    type = "STRING",    mode = "NULLABLE" },
    { name = "sensitivity", type = "STRING",    mode = "NULLABLE" },
    { name = "language",    type = "STRING",    mode = "NULLABLE" },
    { name = "topics",      type = "JSON",      mode = "NULLABLE" },
    { name = "created_at",  type = "TIMESTAMP", mode = "NULLABLE" },
  ])
}

resource "google_bigquery_table" "entities" {
  dataset_id = google_bigquery_dataset.docubrain.dataset_id
  table_id   = "entities"
  project    = var.project_id
  deletion_protection = false

  schema = jsonencode([
    { name = "job_id",               type = "STRING",    mode = "REQUIRED" },
    { name = "organizations",        type = "JSON",      mode = "NULLABLE" },
    { name = "dates",                type = "JSON",      mode = "NULLABLE" },
    { name = "monetary_amounts",     type = "JSON",      mode = "NULLABLE" },
    { name = "locations",            type = "JSON",      mode = "NULLABLE" },
    { name = "document_references",  type = "JSON",      mode = "NULLABLE" },
    { name = "created_at",           type = "TIMESTAMP", mode = "NULLABLE" },
  ])
}

resource "google_bigquery_table" "summaries" {
  dataset_id = google_bigquery_dataset.docubrain.dataset_id
  table_id   = "summaries"
  project    = var.project_id
  deletion_protection = false

  schema = jsonencode([
    { name = "job_id",     type = "STRING",    mode = "REQUIRED" },
    { name = "summary",    type = "STRING",    mode = "NULLABLE" },
    { name = "created_at", type = "TIMESTAMP", mode = "NULLABLE" },
  ])
}

resource "google_bigquery_table" "embeddings" {
  dataset_id = google_bigquery_dataset.docubrain.dataset_id
  table_id   = "embeddings"
  project    = var.project_id
  deletion_protection = false

  schema = jsonencode([
    { name = "job_id",           type = "STRING",    mode = "REQUIRED" },
    { name = "model_version",    type = "STRING",    mode = "NULLABLE" },
    { name = "vector_dimension", type = "INT64",     mode = "NULLABLE" },
    { name = "index_endpoint",   type = "STRING",    mode = "NULLABLE" },
    { name = "created_at",       type = "TIMESTAMP", mode = "NULLABLE" },
  ])
}
