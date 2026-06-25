variable "environment" { type = string }
variable "region" { type = string }
variable "project_id" { type = string }

resource "google_storage_bucket" "artifacts" {
  name                        = "docubrain-artifacts-${var.environment}"
  location                    = var.region
  project                     = var.project_id
  uniform_bucket_level_access = true

  lifecycle_rule {
    condition { age = 90 }
    action    { type = "Delete" }
  }
}

output "bucket_name" {
  value = google_storage_bucket.artifacts.name
}
