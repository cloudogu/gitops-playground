terraform {
  backend "gcs" {}

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 3.51.0"
    }
  }

  required_version = ">= 0.14"
}

provider "google" {
  credentials = file("account.json") # Access to the cluster. Needs to be created first
  project     = var.gce_project
}



# k8s cluster
resource "google_container_cluster" "cluster" {
  name               = var.cluster_name
  location           = var.gce_location
  min_master_version = var.min_master_version


  # Initial node gets destroyed immediately and is replaced by node pool
  initial_node_count       = 1
  remove_default_node_pool = true


  # This block makes the cluster VPC-native, which we need for redis (Google Cloud MemoryStore) and is also useful for
  # Postgresql (Google CloudSQL)
  ip_allocation_policy {
  }
}


# k8s node pool
resource "google_container_node_pool" "node_pool" {
  name       = "default-node-pool"
  location   = var.gce_location
  version    = var.node_version
  cluster    = google_container_cluster.cluster.name
  node_count = var.node_pool_node_count

  #management {
  #  auto_upgrade = false
  #}

  node_config {
    preemptible  = false
    machine_type = "n1-standard-2" # pricing: https://cloud.google.com/compute/vm-instance-pricing#n1_predefined
    oauth_scopes = [
      "https://www.googleapis.com/auth/logging.write",
      "https://www.googleapis.com/auth/monitoring",
      # Grants Read Access to GCR to clusters
      "https://www.googleapis.com/auth/devstorage.read_only",
    ]
  }
}
