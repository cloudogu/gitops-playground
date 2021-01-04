### Variables for deployment ###
# See descriptions of variables in vars.tf

# GCE settings
gce_project = "GCE_PROJECT_NAME"
gce_location= "europe-west3-a"

# Cluster settings
cluster_name = "gitops-cluster"
min_master_version = "1.18.12-gke.1200"
node_version = "1.18.12-gke.1200"
node_pool_node_count = 2
