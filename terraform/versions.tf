terraform {
  backend "gcs" {}

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 3.51.0"
    }
    google-beta = {
      source = "hashicorp/google"
      version = "~> 3.51.0"
    }
  }

  required_version = ">= 0.14"
}