# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows with Kubernetes.  
Derived from our experiences in [consulting](https://cloudogu.com/en/consulting/?mtm_campaign=gitops-playground&mtm_kwd=consulting&mtm_source=github&mtm_medium=link) 
and operating the [myCloudogu platform](https://my.cloudogu.com/).

We are working on distilling the logic used in the example application pipelines into a reusable library for Jenkins:
[cloudogu/gitops-build-lib](https://github.com/cloudogu/gitops-build-lib).

# Table of contents

<!-- Update with `doctoc --notitle README.md --maxlevel 5`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->

- [What is the GitOps Playground?](#what-is-the-gitops-playground)
- [Installation](#installation)
  - [Create Cluster](#create-cluster)
  - [Apply apps to cluster](#apply-apps-to-cluster)
  - [Remove apps from cluster](#remove-apps-from-cluster)
- [Applications](#applications)
  - [Credentials](#credentials)
  - [Jenkins](#jenkins)
  - [SCM-Manager](#scm-manager)
  - [ArgoCD UI](#argocd-ui)
  - [Demo applications](#demo-applications)
    - [Flux V1](#flux-v1)
      - [PetClinic with plain k8s resources](#petclinic-with-plain-k8s-resources)
      - [PetClinic with helm](#petclinic-with-helm)
      - [3rd Party app (NGINX) with helm](#3rd-party-app-nginx-with-helm)
    - [Flux V2](#flux-v2)
      - [PetClinic with plain k8s resources](#petclinic-with-plain-k8s-resources-1)
    - [ArgoCD](#argocd)
      - [PetClinic with plain k8s resources](#petclinic-with-plain-k8s-resources-2)
      - [PetClinic with helm](#petclinic-with-helm-1)
      - [3rd Party app (NGINX) with helm](#3rd-party-app-nginx-with-helm-1)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## What is the GitOps Playground?

The GitOps Playground provides an reproducible environment for trying out GitOps. Is consists of Infra As Code and 
scripts for automatically setting up a Kubernetes Cluster CI-server (Jenkins), source code management (SCM-Manager) 
and several GitOps operators (Flux V1, Flux V2, ArgoCD). 
CI-Server, SCM and operators are pre-configured with a number of [demo applications](#demo-applications).

The GitOps Playground lowers the barriers for getting your hands on GitOps. No need to read lots of books and operator
docs, getting familiar with CLIs, ponder about GitOps Repository folder structures and staging, etc.
The GitOps Playground is a pre-configured environment to see GitOps in motion.  

## Installation

There a several options for running the GitOps playground

* on a local k3d cluster
* on a remote k8s cluster
* each with the option 
  * to use an external Jenkins, SCM-Manager and registry 
    (this can be run in production, e.g. with a [Cloudogu Ecosystem](https://cloudogu.com/en/ecosystem/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link)) or  
  * to run everything inside the cluster (for demo only)  

The diagrams bellow show an overview of the playground's architecture and three scenarios for running the playground. 

Note that running Jenkins inside the cluster is meant for demo purposes only. The third graphic shows our production 
scenario with the Cloudogu EcoSystem (CES). Here better security and build performance is achieved using ephemeral 
Jenkins build agents spawned in the cloud.

| Demo on local machine | Demo on remote cluster | Production environment with CES |
|--------------------|--------------------|--------------------|
|![Playground on local machine](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/gitops-playground.puml&fmt=svg) | ![Playground on remote cluster](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/gitops-playground-remote.puml&fmt=svg)  | ![A possible production environment](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/production-setting.puml&fmt=svg) | 

### Create Cluster

If you don't have a demo cluster at hand we provide scripts to create either 

* a local k3d cluster ([see docs](docs/k3s.md)) or
* a remote k8s cluster on Google Kubernetes Engine via terraform ([see docs](docs/gke.md)).
* But most k8s cluster should work (tested with k8s 1.18+).  
  Note that if you want to deploy Jenkins inside the cluster, Docker is required as container runtime.

### Apply apps to cluster

The GitOps Playground can be deployed to the currently active kube context via `scripts/apply.sh`.
So clone the repo and execute the script on your local linux computer or VM.
It requires the following binaries:
* curl,
* jq,
* htpasswd,
* envsubst,
* kubectl,
* helm.

The scripts also prints a little intro on how to get started with a GitOps deployment.

The scripts provides a number of options: See `./scripts/apply.sh --help` for more information.

Examples:
* Start on local k3s cluster
```shell
scripts/apply.sh
```
* Start on a remote k8s cluster
```shell
scripts/apply.sh --remote
```
* Start with local Cloudogu Ecosystem.  
  See our [Quickstart Guide](https://cloudogu.com/en/ecosystem/quick-start-guide/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link) on how to set up the instance.
```shell
# In this case --password only sets the argocd admin password (Jenkins and SCMM are external) 
/scripts/apply.sh \
--jenkins-url=https://192.168.56.2/jenkins \ 
--scmm-url=https://192.168.56.2/scm \
--jenkins-username=admin \
--jenkins-password=yourpassword \
--scmm-username=admin \
--scmm-password=yourpassword \
--password=yourpassword \
--insecure
```
* Start with productive Cloudogu Ecosystem and Google Container Registry.  
  Note that you can get a free CES demo instance set up with a Kubernetes Cluster as GitOps Playground [here](https://cloudogu.com/en/ecosystem/demo-appointment/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link).   
```shell
# In this case --password only sets the argocd admin password (Jenkins and SCMM are external) 
/scripts/apply.sh \
--jenkins-url=https://your-ecosystem.cloudogu.net/jenkins \ 
--scmm-url=https://your-ecosystem.cloudogu.net/scm \
--jenkins-username=admin \
--jenkins-password=yourpassword \
--scmm-username=admin \
--scmm-password=yourpassword \
--password=yourpassword \
--registry-url=eu.gcr.io \
--registry-path=yourproject \
--registry-username=_json_key \ 
--registry-password="$( cat account.json | sed 's/"/\\"/g' )" 
```

Some more options:
* `--argocd` - deploy only argoCD GitOps operator 
* `--fluxv1` - deploy only Flux v1 GitOps operator
* `--fluxv2` - deploy only Flux v2 GitOps operator

#### Override default images used in the gitops-build-lib

Images used by the gitops-build-lib are set in the `gitopsConfig` in each `Jenkinsfile` of an application like that:
```
def gitopsConfig = [
    ...
    buildImages          : [
            helm: 'ghcr.io/cloudogu/helm:3.5.4-1',
            kubectl: 'lachlanevenson/k8s-kubectl:v1.19.3',
            kubeval: 'ghcr.io/cloudogu/helm:3.5.4-1',
            helmKubeval: 'ghcr.io/cloudogu/helm:3.5.4-1',
            yamllint: 'cytopia/yamllint:1.25-0.7'
    ],...
```
To override each image in all the applications you can use following parameters:
* `--kubectl-image someRegistry/someImage:1.0.0`
* `--helm-image someRegistry/someImage:1.0.0`
* `--kubeval-image someRegistry/someImage:1.0.0`
* `--helmkubeval-image someRegistry/someImage:1.0.0`
* `--yamllint-image someRegistry/someImage:1.0.0`

### Remove apps from cluster

```shell
./scripts/destroy.sh
```

## Applications

As described [above](#what-is-the-gitops-playground) the GitOps playground comes with a number of applications. Some of
them can be accessed via web.
* Jenkins
* SCM-Manager
* ArgoCD
* Demo applications for each GitOps operator, each with staging and production instance.

The URLs of the applications depend on the environment the playground is deployed to.
The following lists all application and how to find out their respective URLs for a GitOps playground being deployed to
local or remote cluster.

For remote clusters you need the external IP, no need to specify the port (everything running on port 80).
Basically, you can get the IP address as follows:

```shell
kubectl -n "${namespace}" get svc "${serviceName}" \
  --template="{{range .status.loadBalancer.ingress}}{{.ip}}{{end}}"
```

There is also a convenience script `scripts/get-remote-url`. The script waits, if externalIP is not present, yet.

You can open the application in the browser right away, like so for example:

```shell
xdg-open $(scripts/get-remote-url default jenkins)
```
### Credentials

If deployed within the cluster Jenkins, SCM-Manager and ArgoCD can be accessed via: `admin/admin`

Note that you can change (an should for a remote cluster!) the password with `apply.sh`'s `--password` argument.

### Jenkins

Jenkins is available at

* http://localhost:9090 (k3s)
* `scripts/get-remote-url jenkins default` (remote k8s) 

Note: You can enable browser notifications about build results via a button in the lower right corner of Jenkins Web UI.

![Enable Jenkins Notifications](docs/jenkins-enable-notifications.png)

![Example of a Jenkins browser notifications](docs/jenkins-example-notification.png)

###### External Jenkins

You can set external jenkins server through this arguments when calling `apply.sh`:  
`jenkins-url`, `jenkins-username`, `jenkins-password`

Note that the [demo applications](#demo-applications) Pipelines will only run on a Jenkins that uses agents that provide
a docker host. That is, Jenkins must be able to run e.g. `docker ps` successfully on the agent. 

The user has to have the following privileges: 
* install plugins
* set credentials
* create jobs
* restarting

### SCM-Manager

SCM-Manager is available at

* http://localhost:9091 (k3s)
* `scripts/get-remote-url scmm-scm-manager default` (remote k8s)

###### External SCM-Manager

You can set external SCM-Manager server through this arguments when calling `apply.sh`:  
`scmm-url`, `scmm-username`, `scmm-password`

The user on the scm has to have privileges to:
* add / edit users
* add / edit permissions
* add / edit repositories
* add / edit proxy
* install plugins

### ArgoCD UI

ArgoCD's web UI is available at

* http://localhost:9092 (k3s)
* `scripts/get-remote-url argocd-server argocd` (remote k8s)

### Demo applications

Each GitOps operator comes with a couple of demo applications that allow for experimenting with different GitOps 
features.

All applications are deployed via separated application and GitOps repos: 
![](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-diagrams/cdd6bb77/diagrams/gitops-with-app-repo.puml&fmt=svg)

* Separation of app repo and GitOps repo
* Infrastructure as Code is maintained  in app repo,
* CI Server writes to GitOps repo and creates PullRequests.

The applications implement a simple staging mechanism:

* After a successful Jenkins build, the staging application will be deployed into the cluster.
* The production applications can be deployed by accepting Pull Requests.

Note that we are working on moving the GitOps-related logic into a
[gitops-build-lib](https://github.com/cloudogu/gitops-build-lib) for Jenkins. See the README there for more options like
* staging,
* resource creation,
* validation (fail early).

Please note that it might take about 1 Minute after the PullRequest has been accepted for the GitOps operator to start
deploying.
Alternatively you can trigger the deployment via the respective GitOps operator's CLI (flux) or UI (argo CD)

#### Flux V1

##### PetClinic with plain k8s resources 

[Jenkinsfile](applications/petclinic/fluxv1/plain-k8s/Jenkinsfile) for plain `k8s` deployment

* Staging: 
  * local: [localhost:30001](http://localhost:30001) 
  * remote: `scripts/get-remote-url spring-petclinic-plain fluxv1-staging`
* Production:  
  * local: [localhost:30002](http://localhost:30002)
  * remote: `scripts/get-remote-url spring-petclinic-plain fluxv1-production`
* QA (example for a 3rd stage)
  * local: [localhost:30003](http://localhost:30003) 
  * remote: `scripts/get-remote-url spring-petclinic-plain fluxv1-qa`

##### PetClinic with helm

[Jenkinsfile](applications/petclinic/fluxv1/helm/Jenkinsfile) for `helm` deployment

* Staging
  * local: [localhost:30004](http://localhost:30004)
  * remote: `scripts/get-remote-url spring-petclinic-helm-springboot fluxv1-staging`
* Production
  * local: [localhost:30005](http://localhost:30005) 
  * remote: `scripts/get-remote-url spring-petclinic-helm-springboot fluxv1-production`

##### 3rd Party app (NGINX) with helm

[Jenkinsfile](applications/nginx/fluxv1/Jenkinsfile)

* Staging
  * local: [localhost:30006](http://localhost:30006)
  * remote: `scripts/get-remote-url nginx fluxv1-staging`
* Production
  * local: [localhost:30007](http://localhost:30007)
  * remote: `scripts/get-remote-url nginx fluxv1-production`

#### Flux V2

##### PetClinic with plain k8s resources 

[Jenkinsfile](applications/petclinic/fluxv2/plain-k8s/Jenkinsfile)

* Staging
  * local: [localhost:30010](http://localhost:30010)
  * remote: `scripts/get-remote-url spring-petclinic-plain fluxv2-staging`
* Production
  * local: [localhost:30011](http://localhost:30011) 
  * remote: `scripts/get-remote-url spring-petclinic-plain fluxv2-production`

#### ArgoCD

##### PetClinic with plain k8s resources

[Jenkinsfile](applications/petclinic/argocd/plain-k8s/Jenkinsfile) for `plain` deployment

* Staging
  * local [localhost:30020](http://localhost:30020)
  * remote: `scripts/get-remote-url spring-petclinic-plain argocd-staging`
* Production
  * local [localhost:30021](http://localhost:30021)
  * remote: `scripts/get-remote-url spring-petclinic-plain argocd-production`

##### PetClinic with helm

[Jenkinsfile](applications/petclinic/argocd/helm/Jenkinsfile) for `helm` deployment

* Staging
  * local [localhost:30022](http://localhost:30022)
  * remote: `scripts/get-remote-url spring-petclinic-helm argocd-staging`
* Production
  * local [localhost:30023](http://localhost:30023)
  * remote: `scripts/get-remote-url spring-petclinic-helm argocd-production`

##### 3rd Party app (NGINX) with helm

[Jenkinsfile](applications/nginx/argocd/Jenkinsfile)

* Staging
  * local: [localhost:30006](http://localhost:30024)
  * remote: `scripts/get-remote-url nginx fluxv1-staging`
* Production
  * local: [localhost:30007](http://localhost:30025)
  * remote: `scripts/get-remote-url nginx fluxv1-production`