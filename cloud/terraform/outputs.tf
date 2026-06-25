output "artifact_bucket_name" {
  value = module.storage.bucket_name
}

output "pubsub_topic_id" {
  value = module.pubsub.topic_id
}

output "dispatcher_url" {
  value = module.cloudrun.dispatcher_url
}

output "vector_search_index_id" {
  value = module.vertexai.index_id
}

output "vector_search_index_endpoint" {
  value = module.vertexai.index_endpoint_id
}
