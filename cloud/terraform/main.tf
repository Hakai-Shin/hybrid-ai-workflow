terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

module "storage" {
  source      = "./modules/storage"
  environment = var.environment
  region      = var.region
  project_id  = var.project_id
}

module "pubsub" {
  source         = "./modules/pubsub"
  project_id     = var.project_id
  dispatcher_url = module.cloudrun.dispatcher_url
  pubsub_invoker_sa = "docubrain-pubsub-invoker@${var.project_id}.iam.gserviceaccount.com"
}

module "tasks" {
  source     = "./modules/tasks"
  region     = var.region
  project_id = var.project_id
}

module "bigquery" {
  source     = "./modules/bigquery"
  project_id = var.project_id
}

module "vertexai" {
  source          = "./modules/vertexai"
  region          = var.region
  environment     = var.environment
  artifacts_bucket = module.storage.bucket_name
}

module "cloudrun" {
  source      = "./modules/cloudrun"
  project_id  = var.project_id
  region      = var.region
  environment = var.environment
  artifact_bucket = module.storage.bucket_name
  index_endpoint  = module.vertexai.index_endpoint_id
  index_id        = module.vertexai.index_id
}
