### Variables for deployment ###
# See descriptions of variables in vars.tf

# GCE settings
gce_project = "GCE_PROJECT_NAME"
gce_location= "europe-west3-a"

# Cluster settings
cluster_name = "gitops-cluster"
# We use 1.17 because as of 01/2021 later version can only be used via a release channel
# A release channel seems to force node auto upgrade, which we want to disable for demo purposes. 
min_master_version = "1.17.13-gke.2600"
node_version = "1.17.13-gke.2600"
node_pool_node_count = 2
