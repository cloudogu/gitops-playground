# Values of vars are defined in values.tfvars

variable "gce_project" {
  type        = string
  description = "Determines the Google Cloud project to be used"
}

variable "gce_location" {
  type        = string
  description = "The GCE location to be used. Defaults to us-central1-a"
}

variable "credentials" {
  default = "account.json"
}

variable "cluster_name" {
  type        = string
  description = "Cluster to refer to"
}

variable "node_pool_node_count" {
  type        = number
  description = "Number of initial nodes for default node pool"
  default = 2
}

variable "k8s_version_prefix" {
  type        = string
  # Docs recommend to not use fuzzy version here:
  # https://www.terraform.io/docs/providers/google/r/container_cluster.html
  # OTOH google deprecates support for specific version rather fast.
  # Resulting in "Error 400: Master version "X" is unsupported., badRequest"
  # So we use a version prefix hoping that the stable patch versions won't do unexpected things (which is unlikely!) 
  description = "Master and Node version prefix to setup"

  # When updating please also adapt in Dockerfile, init-cluster.sh and ApplicationConfigurator.groovy
  default = "1.29."
}

variable "creator" {
  type = map
  default = null
}
