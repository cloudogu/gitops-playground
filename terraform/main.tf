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

resource "google_container_cluster" "cluster" {
  name               = var.cluster_name
  location           = var.gce_location
  min_master_version = var.min_master_version


  # Initial node gets destroyed immediately and is replaced by node pool
  initial_node_count       = 1
  remove_default_node_pool = true

  # Add entry to local kubeconfig automatically
  provisioner "local-exec" {
    command = "gcloud container clusters get-credentials ${var.cluster_name} --zone ${var.gce_location} --project ${var.gce_project}"
  }
}


# k8s node pool
resource "google_container_node_pool" "node_pool" {
  name       = "default-node-pool"
  location   = var.gce_location
  version    = var.node_version
  cluster    = google_container_cluster.cluster.name
  node_count = var.node_pool_node_count

  management {
    # Avoid upgrade during demos
    auto_upgrade = false
  }

  node_config {
    # We use ubuntu, because Container-optimized OS has /tmp mounted with noexec.
    # This conflicts with the workarounds we need to take to make Jenkins Build runnable with docker inside the cluster
    # Basically: Providing the docker binary within the agent pods, via a hostPath mount :-|
    # On the default Container-optimized OS /tmp mounted with noexec.
    # So we use ubuntu
    # A more robust option would be to provide a jenkins-agent-docker image but for now this seems to much effort
    image_type = "ubuntu"
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
