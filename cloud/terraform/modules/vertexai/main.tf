variable "region" { type = string }
variable "environment" { type = string }
variable "artifacts_bucket" { type = string }

resource "google_vertex_ai_index" "docubrain" {
  display_name = "docubrain-index-${var.environment}"
  region       = var.region

  metadata {
    contents_delta_uri = "gs://${var.artifacts_bucket}/index-staging/"
    config {
      dimensions                  = 768
      approximate_neighbors_count = 10
      distance_measure_type       = "COSINE_DISTANCE"
      algorithm_config {
        tree_ah_config {
          leaf_node_embedding_count = 1000
        }
      }
    }
  }

  index_update_method = "STREAM_UPDATE"
}

resource "google_vertex_ai_index_endpoint" "docubrain" {
  display_name = "docubrain-endpoint-${var.environment}"
  region       = var.region
}

resource "google_vertex_ai_index_endpoint_deployed_index" "docubrain" {
  index_endpoint  = google_vertex_ai_index_endpoint.docubrain.id
  index           = google_vertex_ai_index.docubrain.id
  deployed_index_id = "docubrain_deployed_${var.environment}"

  automatic_resources {
    min_replica_count = 1
    max_replica_count = 3
  }
}

output "index_id" {
  value = google_vertex_ai_index.docubrain.id
}

output "index_endpoint_id" {
  value = google_vertex_ai_index_endpoint.docubrain.id
}
