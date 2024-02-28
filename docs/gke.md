### Google Kubernetes Engine

⚠️ Note that since k8s 1.24 [GKE no longer supports nodes with docker](https://cloud.google.com/kubernetes-engine/docs/deprecations/docker-containerd).
That is why the **Jenkins deployed on GKE can no longer successfully run builds that use the `docker` step** in K8s agent Pods.
We recommend setting up [GCP VMs as build agents](https://cloud.google.com/architecture/using-jenkins-for-distributed-builds-on-compute-engine#configuring_jenkins_plugins).

#### Prerequisites

You will need the `OWNER` role fpr GKE, because we need `ClusterRoles`, which are only allowed to owners.

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
```

Create a service account:
```shell
gcloud iam service-accounts create terraform-cluster \
  --display-name terraform-cluster --project ${PROJECT_ID}
```

Authorize Service Account

```shell
gcloud projects add-iam-policy-binding ${PROJECT_ID} \
    --member serviceAccount:terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com --role=roles/editor
```

Create an account.json file, which contains the keys for the service account.
You will need this file to apply the infrastructure:

```shell
gcloud iam service-accounts keys create \
  --iam-account terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com \
  terraform/account.json
```

##### State


You can either use a remote state (default, described bellow) or use a local state by changing the following in `versions.tf`:
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

For local state `terraform init` suffices.

```shell
cd terraform
terraform init  \
  -backend-config "credentials=account.json" \
  -backend-config "bucket=${BUCKET_NAME}\"
```

Apply infra:
```shell
terraform apply -var gce_project=${PROJECT_ID}
```

terraform apply already adds an entry to your local `kubeconfig` and activate the context. That is calling
`kubectl get pod` should already connect to the cluser.

If not, you can create add an entry to your local `kubeconfig` like so:

```shell
gcloud container clusters get-credentials ${cluster_name} --zone ${gce_location} --project ${gce_project}
```

Now you're ready to apply the apps to the cluster.

Note that to be able to access the services remotely you either need to pass the
* `--remote` flag (exposes alls services as `LoadBalancer` with external IP) or
* `--ingress-nginx --base-url=$yourdomain` and either set a DNS record or `/etc/hosts` entries to the external IP of the
  ingress-nginx service. 

##### Clean up

Once you're done with the playground, you can destroy the cluster using

```shell
terraform destroy -var gce_project=${PROJECT_ID}
```

In addition you might want to delete
* The service account or key and
* the state bucket (if created)

You either delete the key or the whole service account:

Key: 
```shell
gcloud iam service-accounts keys delete $(cat account.json | grep private_key_id  | sed 's/".*: "\(.*\)".*/\1/') \
    --iam-account terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com
```

Service Account:
```shell
gcloud iam service-accounts delete terraform-cluster@${PROJECT_ID}.iam.gserviceaccount.com \
  --project ${PROJECT_ID}
```

Bucket:

```shell
gsutil rm -r  gs://${BUCKET_NAME}
```