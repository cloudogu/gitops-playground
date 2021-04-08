### Google Kubernetes Engine

#### Prerequisites

You will need the `OWNER` role fpr GKE, because `apply.sh` applies `ClusterRoles`, which is only allowed to owners.

#### Create Cluster using Terraform
The following steps are deploying a k8s cluster with a node pool to GKE in the europe-west-3 region.
The required terraform files are located in the `./terraform/` folder.
You have to set `PROJECT_ID` to the correct ID of your Google Cloud project.

Login to GCP from your local machine:

```shell
gcloud auth login
```

Select the project, where you want to deploy the cluster:
```shell
PROJECT_ID=<your project ID goes here>
gcloud config set project ${PROJECT_ID}
```

Create a service account:
```shell
gcloud iam service-accounts create terraform-cluster \
  --display-name terraform-cluster --project ${PROJECT_ID}
```

Authorize Service Accout

```shell
gcloud projects add-iam-policy-binding ${PROJECT} \
    --member terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com --role=roles/editor
```

Create an account.json file, which contains the keys for the service account.
You will need this file to apply the infrastructure:
```shell
gcloud iam service-accounts keys create \
  --iam-account terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com \
  terraform/account.json
```

##### State

You can either use a remote state (default, described bellow) or use a local state by changing the following in `main.tf`:
```
-  backend "gcs" {}
+  backend "local" {}
```

If you want to work several persons on the project, use a remote state. The following describes how it works:

Create a bucket for the terraform state file:
```shell
BUCKET_NAME=terraform-cluster-state
gsutil mb -p ${PROJECT_ID} -l EUROPE-WEST3 gs://${BUCKET_NAME}
```

Grant the service account permissions for the bucket:
```shell
gsutil iam ch \
  serviceAccount:terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com:roles/storage.admin \
  gs://${BUCKET_NAME}
```

##### Create cluster

Before continuing with the terraform steps, you have to open the `terraform.tfvars` file
and edit the `gce_project` value to your specific ID.

For local state `terraform init` suffices.
```shell
cd terraform
terraform init  \
  -backend-config "credentials=account.json" \
  -backend-config "bucket=${BUCKET_NAME}\"
```

Apply infra:
```shell
terraform apply
```

terraform apply already adds an entry to your local `kubeconfig` and activate the context. That is calling
`kubectl get pod` should already connect to the cluser.

If not, you can create add an entry to your local `kubeconfig` like so:

```shell
gcloud container clusters get-credentials ${cluster_name} --zone ${gce_location} --project ${gce_project}
```

##### Delete Cluster

Once you're done you can destroy the cluster using

```shell
terraform destroy -var-file values.tfvars
```