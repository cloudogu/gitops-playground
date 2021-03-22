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

variable "min_master_version" {
  type        = string
  description = "Min Master version for the k8s cluster"
}

variable "node_pool_node_count" {
  type        = number
  description = "Number of initial nodes for default node pool"
}

variable "node_version" {
  type        = string
  description = "Version for all nodes"
}
