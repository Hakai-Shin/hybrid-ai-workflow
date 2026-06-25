variable "project_id" { type = string }
variable "dispatcher_url" { type = string }
variable "pubsub_invoker_sa" { type = string }

resource "google_pubsub_topic" "artifact_intake" {
  name    = "artifact-intake"
  project = var.project_id
}

resource "google_pubsub_subscription" "dispatcher_push" {
  name    = "dispatcher-push"
  topic   = google_pubsub_topic.artifact_intake.name
  project = var.project_id

  push_config {
    push_endpoint = var.dispatcher_url
    oidc_token {
      service_account_email = var.pubsub_invoker_sa
    }
  }

  ack_deadline_seconds       = 60
  message_retention_duration = "86400s"
  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "300s"
  }
}

output "topic_id" {
  value = google_pubsub_topic.artifact_intake.id
}
