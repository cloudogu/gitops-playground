# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows with Kubernetes.  
Derived from our experiences in [consulting](https://cloudogu.com/en/consulting/) 
and operating the [myCloudogu platform](https://my.cloudogu.com/).

We are working on distilling the logic used in the example application pipelines into a reusable library for Jenkins:
[cloudogu/gitops-build-lib](https://github.com/cloudogu/gitops-build-lib).

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
        - [State](#state)
        - [Create cluster](#create-cluster)
        - [Delete Cluster](#delete-cluster)
  - [Apply apps to cluster](#apply-apps-to-cluster)
    - [Integration of external applications](#integration-of-external-applications)
  - [Applications](#applications)
    - [Jenkins](#jenkins)
      - [External Jenkins](#external-jenkins)
    - [SCM-Manager](#scm-manager)
      - [External SCM-Manager](#external-scm-manager)
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

Before continuing with the terraform steps, you have to open the `values.tfvars` file
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
terraform apply -var-file values.tfvars
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

## Apply apps to cluster

The gitops-playground can be deployed to the currently active context in kubeconfig via 
[`scripts/apply.sh`](scripts/apply.sh).

You can also just install one GitOps module like Flux V1 or ArgoCD via parameters.  
Use `./scripts/apply.sh --help` for more information.

Important options:
* `--remote` - deploy to remote cluster (not local k3s cluster), e.g. in GKE
* `--password` - change admin passwords for SCM-Manager, Jenkins and ArgoCD. Should be set with `--remote` for security 
  reasons. 
* `--argocd` - deploy only argoCD GitOps operator 
* `--fluxv1` - deploy only Flux v1 GitOps operator
* `--fluxv2` - deploy only Flux v2 GitOps operator


### Integration of external applications

We now support the usage of already existing installations for `jenkins`, `scm-manager` and `registry`.
This enables you to connect the gitops-playground to your already existing infrastructure, e.g. with your [Ecosystem](https://cloudogu.com/en/ecosystem/)!

Optional external Jenkins:
* `--jenkins-url` - The url of your external jenkins
* `--jenkins-username` - Username for the account on jenkins
* `--jenkins-password` - Password the the account on jenkins

Note: See [external jenkins](#external-jenkins) for further information about prerequisites.

Optional external SCM-Manager:
* `--scmm-url` - The url of your external scm-manager.
* `--scmm-username` - Username for the account on scm-manager.
* `--scmm-password` - Password the the account on scm-manager.

Note: See [external scm-manager](#external-scm-manager) for further information about prerequisites.


Optional external Registry:
* `--registry-url=registry` - The url of your external registry (Do not use `http://`)
* `--registry-path=public` - Optional, empty when not set
* `--registry-username=myUsername` - Optional, empty when not set
* `--registry-password=myPassword` - Optional, empty when not set

The scripts also prints a little intro on how to get started with a GitOps deployment.

## Applications

### Jenkins

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - `admin/admin`

Note: You can enable browser notifications about build results via a button in the lower right corner of Jenkins Web UI.

![Enable Jenkins Notifications](docs/jenkins-enable-notifications.png)

![Example of a Jenkins browser notifications](docs/jenkins-example-notification.png)
  

#### External Jenkins

You can set external jenkins server through this parameters:  
`jenkins-url`, `jenkins-username`, `jenkins-password`

The user has to have the following privileges: 
* install plugins
* set credentials
* create jobs
* restarting

### SCM-Manager

Find scm-manager on http://localhost:9091

Login with `admin/admin`

#### External SCM-Manager

You can set external scm-manager server through this parameters:  
`scmm-url`, `scmm-username`, `scmm-password`

The user has to have the following privileges:
* install plugins
* create and modify users
* create and modify repositories

### ArgoCD UI

Find the ArgoCD UI on http://localhost:9092 (redirects to https://localhost:9093)

Login with `admin/admin`

## Test applications deployed via GitOps

Each GitOps operator comes with a couple of demo applications that allow for experimenting with different GitOps 
features.

All applications implement a simple staging mechanism:

After a successful Jenkins build, the staging application will be deployed into the cluster.
The production applications can be deployed by accepting Pull Requests.

Please note that it might take about 1 Minute after the PullRequest has been accepted for the GitOps operator to start
deploying.

The URLs of the applications depend on the environment the playground is deployed to.
The following lists all application and how to find out their respective URLs for a GitOps playground being deployed to 
local or remote cluster.

For remote clusters you need the external IP, no need to specify the port (everything running on port 80).
Basiscally, you can get the IP Adress as follows: 

```shell
kubectl -n "${namespace}" get svc "${serviceName}" --template="{{range .status.loadBalancer.ingress}}{{.ip}}{{end}}"
```

There is also a convenience script `scripts/get-remote-url`. The script waits, if externalIP is not present, yet.

You can open the application in the browser right away, like so for example:

```shell
xdg-open $(scripts/get-remote-url default jenkins)
```

##### PetClinic via Flux V1

* [Jenkinsfile](applications/petclinic/fluxv1/plain-k8s/Jenkinsfile) for plain `k8s` deployment
  * Staging: 
    * local: [localhost:30001](http://localhost:30001) 
    * remote: `scripts/get-remote-url spring-petclinic-plain fluxv1-staging`
  * Production:  
    * local: [localhost:30002](http://localhost:30002)
    * remote: `scripts/get-remote-url spring-petclinic-plain fluxv1-production`
  * QA (example for a 3rd stage)
    * local: [localhost:30003](http://localhost:30003) 
    * remote: `scripts/get-remote-url spring-petclinic-plain fluxv1-qa`

* [Jenkinsfile](applications/petclinic/fluxv1/helm/Jenkinsfile) for `helm` deployment
  * Staging
    * local: [localhost:30004](http://localhost:30004)
    * remote: `scripts/get-remote-url spring-petclinic-helm-springboot fluxv1-staging`
  * Production
    * [localhost:30005](http://localhost:30005) 
    * remote: `scripts/get-remote-url spring-petclinic-helm-springboot fluxv1-production`

##### 3rd Party app (NGINX) via Flux V1

TODO not reachable via 30006!

* [Jenkinsfile](applications/nginx/fluxv1/Jenkinsfile)
  * Staging
    * local: [localhost:30006](http://localhost:30006)
    * remote: `scripts/get-remote-url nginx fluxv1-staging`
  * Production
    * local: [localhost:30007](http://localhost:30007)
    * remote: `scripts/get-remote-url nginx fluxv1-production`

##### PetClinic via Flux V2

* [Jenkinsfile](applications/petclinic/fluxv2/plain-k8s/Jenkinsfile)
    * Staging
        * local: [localhost:30010](http://localhost:30010)
        * remote: `scripts/get-remote-url spring-petclinic-plain fluxv2-staging`
    * Production
        * local: [localhost:30011](http://localhost:30011) 
        * remote: `scripts/get-remote-url spring-petclinic-plain fluxv2-production`
  
##### PetClinic via ArgoCD

* [Jenkinsfile](applications/petclinic/argocd/plain-k8s/Jenkinsfile)
    * Staging
        * local [localhost:30020](http://localhost:30020)
        * remote: `scripts/get-remote-url spring-petclinic-plain argocd-staging`
    * Remote
        * local [localhost:30021](http://localhost:30021)
        * remote: `scripts/get-remote-url spring-petclinic-plain argocd-production`

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