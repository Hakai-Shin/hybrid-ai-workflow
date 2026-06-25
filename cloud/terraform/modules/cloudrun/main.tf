variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "artifact_bucket" { type = string }
variable "index_endpoint" { type = string }
variable "index_id" { type = string }

locals {
  worker_sa    = "docubrain-worker@${var.project_id}.iam.gserviceaccount.com"
  invoker_sa   = "docubrain-tasks-invoker@${var.project_id}.iam.gserviceaccount.com"
  image_prefix = "gcr.io/${var.project_id}"

  ai_workers = {
    classifier = { path = "/classify", memory = "1Gi" }
    extractor  = { path = "/extract",  memory = "1Gi" }
    summariser = { path = "/summarise", memory = "1Gi" }
    embedder   = { path = "/embed",    memory = "1Gi" }
  }
}

resource "google_cloud_run_v2_service" "dispatcher" {
  name     = "docubrain-dispatcher-${var.environment}"
  location = var.region
  project  = var.project_id

  template {
    service_account = local.worker_sa
    containers {
      image = "${local.image_prefix}/docubrain-dispatcher:latest"
      resources { limits = { memory = "512Mi" } }
      env { name = "GCP_PROJECT_ID",           value = var.project_id }
      env { name = "GCS_ARTIFACT_BUCKET",      value = var.artifact_bucket }
      env { name = "CLASSIFICATION_WORKER_URL", value = google_cloud_run_v2_service.ai_worker["classifier"].uri }
      env { name = "EXTRACTION_WORKER_URL",     value = google_cloud_run_v2_service.ai_worker["extractor"].uri }
      env { name = "SUMMARISATION_WORKER_URL",  value = google_cloud_run_v2_service.ai_worker["summariser"].uri }
      env { name = "EMBEDDING_WORKER_URL",      value = google_cloud_run_v2_service.ai_worker["embedder"].uri }
    }
    scaling { min_instance_count = 0; max_instance_count = 10 }
  }
}

resource "google_cloud_run_v2_service" "ai_worker" {
  for_each = local.ai_workers
  name     = "docubrain-${each.key}-${var.environment}"
  location = var.region
  project  = var.project_id

  template {
    service_account = local.worker_sa
    containers {
      image = "${local.image_prefix}/docubrain-${each.key}:latest"
      resources { limits = { memory = each.value.memory } }
      env { name = "GCP_PROJECT_ID",              value = var.project_id }
      env { name = "GCS_ARTIFACT_BUCKET",         value = var.artifact_bucket }
      env { name = "BIGQUERY_DATASET",            value = "docubrain" }
      env { name = "VERTEX_AI_REGION",            value = var.region }
      env { name = "VECTOR_SEARCH_INDEX_ENDPOINT", value = var.index_endpoint }
      env { name = "VECTOR_SEARCH_INDEX_ID",      value = var.index_id }
    }
    scaling { min_instance_count = 0; max_instance_count = 10 }
  }
}

resource "google_cloud_run_v2_service_iam_member" "dispatcher_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.dispatcher.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:docubrain-pubsub-invoker@${var.project_id}.iam.gserviceaccount.com"
}

resource "google_cloud_run_v2_service_iam_member" "ai_worker_invoker" {
  for_each = local.ai_workers
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.ai_worker[each.key].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${local.invoker_sa}"
}

output "dispatcher_url" {
  value = google_cloud_run_v2_service.dispatcher.uri
}
