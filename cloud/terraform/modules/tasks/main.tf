variable "region" { type = string }
variable "project_id" { type = string }

resource "google_cloud_tasks_queue" "worker_queues" {
  for_each = toset([
    "classification-queue",
    "extraction-queue",
    "summarisation-queue",
    "embedding-queue",
  ])

  name     = each.key
  location = var.region
  project  = var.project_id

  rate_limits {
    max_dispatches_per_second = 10
    max_concurrent_dispatches = 5
  }

  retry_config {
    max_attempts = 5
    min_backoff  = "10s"
    max_backoff  = "300s"
  }
}
