# gitops-playground

Reproducible infrastructure to showcase GitOps workflows with Kubernetes.

In fact, this rolls out a complete DevOps stack with different features including
* GitOps (with different controllers to choose from: Argo CD and Flux v2),
* example applications and CI-pipelines (using Jenkins and the [gitops-build-lib](https://github.com/cloudogu/gitops-build-lib)),
* Notifications/Alerts (using Mailhog for demo purposes)
* Monitoring (using Prometheus and Grafana),
* Secrets management (using Vault and external secrets operator).

The gitops-playground is derived from our experiences in [consulting](https://cloudogu.com/en/consulting/?mtm_campaign=gitops-playground&mtm_kwd=consulting&mtm_source=github&mtm_medium=link),
operating the [myCloudogu platform](https://my.cloudogu.com/) and is used in our [GitOps trainings for both Flux and ArgoCD](https://platform.cloudogu.com/en/trainings/gitops-continuous-operations/?mtm_campaign=gitops-playground&mtm_kwd=training&mtm_source=github&mtm_medium=link).  
For questions or suggestions you are welcome to join us at our myCloudogu [community forum](https://community.cloudogu.com/t/introducing-the-gitops-playground/107).

[![Discuss it on myCloudogu](https://static.cloudogu.com/static/images/discuss-it.png)](https://community.cloudogu.com/t/introducing-the-gitops-playground/107)

| Playground features                                                                                                                                                               | Installation                                                                                                                                                              |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ![Playground features](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/plantuml-src/gitops-playground-features.puml&fmt=svg) | ![Installation](https://user-images.githubusercontent.com/1824962/215206261-fbae92fc-e73c-4977-99e3-858769e73c53.png) |

# TLDR;

You can run a local k8s cluster with the GitOps playground installed with only one command (on Linux)

```shell
bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) \
  && sleep 2 && docker run --rm -it --pull=always -u $(id -u) \
    -v ~/.k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd
```

This command will also print URLs of the [applications](#applications) inside the cluster to get you started.

We recommend running this command as an unprivileged user, that is inside the [docker group](https://docs.docker.com/engine/install/linux-postinstall/#manage-docker-as-a-non-root-user).

# Table of contents

<!-- Update with `doctoc --notitle README.md.md --maxlevel 4`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->

- [What is the GitOps Playground?](#what-is-the-gitops-playground)
- [Installation](#installation)
    - [Overview](#overview)
    - [Create Cluster](#create-cluster)
    - [Apply playground](#apply-playground)
        - [Apply via Docker (local cluster)](#apply-via-docker-local-cluster)
        - [Apply via kubectl (remote cluster)](#apply-via-kubectl-remote-cluster)
        - [Additional parameters](#additional-parameters)
    - [Remove playground](#remove-playground)
- [Stack](#stack)
    - [Credentials](#credentials)
    - [Argo CD](#argo-cd)
    - [Flux](#flux)
    - [Jenkins](#jenkins)
    - [SCM-Manager](#scm-manager)
    - [Monitoring tools](#monitoring-tools)
    - [Secrets Management Tools](#secrets-management-tools)
        - [dev mode](#dev-mode)
        - [prod mode](#prod-mode)
        - [Example app](#example-app)
    - [Example Applications](#example-applications)
        - [Flux V2](#flux-v2)
        - [Argo CD](#argo-cd-1)
- [Development](#development)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## What is the GitOps Playground?

The GitOps Playground provides a reproducible environment for setting up a GitOps-Stack.
It provides an image for automatically setting up a Kubernetes Cluster including CI-server (Jenkins),
source code management (SCM-Manager), Monitoring and Alerting (Prometheus and Grafana), Secrets Management (Hashicorop
Vault and External Secrets Operator) and of course GitOps operators: here you can choose between Flux V2 and Argo CD.

The playground also deploys a number of [example applications](#example-applications).

The GitOps Playground lowers the barriers for getting your hands on GitOps. No need to read lots of books and operator
docs, getting familiar with CLIs, ponder about GitOps Repository folder structures and staging, etc.
The GitOps Playground is a pre-configured environment to see GitOps in motion, including more advanced use cases like
notifications, monitoring and secrets management.

We aim to be compatible with various environments, e.g. OpenShift and in an air-gapped network.
The support for these is *work in progress*.

## Installation

There a several options for running the GitOps playground

* on a local k3d cluster  
  __NOTE: Currently runs only on linux!__  
  Running on Windows or Mac is possible in general, but we would need to bind all needed ports to k3d container.  
  See our [POC](https://github.com/cloudogu/gitops-playground/commit/d11f1cf77cc58fdc2b768202f9447eab31770f75).
  Let us know if this feature is of interest to you.
* on a remote k8s cluster
* each with the option
    * to use an external Jenkins, SCM-Manager and registry
      (this can be run in production, e.g. with a [Cloudogu Ecosystem](https://cloudogu.com/en/ecosystem/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link)) or
    * to run everything inside the cluster (for demo only)

The diagrams below show an overview of the playground's architecture and three scenarios for running the playground.
For a simpler overview including all optional features such as monitoring and secrets management see intro at the very top.

Note that running Jenkins inside the cluster is meant for demo purposes only. The third graphic shows our production
scenario with the Cloudogu EcoSystem (CES). Here better security and build performance is achieved using ephemeral
Jenkins build agents spawned in the cloud.

### Overview
| Demo on local machine                                                                                                                                                                          | Demo on remote cluster                                                                                                                                                                                 | Production environment with CES |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|
| ![Playground on local machine](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/plantuml-src/gitops-playground.puml&fmt=svg) | ![Playground on remote cluster](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/plantuml-src/gitops-playground-remote.puml&fmt=svg) | ![A possible production environment](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/plantuml-src/production-setting.puml&fmt=svg) |

### Create Cluster

If you don't have a demo cluster at hand we provide scripts to create either

* a local k3d cluster (see [docs](docs/k3d.md) or [script](scripts/init-cluster.sh) for more details):
  ```shell
  bash <(curl -s \
    https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh)
  ```
* a remote k8s cluster on Google Kubernetes Engine (e.g. via Terraform, see our [docs](docs/gke.md)),
* or almost any k8s cluster.  
  Note that if you want to deploy Jenkins inside the cluster, Docker is required as container runtime.

### Apply playground

You can apply the playground to your cluster using our container image `ghcr.io/cloudogu/gitops-playground`.  
On success, the container prints a little intro on how to get started with the GitOps playground.

There are several options for running the container:

* For local k3d cluster, we recommend running the image as a local container via `docker`
* For remote clusters (e.g. on GKE) you can run the image inside a pod of the target cluster via `kubectl`.

All options offer the same parameters, see [below](#additional-parameters).

#### Apply via Docker (local cluster)

When connecting to k3d it is easiest to apply the playground via a local container in the host network and pass
k3d's kubeconfig.

```shell
CLUSTER_NAME=gitops-playground
docker pull ghcr.io/cloudogu/gitops-playground
docker run --rm -it -u $(id -u) \
  -v ~/.k3d/kubeconfig-${CLUSTER_NAME}.yaml:/home/.kube/config \
  --net=host \
  ghcr.io/cloudogu/gitops-playground # additional parameters go here
``` 

Note:
* `docker pull` in advance makes sure you have the newest image, even if you ran this command before.  
  Of course, you could also specify a specific [version of the image](https://github.com/cloudogu/gitops-playground/pkgs/container/gitops-playground/versions).
* Using the host network makes it possible to determine `localhost` and to use k3d's kubeconfig without altering, as it
  access the API server via a port bound to localhost.
* We run as the local user in order to avoid file permission issues with the `kubeconfig-${CLUSTER_NAME}.yaml.`
* If you experience issues and want to access the full log files, use the following command while the container is running:

```bash
docker exec -it \
  $(docker ps -q  --filter ancestor=ghcr.io/cloudogu/gitops-playground) \
  bash -c -- 'tail -f  -n +1 /tmp/playground-log-*'
```

#### Apply via kubectl (remote cluster)

For remote clusters it is easiest to apply the playground via kubectl.
You can find info on how to install kubectl [here](https://v1-25.docs.kubernetes.io/docs/tasks/tools/#kubectl).

```shell
# Create a temporary ServiceAccount and authorize via RBAC.
# This is needed to install CRDs, etc.
kubectl create serviceaccount gitops-playground-job-executer -n default
kubectl create clusterrolebinding gitops-playground-job-executer \
  --clusterrole=cluster-admin \
  --serviceaccount=default:gitops-playground-job-executer

# Then start apply the playground with the following command
# The --remote parameter exposes Jenkins, SCMM and argo on well-known ports 
# for example, so you don't have to remember the individual ports
kubectl run gitops-playground -i --tty --restart=Never \
  --overrides='{ "spec": { "serviceAccount": "gitops-playground-job-executer" } }' \
  --image ghcr.io/cloudogu/gitops-playground \
  -- --yes --remote # additional parameters go here

# If everything succeeded, remove the objects
kubectl delete clusterrolebinding/gitops-playground-job-executer \
  sa/gitops-playground-job-executer pods/gitops-playground -n default  
```

In general `docker run` should work here as well. But GKE, for example, uses gcloud and python in their kubeconfig.
Running inside the cluster avoids these kinds of issues.

#### Additional parameters

The following describes more parameters and use cases.

You can get a full list of all options like so:

```shell
docker run --rm ghcr.io/cloudogu/gitops-playground --help
```

##### Deploy GitOps operators

* `--argocd` - deploy Argo CD GitOps operator

> ⚠️ **Note** that switching between operators is not supported.  
That is, expect errors (for example with cluster-resources) if you apply the playground once with Argo CD and the next
time without it. We recommend resetting the cluster with `init-cluster.sh` beforehand.

##### Deploy with local Cloudogu Ecosystem

See our [Quickstart Guide](https://cloudogu.com/en/ecosystem/quick-start-guide/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link) on how to set up the instance.  
Then set the following parameters.

```shell
# Note: 
# * In this case --password only sets the Argo CD admin password (Jenkins and 
#    SCMM are external)
# * Insecure is needed, because the local instance will not have a valid cert
--jenkins-url=https://192.168.56.2/jenkins \ 
--scmm-url=https://192.168.56.2/scm \
--jenkins-username=admin \
--jenkins-password=yourpassword \
--scmm-username=admin \
--scmm-password=yourpassword \
--password=yourpassword \
--insecure
```

##### Deploy with productive Cloudogu Ecosystem and GCR

Using Google Container Registry (GCR) fits well with our cluster creation example via Terraform on Google Kubernetes Engine
(GKE), see our [docs](docs/gke.md).

Note that you can get a free CES demo instance set up with a Kubernetes Cluster as GitOps Playground
[here](https://cloudogu.com/en/ecosystem/demo-appointment/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link).

```shell
# Note: In this case --password only sets the Argo CD admin password (Jenkins 
# and SCMM are external) 
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

##### Override default images 

###### gitops-build-lib

Images used by the gitops-build-lib are set in the `gitopsConfig` in each `Jenkinsfile` of an application like that:

```
def gitopsConfig = [
    ...
    buildImages          : [
            helm: 'ghcr.io/cloudogu/helm:3.10.3-1',
            kubectl: 'lachlanevenson/k8s-kubectl:v1.25.4',
            kubeval: 'ghcr.io/cloudogu/helm:3.10.3-1',
            helmKubeval: 'ghcr.io/cloudogu/helm:3.10.3-1',
            yamllint: 'cytopia/yamllint:1.25-0.7'
    ],...
```

To override each image in all the applications you can use following parameters:

* `--kubectl-image someRegistry/someImage:1.0.0`
* `--helm-image someRegistry/someImage:1.0.0`
* `--kubeval-image someRegistry/someImage:1.0.0`
* `--helmkubeval-image someRegistry/someImage:1.0.0`
* `--yamllint-image someRegistry/someImage:1.0.0`

###### Tools and Exercises

Images used by various tools and exercises can be configured using the following parameters:

* `--grafana-image someRegistry/someImage:1.0.0`
* `--external-secrets-image someRegistry/someImage:1.0.0`
* `--external-secrets-certcontroller-image someRegistry/someImage:1.0.0`
* `--external-secrets-webhook-image someRegistry/someImage:1.0.0`
* `--vault-image someRegistry/someImage:1.0.0`
* `--nginx-image someRegistry/someImage:1.0.0`

Note that specifying a tag is mandatory.

##### Argo CD-Notifications

If you are using a remote cluster you can set the `--argocd-url` parameter so that argocd-notification messages have a
link to the corresponding application.

##### Monitoring

Set the parameter `--monitoring` to enable deployment of monitoring and alerting tools like prometheus, grafana and mailhog.

See [Monitoring tools](#monitoring-tools) for details.

##### Secrets Management

Set the parameter `--vault=[dev|prod]` to enable deployment of secret management tools hashicorp vault and external
secrets operator.
See [Secrets management tools](#secrets-managment-tools) for details.

### Remove playground

For k3d, you can just `k3d cluster delete gitops-playground`. This will delete the whole cluster.
Right now, there is no way to remove the playground from a cluster completely. We are planning to implement this, though.

## Stack

As described [above](#what-is-the-gitops-playground) the GitOps playground comes with a number of applications. Some of
them can be accessed via web.
* Jenkins
* SCM-Manager
* Argo CD
* Prometheus/Grafana
* Vault
* Example applications for each GitOps operator, some with staging and production environments.

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
You could use this conveniently like so:
```shell
bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/get-remote-url) \
  jenkins default
```

You can open the application in the browser right away, like so for example:

```shell
xdg-open $(bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/get-remote-url) \
   jenkins default)
```

### Credentials

If deployed within the cluster, all applications can be accessed via: `admin/admin`

Note that you can change (and should for a remote cluster!) the password with the `--password` argument.
There also is a `--username` parameter, which is ignored for argocd. That is, for now argos username ist always `admin`.

### Argo CD

Argo CD's web UI is available at

* http://localhost:9092 (k3d)
* `scripts/get-remote-url argocd-server argocd` (remote k8s)

Argo CD is installed in a production-ready way, that allows for operating Argo CD with Argo CD, using GitOps and
providing a [repo per team pattern](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#repo-per-team).

When installing the GitOps playground, the following steps are performed to bootstrap Argo CD:

* The following repos are created and initialized:
    * `argocd` (management and config of Argo CD itself),
    * `example-apps` (example for a developer/application team's GitOps repo) and
    * `cluster-resources` (example for a cluster admin or infra/platform team's repo; see below for details)
* Argo CD is installed imperatively via a helm chart.
* Two resources are applied imperatively to the cluster: an `AppProject` called `argocd` and an `Application` called
  `bootstrap`. These are also contained within the `argocd` repository.

From there everything is managed via GitOps. This diagram shows how it works.

![](docs/argocd-repos.svg)

1. The `bootstrap` application manages the folder `applications`, which also contains `bootstrap` itself.  
   With this, changes to the `bootstrap` application can be done via GitOps. The `bootstrap` application also deploys
   other apps ([App Of Apps pattern](https://github.com/argoproj/argo-cd/blob/v2.7.1/docs/operator-manual/cluster-bootstrapping.md?plain=1))
2. The `argocd` application manages the folder `argocd` which contains Argo CD's resources as an umbrella helm chart.  
   The [umbrella chart pattern](https://github.com/helm/helm-www/blob/d2543/content/en/docs/howto/charts_tips_and_tricks.md#complex-charts-with-many-dependencies)
   allows describing the actual values in `values.yaml` and deploying additional resources (such as secrets and
   ingresses) via the `templates` folder. The actual ArgoCD chart is declared in the `Chart.yaml`
3. The `Chart.yaml` contains the Argo CD helm chart as `dependency`. It points to a deterministic version of the Chart
   (pinned via `Chart.lock`) that is pulled from the Chart repository on the internet.  
   This mechanism can be used to upgrade Argo CD via GitOps. See the [Readme of the argocd repository](argocd/argocd/README.md)
   for details.
4. The `projects` application manages the `projects` folder, that contains the following `AppProjects`:
    * the `argocd` project, used for bootstrapping
    * the built-in `default` project (which is restricted to [eliminate threats to security](https://github.com/argoproj/argo-cd/blob/ce8825ad569bf961178606acc5f3842532148093/docs/threat_model.pdf))
    * one project per team (to implement least privilege and also notifications per team):
        * `cluster-resources` (for platform admin, needs more access to cluster) and
        * `example-apps` (for developers, needs less access to cluster)
5. The `cluster-resources` application points to the `cluster-resources` git repository (`argocd` folder), which
   has the typical folder structure of a GitOps repository (explained in the next step). This way, the platform admins
   use GitOps in the same way as their "customers" (the developers) and can provide better support.
6. The `example-apps` application points to the `example-apps` git repository (`argocd` folder again). Like the
   `cluster-resources`, it also has the typical folder structure of a GitOps repository:
    * `apps` - contains the kubernetes resources of all applications (the actual YAML)
    * `argocd` - contains Argo CD `Applications` that point to subfolders of `apps` (App Of Apps pattern, again)
    * `misc` - contains kubernetes resources, that do not belong to specific applications (namespaces, RBAC,
      resources used by multiple apps, etc.)
7. The `misc` application points to the `misc` folder
8. The `my-app-staging` application points to the `apps/my-app/staging` folder within the same repo. This provides a
   folder structure for release promotion. The `my-app-*` applications implement the [Environment per App Pattern](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#global-vs-env-per-app).
   This pattern allows each application to have its own environments, e.g. production and staging or none at all.
   Note that the actual YAML here could either be pushed manually or using the CI server.
   The [applications](#example-applications) contain examples that push config changes from the app repo to the GitOps
   repo using the CI server. This implementation mixes the [Repo per Team and Repo per App patterns](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#repository-structure)
9. The corresponding production environment is realizing using the `my-app-production` application, that points to the
   `apps/my-app/production` folder within the same repo.  
   Note that it is recommended to protect the `production` folders from manual access, if supported by the SCM of your choice.  
   Alternatively, instead of different YAMLs files as used in the diagram, these applications could be realized as
    * Two applications in the same YAML (implemented in the playground, see e.g. [`petclinic-plain.yaml`](argocd/example-apps/argocd/petclinic-plain.ftl.yaml))
    * Two application with the same name in different namespaces, when ArgoCD is enabled to search for applications
      within different namespaces (implemented in the playground, see
      [Argo CD's values.yaml](argocd/argocd/argocd/values.ftl.yaml) - `application.namespaces` setting)
    * One `ApplicationSet`, using the [`git` generator for directories](https://github.com/argoproj/argo-cd/blob/v2.7.1/docs/operator-manual/applicationset/Generators-Git.md#git-generator-directories)
      (not used in GitOps playground, yet)

To keep things simpler, the GitOps playground only uses one kubernetes cluster, effectively implementing the [Standalone](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#standalone)
pattern. However, the repo structure could also be used to serve multiple clusters, in a [Hub and Spoke](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#hub-and-spoke) pattern:
Additional clusters could either be defined in the `vaules.yaml` or as secrets via the `templates` folder.

We're also working on an optional implementation of the [namespaced](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#namespaced) pattern, using the [Argo CD operator](https://github.com/argoproj-labs/argocd-operator).

#### Why not use argocd-autopilot?

And advanced question: Why does the GitOps playground not use the [**argocd-autopilot**](https://github.com/argoproj-labs/argocd-autopilot)?

The short answer is: As of 2023-05, version 0.4.15 it looks far from ready for production.

Here is a diagram that shows how the repo structure created by autopilot looks like:

![](docs/autopilot-repo.svg)

Here are some thoughts why we deem it not a good fit for production:

* The version of ArgoCD is not pinned.
    * Instead, the `kustomization.yaml` (3️ in the diagram) points to a `base` within the autopilot repo, which in turn
      points to the `stable` branch of the Argo CD repo.
    * While it might be possible to pin the version using Kustomize, this is not the default and looks complicated.
    * A non-deterministic version calls for trouble. Upgrades of Argo CD might happen unnoticed.
    * What about breaking changes? What about disaster recovery?
* The repository structure autopilot creates is more complicated (i.e. difficult to understand and maintain) than
  the one used in the playground
    * Why is the `autopilot-bootstrap` application (1️ in the diagram) not within the GitOps repo and lives only in the
      cluster?
    * The approach of an `ApplicationSet` within the `AppProject`'s yaml pointing to a `config.json` (more difficult to
      write than YAML) is difficult to grasp (4️ and 6️ in the diagram)
    * The `cluster-resources` `ApplicationSet` is a good approach to multi-cluster but again, requires writing JSON
      (4️ in the diagram).
* Projects are used to realize environments (6️ and 7️ in the diagram).  
  How would we separate teams in this [monorepo structure](https://github.com/cloudogu/gitops-patterns/tree/789d300#repository-structure)?  
  One idea would be to use multiple Argo CD instances, realising a [Standalone pattern](https://github.com/cloudogu/gitops-patterns/tree/789d30055443b6096d9018ca13cbd4234a24cc3d#operator-deployment).
  This would mean that every team would have to manage its own ArgoCD instance.  
  How could this task be delegated to a dedicated platform team? These are the questions that lead to the structure
  realized in the GitOps playground.

#### cluster-resources

The playground installs cluster-resources (like prometheus, grafana, vault, external secrets operator, etc.) via the repo  
`argocd/cluster-resources`. See [ADR](docs/architecture-decision-records.md#deploying-cluster-resources-with-argo-cd-using-inline-yaml) for more details.

When installing Argo CD *and* Flux, the tools are installed using helm imperatively, we fall back to using imperative 
helm installation as kind of neutral ground.

### Jenkins

Jenkins is available at

* http://localhost:9090 (k3d)
* `scripts/get-remote-url jenkins default` (remote k8s)

You can enable browser notifications about build results via a button in the lower right corner of Jenkins Web UI.

Note that this only works when using `localhost` or `https://`.

<img src="docs/jenkins-enable-notifications.png" alt="Enable Jenkins Notifications" width="300" >
<img src="docs/jenkins-example-notification.png" alt="Example of a Jenkins browser notifications" width="300" >

###### External Jenkins

You can set an external jenkins server via the following parameters when applying the playground.
See [parameters](#additional-parameters) for examples.

* `--jenkins-url`,
* `--jenkins-username`,
* `--jenkins-password`

Note that the [example applications](#example-applications) pipelines will only run on a Jenkins that uses agents that provide
a docker host. That is, Jenkins must be able to run e.g. `docker ps` successfully on the agent.

The user has to have the following privileges:
* install plugins
* set credentials
* create jobs
* restarting

### SCM-Manager

SCM-Manager is available at

* http://localhost:9091 (k3d)
* `scripts/get-remote-url scmm-scm-manager default` (remote k8s)

###### External SCM-Manager

You can set an external SCM-Manager via the following parameters when applying the playground.
See [Parameters](#additional-parameters) for examples.

* `--scmm-url`,
* `--scmm-username`,
* `--scmm-password`

The user on the scm has to have privileges to:
* add / edit users
* add / edit permissions
* add / edit repositories
* add / edit proxy
* install plugins

### Monitoring tools

Set the parameter `--monitoring` so the [kube-prometheus-stack](https://github.com/prometheus-operator/kube-prometheus)
via its [helm-chart](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
is being deployed including Argo CD dashboards.

This leads to the following tools to be exposed:

* Mailhog
    * http://localhost:9094 (k3d)
    * `scripts/get-remote-url mailhog monitoring` (remote k8s)
* Grafana
    * http://localhost:9095 (k3d)
    * `scripts/get-remote-url kube-prometheus-stack-grafana monitoring` (remote k8s)

Grafana can be used to query and visualize metrics via prometheus.
Prometheus is not exposed by default.

In addition, argocd-notifications is set up. Applications deployed with Argo CD now will alert via email to mailhog
the sync status failed, for example.

**Note that this only works with Argo CD so far**

### Secrets Management Tools

Via the `vault` parameter, you can deploy Hashicorp Vault and the External Secrets Operator into your GitOps playground.

With this, the whole flow from secret value in Vault to kubernetes `Secret` via External Secrets Operator can be seen in
action:

![External Secret Operator <-> Vault - flow](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/plantuml-src/External-Secret-Operator-Flow.puml&fmt=svg)

For this to work, the GitOps playground configures the whole chain in Kubernetes and vault (when [dev mode](#dev-mode) is used):

![External Secret Operator Custom Resources](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/plantuml-src/External-Secret-Operator-CRs.puml&fmt=svg)

* In k8s `namespaces` `argocd-staging` and `argocd-production`:
    * Creates `SecretStore` and `ServiceAccount` (used to authenticate with vault)
    * Creates `ExternalSecrets`
* In Vault:
    * Create secrets for staging and prod
    * Create a human user for changing the secrets
    * Authorizes the service accounts on those secrets
* Creates an [example app](#example-app) that uses the `secrets`

#### dev mode

For testing you can set the parameter `--vault=dev` to deploy vault in development mode. This will lead to
* vault being transient, i.e. all changes during runtime are not persisted. Meaning a restart will reset to default.
* Vault is initialized with some fixed secrets that are used in the example app, see below.
* Vault authorization is initialized with service accounts used in example `SecretStore`s for external secrets operator
* Vault is initialized with the usual `admin/admin` account (can be overriden with `--username` and `--password`)

The secrets are then picked up by the `vault-backend` `SecretStore`s (connects External Secrets Operator with Vault) in
the namespace `argocd-staging` and `argocd-production` namespaces

You can reach the vault UI on
* http://localhost:8200 (k3d)
* `scripts/get-remote-url vault-ui secrets` (remote k8s)
* You can log in vie the user account mentioned above.  
  If necessary, the root token can be found on the log:
  ```shell
  kubectl logs -n secrets vault-0 | grep 'Root Token'
  ```

#### prod mode

When using `vault=prod` you'll have to initialize vault manually but on the other hand it will persist changes.

If you want the example app to work, you'll have to manually
* set up vault, unseal it and
* authorize the `vault` service accounts in `argocd-production` and `argocd-staging` namspaces. See `SecretStore`s and
  [dev-post-start.sh](system/secrets/vault/dev-post-start.sh) for an example.


#### Example app

With vault in `dev` mode and ArgoCD enabled, the example app `applications/nginx/argocd/helm-jenkins` will be deployed
in a way that exposes the vault secrets `secret/<environment>/nginx-secret` via HTTP on the URL `http://<host>/secret`,
for example `http://localhost:30024/secret`.

While exposing secrets on the web is a very bad practice, it's very good for demoing auto reload of a secret changed in
vault.

To demo this, you could
* change the [staging secret](http://localhost:8200/ui/vault/secrets/secret/edit/staging/nginx-helm-jenkins)
* Wait for the change to show on the web, e.g. like so
```shell
while ; do echo -n "$(date '+%Y-%m-%d %H:%M:%S'): " ; \
  curl http://localhost:30024/secret/ ; echo; sleep 1; done
```

This usually takes between a couple of seconds and 1-2 minutes.  
This time consists of `ExternalSecret`'s `refreshInterval`, as well as the [kubelet sync period](https://v1-25.docs.kubernetes.io/docs/concepts/configuration/configmap/#mounted-configmaps-are-updated-automatically)
(defaults to [1 Minute](https://kubernetes.io/docs/reference/config-api/kubelet-config.v1beta1/#kubelet-config-k8s-io-v1beta1-KubeletConfiguration))
+ cache propagation delay

The following video shows this demo in time-lapse:

[secrets-demo-video](https://user-images.githubusercontent.com/1824962/215204174-eadf180b-2a82-4273-8cbb-6e7c187267c6.mp4)

### Example Applications

Each GitOps operator comes with a couple of example applications that allow for experimenting with different GitOps
features.

All applications are deployed via separated application and GitOps repos:

![](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-diagrams/cdd6bb77/diagrams/gitops-with-app-repo.puml&fmt=png)

* Separation of app repo (e.g. `petclinic-plain`) and GitOps repo (e.g. `argocd/example-app`)
* Config is maintained in app repo,
* CI Server writes to GitOps repo and creates PullRequests.

The applications implement a simple staging mechanism:

* After a successful Jenkins build, the staging application will be deployed into the cluster by the GitOps operator.
* Deployment of production applications can be triggered by accepting pull requests.
* For some applications working without CI Server and committing directly to the GitOps repo is pragmatic  
  (e.g. 3rd-party-application like NGINX, like [`argocd/nginx-helm-umbrella`](argocd/example-apps/argocd/nginx-helm-umbrella.ftl.yaml))

![app-repo-vs-gitops-repo](docs/app-repo-vs-gitops-repo.svg)

Note that for ArgoCD the GitOps-related logic is implemented in the
[gitops-build-lib](https://github.com/cloudogu/gitops-build-lib) for Jenkins. See the README there for more options like
* staging,
* resource creation,
* validation (fail early / shift left).

Please note that it might take about a minute after the pull request has been accepted for the GitOps operator to start
deploying.
Alternatively you can trigger the deployment via the respective GitOps operator's CLI (flux) or UI (Argo CD)

#### Argo CD

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

##### 3rd Party app (NGINX) with helm, templated in Jenkins

[Jenkinsfile](applications/nginx/argocd/helm-jenkins/Jenkinsfile)

* Staging
    * local: [localhost:30024](http://localhost:30024)
    * remote: `scripts/get-remote-url nginx argocd-staging`
* Production
    * local: [localhost:30025](http://localhost:30025)
    * remote: `scripts/get-remote-url nginx argocd-production`

##### 3rd Party app (NGINX) with helm, using Helm dependency mechanism

* Application name: `nginx-helm-umbrella`
* local: [localhost:30026](http://localhost:30026)
* remote: `scripts/get-remote-url nginx-helm-umbrella argocd-production`

## Development

See [docs/developers.md](docs/developers.md)
