# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows. Derived from our [consulting experience](https://cloudogu.com/en/consulting/).

# Table of contents

<!-- Update with `doctoc --notitle README.md`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->


  - [Create Cluster](#create-cluster)
    - [k3s](#k3s)
      - [Prerequisites](#prerequisites)
      - [Create Cluster](#create-cluster-1)
    - [Google Kubernetes Engine](#google-kubernetes-engine)
      - [Prerequisites](#prerequisites-1)
      - [Create Cluster using Terraform](#create-cluster-using-terraform)
  - [Apply apps to cluster](#apply-apps-to-cluster)
  - [Applications](#applications)
    - [Jenkins](#jenkins)
    - [SCM-Manager](#scm-manager)
    - [ArgoCD UI](#argocd-ui)
  - [Test applications deployed via GitOps](#test-applications-deployed-via-gitops)
        - [PetClinic via Flux V1](#petclinic-via-flux-v1)
        - [3rd Party app (NGINX) via Flux V1](#3rd-party-app-nginx-via-flux-v1)
        - [PetClinic via Flux V2](#petclinic-via-flux-v2)
        - [PetClinic via ArgoCD](#petclinic-via-argocd)
  - [Remove apps from cluster](#remove-apps-from-cluster)
- [Options](#options)
  - [Multiple stages](#multiple-stages)
        - [This feature is currently only useable for the plain petclinic with fluxv1](#this-feature-is-currently-only-useable-for-the-plain-petclinic-with-fluxv1)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

Can be run on a local k3s cluster or on Google Container Engine.

## Create Cluster

### k3s

#### Prerequisites

To be able to set up the infrastructure you need a linux machine (tested with Ubuntu 20.04) with docker installed.
All other tools like kubectl, k3s and helm are set up using the `./scripts/init-cluster.sh` script.

#### Create Cluster
You can use your own k3s cluster, or use the script provided.
Run this script from repo root with:

`./scripts/init-cluster.sh`

If you use your own cluster, note that jenkins relies on the `--docker` mode to be enabled.

In a real-life scenario, it would make sense to run Jenkins agents outside the cluster for security and load reasons, 
but in order to simplify the setup for this playground we use this slightly dirty workaround: 
Jenkins builds in agent pods that are able to spawn plain docker containers docker host that runs the containers.
That's why we need the k3s' `--docker` mode.
 
**Don't use a setup such as this in production!** The diagrams bellow show an overview of the playground's architecture,
 and a possible production scenario using our [Ecosystem](https://cloudogu.com/en/ecosystem/) (more secure and better build performance using ephemeral build agents spawned in the cloud).


|Playground on local machine | A possible production environment with [Cloudogu Ecosystem](https://cloudogu.com/en/ecosystem/)|
|--------------------|----------|
|![Playground on local machine](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/gitops-playground.puml&fmt=svg) | ![A possible production environment](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/production-setting.puml&fmt=svg)   |

### Google Kubernetes Engine

#### Prerequisites

You will need the `OWNER` role fpr GKE, because `apply.sh` applies `ClusterRoles`, which is only allowed to owners.

#### Create Cluster using Terraform
The following steps are deploying a k8s cluster with a node pool to GKE in the europe-west-3 region.
The required terraform files are located in the ./terraform/ folder.
You have to change `PROJECT_ID` to the correct ID of your Google Cloud project.

Login to GCP from your local machine:
```
gcloud auth login
```

Select the project, where you want to deploy the cluster:
```
gcloud config set project PROJECT_ID
```

Create a service account:
```
gcloud iam service-accounts create terraform-cluster --display-name terraform-cluster --project PROJECT_ID
```

Create an account.json file, which contains the keys for the service account.
You will need this file to apply the infrastructure:
```
gcloud iam service-accounts keys create --iam-account terraform-cluster@PROJECT_ID.iam.gserviceaccount.com terraform/account.json
```

Create a bucket for the terraform state file:
```
gsutil mb -p PROJECT_ID -l EUROPE-WEST3 gs://BUCKET_NAME
```

Grant the service account permissions for the bucket:
```
gsutil iam ch serviceAccount:terraform-cluster@PROJECT_ID.iam.gserviceaccount.com:roles/storage.admin gs://BUCKET_NAME
```

Before continuing with the terraform steps, you have to open the `values.tfvars` file
and edit the `gce_project` value to your specific ID.


Initialize the terraform backend:
```
cd terraform
terraform init -backend-config "credentials=account.json" \
  -backend-config "bucket=BUCKET_NAME"
```

Apply infra:
```
terraform apply -var-file values.tfvars
```

Once you're done you can destroy the cluster using

```
terraform destroy -var-file values.tfvars
```

## Apply apps to cluster

[`scripts/apply.sh`](scripts/apply.sh)

You can also just install one GitOps module like Flux V1 or ArgoCD via parameters.  
Use `./scripts/apply.sh --help` for more information.

The scripts also prints a little intro on how to get started with a GitOps deployment.


## Applications

### Jenkins

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - `scmadmin/scmadmin`
Change in `jenkins-credentials.yaml` if necessary.

Note: You can enable browser notifications about build results via a button in the lower right corner of Jenkins Web UI.

![Enable Jenkins Notifications](docs/jenkins-enable-notifications.png)

![Example of a Jenkins browser notifications](docs/jenkins-example-notification.png)
  

### SCM-Manager

Find scm-manager on http://localhost:9091

Login with `scmadmin/scmadmin`

### ArgoCD UI

Find the ArgoCD UI on http://localhost:9092

Login with `admin/admin`

## Test applications deployed via GitOps

##### PetClinic via Flux V1

* [Jenkinsfile](applications/petclinic/fluxv1/plain-k8s/Jenkinsfile) for plain `k8s` deployment
  * [localhost:9000](http://localhost:9000) (Staging)
  * [localhost:9001](http://localhost:9001) (Production)
  * [localhost:9002](http://localhost:9002) (qa)  
  
* [Jenkinsfile](applications/petclinic/fluxv1/helm/Jenkinsfile) for `helm` deployment
  * [localhost:9003](http://localhost:9003) (Staging)
  * [localhost:9004](http://localhost:9004) (Production) 

##### 3rd Party app (NGINX) via Flux V1

* [Jenkinsfile](applications/nginx/fluxv1/Jenkinsfile)
  * [localhost:9005](http://localhost:9005) (Staging)
  * [localhost:9006](http://localhost:9006) (Production)

##### PetClinic via Flux V2

* [Jenkinsfile](applications/petclinic/fluxv2/plain-k8s/Jenkinsfile)
  * [localhost:9010](http://localhost:9010) (Staging)
  * [localhost:9011](http://localhost:9011) (Production) 
  
##### PetClinic via ArgoCD

* [Jenkinsfile](applications/petclinic/argocd/plain-k8s/Jenkinsfile)
  * [localhost:9020](http://localhost:9020) (Staging)
  * [localhost:9021](http://localhost:9021) (Production) 

## Remove apps from cluster

[`scripts/destroy.sh`](scripts/destroy.sh)

# Options

## Multiple stages
##### This feature is currently only useable for the plain petclinic with fluxv1

You can add additional stages in this [Jenkinsfile](applications/petclinic/fluxv1/plain-k8s/Jenkinsfile) for 
the plain-k8s petclinic version with fluxv1.

Look for the `gitopsConfig` map and edit the following entry:

```
stages: [
  staging: [ deployDirectly: true ],
  production: [ deployDirectly: false ],
  qa: [ ]
]
```

Just add another stage and define its deploy behaviour by setting `deployDirectly` to `true` or `false`.  
The default is `false` so you can leave it empty like `qa: [ ]`.  
  
If set to `true` the changes will deploy automatically when pushed to the gitops repository.  
If set to `false` a pull request is created.

After adding a new stage you need to also create k8s-files in the corresponding folder.  
So for the stage `qa` there have to be k8s-files in the following folder [`applications/petclinic/fluxv1/plain-k8s/k8s/qa`](applications/petclinic/fluxv1/plain-k8s/k8s/qa)
