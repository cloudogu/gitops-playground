# gitops-playground

Creates a complete GitOps-based operational stack that can be used as an internal developer platform (IDP) on your 
Kubernetes clusters:

* Deployment: GitOps via Argo CD with a ready-to-use [repo structure](#argo-cd)
* Monitoring: [Prometheus and Grafana](#monitoring-tools)
* Secrets Management:  [Vault and External Secrets Operator](#secrets-management-tools)
* Notifications/Alerts: Grafana and ArgoCD can be predefined with either an external mailserver or [MailHog](https://github.com/mailhog/MailHog) for demo purposes.
* Pipelines: Example applications using [Jenkins](#jenkins) with the [gitops-build-lib](https://github.com/cloudogu/gitops-build-lib) and [SCM-Manager](#scm-manager)
* Ingress Controller: [ingress-nginx](https://github.com/kubernetes/ingress-nginx/)
* Certificate Management: [cert-manager](#certificate-management)
* [Content Loader](docs/content-loader/content-loader.md): Completely customize what is pushed to Git during installation.
  This allows for adding your own end-user or IDP apps, creating repos, adding Argo CD tenants, etc.
* Runs on: 
  * local cluster (try it [with only one command](#tldr)), 
  * in the public cloud, 
  * and even air-gapped environments.

The gitops-playground is derived from our experiences in [consulting](https://platform.cloudogu.com/consulting/kubernetes-und-gitops/?mtm_campaign=gitops-playground&mtm_kwd=consulting&mtm_source=github&mtm_medium=link),
operating our internal developer platform (IDP) at [Cloudogu](https://cloudogu.com/?mtm_campaign=gitops-playground&mtm_kwd=cloudogu&mtm_source=github&mtm_medium=link) and is used in our [GitOps trainings](https://platform.cloudogu.com/en/trainings/gitops-continuous-operations/?mtm_campaign=gitops-playground&mtm_kwd=training&mtm_source=github&mtm_medium=link).  

[![Playground features](docs/gitops-playground-features.drawio.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/gitops-playground-features.drawio.svg "View full size")

## TL;DR

You can try the GitOps Playground on a local Kubernetes cluster by running a single command:

```shell
bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) \
  && docker run --rm -t --pull=always -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd --ingress-nginx --base-url=http://localhost
# More IDP-features: --mailhog --monitoring --vault=dev --cert-manager
# More features for developers: --jenkins --registry --content-examples
```

Note that on some linux distros like debian do not support subdomains of localhost.
There you might have to use `--base-url=http://local.gd` (see [local ingresses](#local-ingresses)).

See the list of [applications](#applications) to get started.

We recommend running this command as an unprivileged user, that is inside the [docker group](https://docs.docker.com/engine/install/linux-postinstall/#manage-docker-as-a-non-root-user).

## Table of contents

<!-- Update with `doctoc --notitle README.md --maxlevel 5`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->

- [What is the GitOps Playground?](#what-is-the-gitops-playground)
- [Installation](#installation)
  - [Overview](#overview)
  - [Create Cluster](#create-cluster)
  - [Apply playground](#apply-playground)
    - [Apply via Docker (local cluster)](#apply-via-docker-local-cluster)
    - [Apply via kubectl (remote cluster)](#apply-via-kubectl-remote-cluster)
    - [Configuration](#configuration)
      - [Overview of all CLI and config options](#overview-of-all-cli-and-config-options)
      - [Configuration file](#configuration-file)
      - [Print all CLI parameters](#print-all-cli-parameters)
      - [Deploy Ingress Controller](#deploy-ingress-controller)
      - [Deploy Ingresses](#deploy-ingresses)
      - [Deploy GitOps operators](#deploy-gitops-operators)
      - [Deploy with local Cloudogu Ecosystem](#deploy-with-local-cloudogu-ecosystem)
      - [Deploy with productive Cloudogu Ecosystem and GCR](#deploy-with-productive-cloudogu-ecosystem-and-gcr)
      - [Override default images](#override-default-images)
      - [Argo CD-Notifications](#argo-cd-notifications)
      - [Monitoring](#monitoring)
      - [Mail server](#mail-server)
      - [MailHog](#mailhog)
      - [External Mailserver](#external-mailserver)
      - [Secrets Management](#secrets-management)
      - [Certificate Management](#certificate-management)
    - [Profiles](#profiles)
  - [Remove playground](#remove-playground)
  - [Running on Windows or Mac](#running-on-windows-or-mac)
    - [Mac and Windows WSL](#mac-and-windows-wsl)
    - [Windows Docker Desktop](#windows-docker-desktop)
- [Stack](#stack)
  - [Credentials](#credentials)
  - [Argo CD](#argo-cd)
    - [Why not use argocd-autopilot?](#why-not-use-argocd-autopilot)
    - [cluster-resources](#cluster-resources)
  - [Jenkins](#jenkins)
  - [SCMs](#scms)
    - [SCM-Manager](#scm-manager)
    - [Gitlab](#gitlab)
  - [Monitoring tools](#monitoring-tools)
  - [Secrets Management Tools](#secrets-management-tools)
    - [dev mode](#dev-mode)
    - [prod mode](#prod-mode)
    - [Example app](#example-app)
  - [Example Applications](#example-applications)
    - [PetClinic with plain k8s resources](#petclinic-with-plain-k8s-resources)
    - [PetClinic with helm](#petclinic-with-helm)
    - [3rd Party app (NGINX) with helm, templated in Jenkins](#3rd-party-app-nginx-with-helm-templated-in-jenkins)
    - [3rd Party app (NGINX) with helm, using Helm dependency mechanism](#3rd-party-app-nginx-with-helm-using-helm-dependency-mechanism)
- [Development](#development)
- [License](#license)
- [Written Offer](#written-offer)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## What is the GitOps Playground?

The GitOps Playground provides a reproducible environment for setting up a complete GitOps-based operational stack 
that can be used as an internal developer platform (IDP) on your Kubernetes clusters.
It provides an image for automatically setting up a Kubernetes Cluster including CI-server (Jenkins),
source code management (SCM-Manager), Monitoring and Alerting (Prometheus, Grafana, MailHog), Secrets Management (Hashicorp
Vault and External Secrets Operator) and of course, Argo CD as GitOps operator.

The playground also deploys a number of [example applications](#example-applications).

The GitOps Playground lowers the barriers for operating your application on Kubernetes using GitOps.
It creates a complete GitOps-based operational stack on your Kubernetes clusters.
No need to read lots of books and operator
docs, getting familiar with CLIs, ponder about GitOps Repository folder structures and promotion to different environments, etc.  
The GitOps Playground is a pre-configured environment to see GitOps in motion, including more advanced use cases like
notifications, monitoring and secret management.

In addition to creating an operational stack in production, you can run the playground locally, for learning and developing new features. 

We aim to be compatible with various environments, we even run in an air-gapped networks.

## Installation

There a several options for running the GitOps playground

* on a local k3d cluster
  Works best on Linux, but is possible on [Windows and Mac](#windows-or-mac). 
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
| Playground on local machine                                                | Production environment with Cloudogu EcoSystem                                       |
|----------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| [![Playground on local machine](docs/gitops-playground-local.drawio.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/gitops-playground-local.drawio.svg "View full size") | [![A possible production environment](docs/gitops-playground-production.drawio.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/gitops-playground-production.drawio.svg "View full size") |

### Create Cluster

You can apply the GitOps playground to 

* a local k3d cluster (see [docs](docs/k3d.md) or [script](scripts/init-cluster.sh) for more details):
  ```shell
  bash <(curl -s \
    https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh)
  ```
* a remote k8s cluster on Google Kubernetes Engine (e.g. via Terraform, see our [docs](docs/gke.md)),
* or almost any k8s cluster.  
  Note that if you want to deploy Jenkins inside the cluster, you either need Docker as container runtime or set Jenkins up to run its build on an agent that provides Docker.

For the local cluster, you can avoid hitting DockerHub's rate limiting by using a mirror via the `--docker-io-registry-mirror` parameter.

For example:

```bash
bash <(curl -s \
    https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) --docker-io-registry-mirror https://mirror.gcr.io
```

This parameter is passed on the containerd used by k3d. 

In addition, the Jobs run by Jenkins are using the host's Docker daemon.  
To avoid rate limits there, you might have to configure a mirror there as well.
This can be done in the `/etc/docker/daemon.json` or in the config of Docker Desktop.

For example:
```json
{
  "registry-mirrors": ["https://mirror.gcr.io"]
}
```

### Apply playground

You can apply the playground to your cluster using our container image `ghcr.io/cloudogu/gitops-playground`.  
On success, the container prints a little intro on how to get started with the GitOps playground.

There are several options for running the container:

* For local k3d cluster, we recommend running the image as a local container via `docker`
* For remote clusters (e.g. on GKE) you can run the image inside a pod of the target cluster via `kubectl`.

All options offer the same parameters, see [below](#overview-of-all-cli-and-config-options).

#### Apply via Docker (local cluster)

When connecting to k3d it is easiest to apply the playground via a local container in the host network and pass
k3d's kubeconfig.

```shell
CLUSTER_NAME=gitops-playground
docker pull ghcr.io/cloudogu/gitops-playground
docker run --rm -t -u $(id -u) \
  -v ~/.config/k3d/kubeconfig-${CLUSTER_NAME}.yaml:/home/.kube/config \
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

# Then start apply the playground with the following command:
# To access services on remote clusters, add either --remote or --ingress-nginx --base-url=$yourdomain
kubectl run gitops-playground -i --tty --restart=Never \
  --overrides='{ "spec": { "serviceAccount": "gitops-playground-job-executer" } }' \
  --image ghcr.io/cloudogu/gitops-playground \
  -- --yes --argocd # additional parameters go here. 

# If everything succeeded, remove the objects
kubectl delete clusterrolebinding/gitops-playground-job-executer \
  sa/gitops-playground-job-executer pods/gitops-playground -n default  
```

In general `docker run` should work here as well. But GKE, for example, uses gcloud and python in their kubeconfig.
Running inside the cluster avoids these kinds of issues.

#### Configuration

The following describes how to configure GOP.

You can configure GOP using CLI params, config file and/or config map.
Config file and map have the same format and offer a [schema file](https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/configuration.schema.json). 
See [here](#configuration-file).
You can also find a list of all CLI/config options [here](#overview-of-all-cli-and-config-options).

**Configuration precedence (highest to lowest):**
1. Command-line parameters
2. Configuration files (`--config-file`)
3. Config maps (`--config-map`)

That is, if you pass a param via CLI, for example, it will overwrite the corresponding value in the configuration.

##### Overview of all CLI and config options
- [Application](#application)
- [Registry](#registry)
- [Jenkins](#jenkins)
- [SCM](#scmtenant)
  - [SCMM](#scmmtenant)
  - [GITLAB](#gitlabtenant)
- [Images](#images)
- [Features](#argocd)
    - [ArgoCD](#argocd)
    - [Mail](#mail)
    - [Monitoring](#monitoring)
    - [Secrets](#secrets)
    - [Ingress Nginx](#ingress-nginx)
    - [Cert Manager](#cert-manager)
- [Content](#content)
- [Multitenant](#multitenant)
  - [SCMM](#scm-managercentral)
  - [GITLAB](#gitlabcentral)

###### Application

| CLI                      | Config                             | Default | Type     | Description                                                                   |
|--------------------------|------------------------------------|---------|----------|-------------------------------------------------------------------------------|
| `--config-file`          | -                                  | `''` | String   | Config file path                                                              |
| `--config-map`           | -                                  | `''` | String   | Config map name                                                               |
| `-d, --debug`            | `application.debug`                | - | Boolean  | Enable debug mode                                                             |
| `-x, --trace`            | `application.trace`                | - | Boolean  | Enable trace mode                                                             |
| `--output-config-file`   | `application.outputConfigFile`     | `false` | Boolean  | Output configuration file                                                     |
| `-v, --version`          | `application.versionInfoRequested` | `false` | Boolean  | Display version and license info                                              |
| `-h, --help`             | `application.usageHelpRequested`   | `false` | Boolean  | Display help message                                                          |
| `--remote`               | `application.remote`               | `false` | Boolean  | Expose services as LoadBalancers                                              |
| `--insecure`             | `application.insecure`             | `false` | Boolean  | Sets insecure-mode in cURL which skips cert validation                        |
| `--openshift`            | `application.openshift`            | `false` | Boolean  | When set, openshift specific resources and configurations are applied         |
| `--username`             | `application.username`             | `'admin'` | String   | Set initial admin username                                                    |
| `--password`             | `application.password`             | `'admin'` | String   | Set initial admin passwords                                                   |
| `-y, --yes`              | `application.yes`                  | `false` | Boolean  | Skip confirmation                                                             |
| `--name-prefix`          | `application.namePrefix`           | `''` | String   | Set name-prefix for repos, jobs, namespaces                                   |
| `--destroy`              | `application.destroy`              | `false` | Boolean  | Unroll playground                                                             |
| `--pod-resources`        | `application.podResources`         | `false` | Boolean  | Write kubernetes resource requests and limits on each pod                     |
| `--git-name`             | `application.gitName`              | `'Cloudogu'` | String   | Sets git author and committer name used for initial commits                   |
| `--git-email`            | `application.gitEmail`             | `'hello@cloudogu.com'` | String   | Sets git author and committer email used for initial commits                  |
| `--base-url`             | `application.baseUrl`              | `''` | String   | The external base url (TLD) for all tools                                     |
| `--url-separator-hyphen` | `application.urlSeparatorHyphen`   | `false` | Boolean  | Use hyphens instead of dots to separate application name from base-url        |
| `--mirror-repos`         | `application.mirrorRepos`          | `false` | Boolean  | Changes the sources of deployed tools so they work in air-gapped environments |
| `--skip-crds`            | `application.skipCrds`             | `false` | Boolean  | Skip installation of CRDs                                                     |
| `--namespace-isolation`  | `application.namespaceIsolation`   | `false` | Boolean  | Configure tools to work with given namespaces only                            |
| `--netpols`              | `application.netpols`              | `false` | Boolean  | Sets Network Policies                                                         |
| `-p, --profiles`         | `application.profile`              | `''` | String   | Sets a profile for pre-defined parameter                                      |


###### Registry

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--registry` | `registry.active` | `false` | Boolean | Installs a simple cluster-local registry for demonstration purposes. Warning: Registry does not provide authentication! |
| `--internal-registry-port` | `registry.internalPort` | `30000` | Integer | Port of registry registry. Ignored when a registry*url params are set |
| `--registry-url` | `registry.url` | `''` | String | The url of your external registry, used for pushing images |
| `--registry-path` | `registry.path` | `''` | String | Optional when registry-url is set |
| `--registry-username` | `registry.username` | `''` | String | Optional when registry-url is set |
| `--registry-password` | `registry.password` | `''` | String | Optional when registry-url is set |
| `--registry-proxy-url` | `registry.proxyUrl` | `''` | String | The url of your proxy-registry. Used in pipelines to authorize pull base images |
| `--registry-proxy-username` | `registry.proxyUsername` | `''` | String | Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets |
| `--registry-proxy-password` | `registry.proxyPassword` | `''` | String | Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets |
| `--registry-username-read-only` | `registry.readOnlyUsername` | `''` | String | Optional alternative username for registry-url with read-only permissions |
| `--registry-password-read-only` | `registry.readOnlyPassword` | `''` | String | Optional alternative password for registry-url with read-only permissions |
| `--create-image-pull-secrets` | `registry.createImagePullSecrets` | `false` | Boolean | Create image pull secrets for registry and proxy-registry for all GOP namespaces |
| - | `registry.helm.chart` | `'docker-registry'` | String | Name of the Helm chart |
| - | `registry.helm.repoURL` | `'https://helm.twun.io'` | String | Repository url from which the Helm chart should be obtained |
| - | `registry.helm.version` | `'2.2.3'` | String | The version of the Helm chart to be installed |
| - | `registry.helm.values` | `[:]` | Map | Helm values of the chart |

###### Jenkins

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--jenkins` | `jenkins.active` | `false` | Boolean | Installs Jenkins as CI server |
| `--jenkins-skip-restart` | `jenkins.skipRestart` | `false` | Boolean | Skips restarting Jenkins after plugin installation |
| `--jenkins-skip-plugins` | `jenkins.skipPlugins` | `false` | Boolean | Skips plugin installation |
| `--jenkins-url` | `jenkins.url` | `''` | String | The url of your external jenkins |
| `--jenkins-username` | `jenkins.username` | `'admin'` | String | Mandatory when jenkins-url is set |
| `--jenkins-password` | `jenkins.password` | `'admin'` | String | Mandatory when jenkins-url is set |
| `--jenkins-metrics-username` | `jenkins.metricsUsername` | `'metrics'` | String | Mandatory when jenkins-url is set and monitoring enabled |
| `--jenkins-metrics-password` | `jenkins.metricsPassword` | `'metrics'` | String | Mandatory when jenkins-url is set and monitoring enabled |
| `--maven-central-mirror` | `jenkins.mavenCentralMirror` | `''` | String | URL for maven mirror, used by applications built in Jenkins |
| `--jenkins-additional-envs` | `jenkins.additionalEnvs` | `[:]` | Map | Set additional environments to Jenkins |
| - | `jenkins.helm.chart` | `'jenkins'` | String | Name of the Helm chart |
| - | `jenkins.helm.repoURL` | `'https://charts.jenkins.io'` | String | Repository url from which the Helm chart should be obtained |
| - | `jenkins.helm.version` | `'5.8.43'` | String | The version of the Helm chart to be installed |
| - | `jenkins.helm.values` | `[:]` | Map | Helm values of the chart |

###### Scm(Tenant)

| CLI              | Config                          | Default      | Type                    | Description                                                           |
|------------------|---------------------------------|--------------|-------------------------|-----------------------------------------------------------------------|
| `--scm-provider` | `scmTenant.scmProviderType`     | `SCM_MANAGER` | ScmProviderType         | Specifies the SCM provider type. Possible values: `SCM_MANAGER`, `GITLAB`. |
|                  | `scmTenant.gitOpsUsername`      | `''`         | String                  | The username for the GitOps user.                                      |
|                  | `scmTenant.gitlab`              | `''`         | GitlabTenantConfig      | Configuration for GitLab, including URL, username, token, and parent group ID. |
|                  | `scmTenant.scmManager`          | `''`         | ScmManagerTenantConfig  | Configuration for SCM Manager, such as internal setup or plugin handling. |

###### SCMM(Tenant)

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--scmm-skip-restart` | `scmm.skipRestart` | `false` | Boolean | Skips restarting SCM-Manager after plugin installation |
| `--scmm-skip-plugins` | `scmm.skipPlugins` | `false` | Boolean | Skips plugin installation |
| `--scmm-url` | `scmm.url` | `''` | String | The host of your external scm-manager |
| `--scmm-username` | `scmm.username` | `'admin'` | String | Mandatory when scmm-url is set |
| `--scmm-password` | `scmm.password` | `'admin'` | String | Mandatory when scmm-url is set |
| `--scm-root-path` | `scmm.rootPath` | `'repo'` | String | Sets the root path for the Git Repositories |
| - | `scmm.helm.chart` | `'scm-manager'` | String | Name of the Helm chart |
| - | `scmm.helm.repoURL` | `'https://packages.scm-manager.org/repository/helm-v2-releases/'` | String | Repository url from which the Helm chart should be obtained |
| - | `scmm.helm.version` | `'3.10.2'` | String | The version of the Helm chart to be installed |
| - | `scmm.helm.values` | `[:]` | Map | Helm values of the chart |


###### Gitlab(Tenant)

| CLI                 | Config             | Default   | Type   | Description                                                                                                |
|---------------------|--------------------|-----------|--------|------------------------------------------------------------------------------------------------------------|
| `--gitlab-url`      | `gitlabTenant.url` | `''`      | String | Base URL for the GitLab instance.                                                                          |
| `--gitlab-username` | `gitlabTenant.username` | `'oauth2.0'` | String | Defaults to: `oauth2.0` when a PAT token is provided.                                                      |
| `--gitlab-token`    | `gitlabTenant.password` | `''`      | String | PAT token for the account.                                                                                 |
| `--gitlab-group-id` | `gitlabTenant.parentGroupId` | `''`  | String | The numeric ID for the GitLab Group where repositories and subgroups should be created.                    |
|                     | `gitlabTenant.internal` | `false`  | Boolean | Indicates if GitLab is running in the same Kubernetes cluster. Currently only external URLs are supported. |


###### Images

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--kubectl-image` | `images.kubectl` | `"bitnamilegacy/kubectl:1.29"` | String | Sets image for kubectl |
| `--helm-image` | `images.helm` | `"ghcr.io/cloudogu/helm:3.16.4-1"` | String | Sets image for helm |
| `--kubeval-image` | `images.kubeval` | `"ghcr.io/cloudogu/helm:3.16.4-1"` | String | Sets image for kubeval |
| `--helmkubeval-image` | `images.helmKubeval` | `"ghcr.io/cloudogu/helm:3.16.4-1"` | String | Sets image for helmkubeval |
| `--yamllint-image` | `images.yamllint` | `"cytopia/yamllint:1.25-0.7"` | String | Sets image for yamllint |
| `--nginx-image` | `images.nginx` | `''` | String | Sets image for nginx |
| `--petclinic-image` | `images.petclinic` | `'eclipse-temurin:17-jre-alpine'` | String | Sets image for petclinic |
| `--maven-image` | `images.maven` | `''` | String | Sets image for maven |

###### ArgoCD

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--argocd` | `features.argocd.active` | `false` | Boolean | Installs ArgoCD as GitOps CD tool |
| `--argocd-operator` | `features.argocd.operator` | `false` | Boolean | Install ArgoCD operator |
| `--argocd-url` | `features.argocd.url` | `''` | String | The url of your external argocd |
| `--argocd-email-from` | `features.argocd.emailFrom` | `'argocd@example.org'` | String | Email from address for ArgoCD notifications |
| `--argocd-email-to-user` | `features.argocd.emailToUser` | `'app-team@example.org'` | String | Email to address for user notifications |
| `--argocd-email-to-admin` | `features.argocd.emailToAdmin` | `'infra@example.org'` | String | Email to address for admin notifications |
| `--argocd-resource-inclusions-cluster` | `features.argocd.resourceInclusionsCluster` | `''` | String | ArgoCD resource inclusions for cluster |
| `--argocd-namespace` | `features.argocd.namespace` | `'argocd'` | String | ArgoCD namespace |
| - | `features.argocd.env` | - | List | Environment variables for ArgoCD |

###### Mail

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--mailhog, --mail` | `features.mail.mailhog` | `false` | Boolean | Installs Mailhog as email testing tool |
| `--mailhog-url` | `features.mail.mailhogUrl` | `''` | String | The url of your external mailhog |
| `--smtp-address` | `features.mail.smtpAddress` | `''` | String | SMTP server address |
| `--smtp-port` | `features.mail.smtpPort` | `null` | Integer | SMTP server port |
| `--smtp-user` | `features.mail.smtpUser` | `''` | String | SMTP username |
| `--smtp-password` | `features.mail.smtpPassword` | `''` | String | SMTP password |
| `--mailhog-image` | `features.mail.helm.image` | `'ghcr.io/cloudogu/mailhog:v1.0.1'` | String | Mailhog container image |
| - | `features.mail.helm.chart` | `'mailhog'` | String | Name of the Helm chart |
| - | `features.mail.helm.repoURL` | `'https://codecentric.github.io/helm-charts'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.mail.helm.version` | `'5.0.1'` | String | The version of the Helm chart to be installed |
| - | `features.mail.helm.values` | `[:]` | Map | Helm values of the chart |

###### Monitoring

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--metrics, --monitoring` | `features.monitoring.active` | `false` | Boolean | Installs monitoring stack (Prometheus, Grafana) |
| `--grafana-url` | `features.monitoring.grafanaUrl` | `''` | String | The url of your external grafana |
| `--grafana-email-from` | `features.monitoring.grafanaEmailFrom` | `'grafana@example.org'` | String | Email from address for Grafana notifications |
| `--grafana-email-to` | `features.monitoring.grafanaEmailTo` | `'infra@example.org'` | String | Email to address for Grafana notifications |
| `--grafana-image` | `features.monitoring.helm.grafanaImage` | `''` | String | Grafana container image |
| `--grafana-sidecar-image` | `features.monitoring.helm.grafanaSidecarImage` | `''` | String | Grafana sidecar container image |
| `--prometheus-image` | `features.monitoring.helm.prometheusImage` | `''` | String | Prometheus container image |
| `--prometheus-operator-image` | `features.monitoring.helm.prometheusOperatorImage` | `''` | String | Prometheus operator container image |
| `--prometheus-config-reloader-image` | `features.monitoring.helm.prometheusConfigReloaderImage` | `''` | String | Prometheus config reloader container image |
| - | `features.monitoring.helm.chart` | `'kube-prometheus-stack'` | String | Name of the Helm chart |
| - | `features.monitoring.helm.repoURL` | `'https://prometheus-community.github.io/helm-charts'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.monitoring.helm.version` | `'69.7.4'` | String | The version of the Helm chart to be installed |
| - | `features.monitoring.helm.values` | `[:]` | Map | Helm values of the chart |

###### Secrets

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--vault` | `features.secrets.vault.mode` | - | VaultMode | Install Vault for secrets management |
| `--vault-url` | `features.secrets.vault.url` | `''` | String | The url of your external vault |
| `--vault-image` | `features.secrets.vault.helm.image` | `''` | String | Vault container image |
| `--external-secrets-image` | `features.secrets.externalSecrets.helm.image` | `''` | String | External secrets operator image |
| `--external-secrets-certcontroller-image` | `features.secrets.externalSecrets.helm.certControllerImage` | `''` | String | External secrets cert controller image |
| `--external-secrets-webhook-image` | `features.secrets.externalSecrets.helm.webhookImage` | `''` | String | External secrets webhook image |
| - | `features.secrets.vault.helm.chart` | `'vault'` | String | Name of the Helm chart |
| - | `features.secrets.vault.helm.repoURL` | `'https://helm.releases.hashicorp.com'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.secrets.vault.helm.version` | `'0.25.0'` | String | The version of the Helm chart to be installed |
| - | `features.secrets.externalSecrets.helm.chart` | `'external-secrets'` | String | Name of the Helm chart |
| - | `features.secrets.externalSecrets.helm.repoURL` | `'https://charts.external-secrets.io'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.secrets.externalSecrets.helm.version` | `'0.9.16'` | String | The version of the Helm chart to be installed |

###### Ingress Nginx

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--ingress-nginx` | `features.ingressNginx.active` | `false` | Boolean | Install Ingress Nginx controller |
| `--ingress-nginx-image` | `features.ingressNginx.helm.image` | `''` | String | Ingress Nginx controller image |
| - | `features.ingressNginx.helm.chart` | `'ingress-nginx'` | String | Name of the Helm chart |
| - | `features.ingressNginx.helm.repoURL` | `'https://kubernetes.github.io/ingress-nginx'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.ingressNginx.helm.version` | `'4.12.1'` | String | The version of the Helm chart to be installed |
| - | `features.ingressNginx.helm.values` | `[:]` | Map | Helm values of the chart |

###### Cert Manager

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--cert-manager` | `features.certManager.active` | `false` | Boolean | Install cert-manager for TLS certificate management |
| `--cert-manager-image` | `features.certManager.helm.image` | `''` | String | Cert-manager controller image |
| `--cert-manager-webhook-image` | `features.certManager.helm.webhookImage` | `''` | String | Cert-manager webhook image |
| `--cert-manager-cainjector-image` | `features.certManager.helm.cainjectorImage` | `''` | String | Cert-manager CA injector image |
| `--cert-manager-acme-solver-image` | `features.certManager.helm.acmeSolverImage` | `''` | String | Cert-manager ACME solver image |
| `--cert-manager-startup-api-check-image` | `features.certManager.helm.startupAPICheckImage` | `''` | String | Cert-manager startup API check image |
| - | `features.certManager.helm.chart` | `'cert-manager'` | String | Name of the Helm chart |
| - | `features.certManager.helm.repoURL` | `'https://charts.jetstack.io'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.certManager.helm.version` | `'1.16.1'` | String | The version of the Helm chart to be installed |
| - | `features.certManager.helm.values` | `[:]` | Map | Helm values of the chart |

###### Content

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--content-examples` | `content.examples` | `false` | Boolean | Deploy example content: source repos, GitOps repos, Jenkins Job, Argo CD apps/project |
| - | `content.namespaces` | `[]` | List | Additional kubernetes namespaces |
| - | `content.repos` | `[]` | List | Content repos to push into target environment |
| - | `content.variables` | `[:]` | Map | Additional variables to use in custom templates |
| - | `content.repos[].url` | `''` | String | URL of the content repo. Mandatory for each type |
| - | `content.repos[].path` | `'.'` | String | Path within the content repo to process |
| - | `content.repos[].ref` | `''` | String | Reference for a specific branch, tag, or commit |
| - | `content.repos[].targetRef` | `''` | String | Reference for a specific branch or tag in the target repo |
| - | `content.repos[].username` | `''` | String | Username to authenticate against content repo |
| - | `content.repos[].password` | `''` | String | Password to authenticate against content repo |
| - | `content.repos[].templating` | `false` | Boolean | When true, template all files ending in .ftl within the repo |
| - | `content.repos[].type` | `MIRROR` | ContentRepoType | Content repo type (FOLDER_BASED, COPY, MIRROR) |
| - | `content.repos[].target` | `''` | String | Target repo for the repository in the form of namespace/name |
| - | `content.repos[].overwriteMode` | `INIT` | OverwriteMode | How customer repos will be updated (INIT, RESET, UPGRADE) |
| - | `content.repos[].createJenkinsJob` | `false` | Boolean | If true, creates a Jenkins job |

###### MultiTenant

| CLI                          | Config                              | Default       | Type                     | Description                                                    |
|------------------------------|-------------------------------------|---------------|--------------------------|----------------------------------------------------------------|
| `--dedicated-instance`       | `multiTenant.useDedicatedInstance`  | `false`       | Boolean                  | Toggles the Dedicated Instances Mode. See docs for more info   |
| `--central-argocd-namespace` | `multiTenant.centralArgocdNamespace`| `'argocd'`    | String                   | Namespace for the centralized Argocd                           |
| `--central-scm-provider`     | `multiTenant.scmProviderType`       | `SCM_MANAGER` | ScmProviderType          | The SCM provider type. Possible values: `SCM_MANAGER`, `GITLAB`|
|                              | `multiTenant.gitlab`                | ``        | GitlabCentralConfig      | Config for GITLAB                                              |
|                              | `multiTenant.scmManager`            | ``        | ScmManagerCentralConfig  | Config for SCM Manager                                         |

###### Gitlab(Central)

| CLI                          | Config                         | Default     | Type    | Description                                                      |
|------------------------------|--------------------------------|-------------|---------|------------------------------------------------------------------|
| `--central-gitlab-url`       | `multiTenant.gitlab.url`       | `''`        | String  | URL for external Gitlab                                          |
| `--central-gitlab-username`  | `multiTenant.gitlab.username`  | `'oauth2.0'`| String  | Username for GitLab authentication                               |
| `--central-gitlab-token`     | `multiTenant.gitlab.password`  | `''`        | String  | Password for SCM Manager authentication                          |
| `--central-gitlab-group-id`  | `multiTenant.gitlab.parentGroupId` | `''`    | String  | Main Group for Gitlab where the GOP creates it's groups/repos    |
|                              | `multiTenant.gitlab.internal`  | `false`     | Boolean | SCM is running on the same cluster (only external supported now) |

###### Scm-Manager(Central)

| CLI                          | Config                              | Default         | Type    | Description                                                                          |
|------------------------------|-------------------------------------|-----------------|---------|--------------------------------------------------------------------------------------|
| `--central-scmm-internal`    | `multiTenant.scmManager.internal`   | `false`         | Boolean | SCM for Central Management is running on the same cluster, so k8s internal URLs can be used for access |
| `--central-scmm-url`         | `multiTenant.scmManager.url`        | `''`            | String  | URL for the centralized Management Repo                                              |
| `--central-scmm-username`    | `multiTenant.scmManager.username`   | `''`            | String  | CENTRAL SCMM USERNAME                                                                |
| `--central-scmm-password`    | `multiTenant.scmManager.password`   | `''`            | String  | CENTRAL SCMM Password                                                                |
| `--central-scmm-root-path`   | `multiTenant.scmManager.rootPath`   | `'repo'`        | String  | Root path for SCM Manager                                                            |
| `--central-scmm-namespace`   | `multiTenant.scmManager.namespace`  | `'scm-manager'` | String  | Namespace where to find the Central SCMM                                             |

##### Configuration file

You can also use a configuration file to specify the parameters (`--config-file` or `--config-map`).
That file must be a YAML file. 

Note that the config file is not yet a complete replacement for CLI parameters.

You can use `--output-config-file` to output the current config as set by defaults and CLI parameters.

In addition, For easier validation and auto-completion, we provide a [schema file](https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/configuration.schema.json).

For example in Jetbrains IntelliJ IDEA, you can use the schema for autocompletion and validation when you put the following at the beginning of your config file:

```yaml
# $schema: https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/configuration.schema.json
```

If you work with an older version, you can use a specific git commit ID instead of `main` in the schema URL.

Then use the context assistant to enable coding assistance or fill in all available properties.
See [here](https://www.jetbrains.com/help/ruby/yaml.html#use-schema-keyword) for the full manual.

![example of a config file inside Jetbrains IntelliJ IDEA](docs/config-file-assistant.png)

###### Apply via Docker

```bash
docker run --rm -t --pull=always -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    -v $(pwd)/gitops-playground.yaml:/config/gitops-playground.yaml \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd --config-file=/config/gitops-playground.yaml
```

###### Apply via kubectl

[Create the serviceaccount and clusterrolebinding](#apply-via-kubectl-remote-cluster)

```bash
$ cat config.yaml # for example
features: 
  monitoring:
    active: true

# Convention:
# Find the ConfigMap inside the current namespace for the config map
# From the config map, pick the key "config.yaml"
kubectl create configmap gitops-config --from-file=config.yaml

kubectl run gitops-playground -i --tty --restart=Never \
  --overrides='{ "spec": { "serviceAccount": "gitops-playground-job-executer" } }' \
  --image ghcr.io/cloudogu/gitops-playground \
  -- --yes --argocd --config-map=gitops-config
```

Afterwards, you might want to do a [clean up](#apply-via-kubectl-remote-cluster).
In addition, you might want to delete the config-map as well.

``` bash
kubectl delete cm gitops-config 
```


##### Print all CLI parameters

You can get a full list of all CLI options like so:

```shell
docker run -t --rm ghcr.io/cloudogu/gitops-playground --help
```

##### Deploy Ingress Controller

In the default installation the GitOps-Playground comes without an Ingress-Controller.  

We use Nginx as default Ingress-Controller.
It can be enabled via the configfile or parameter `--ingress-nginx`.

In order to make use of the ingress controller, it is recommended to use it in conjunction with [`--base-url`](#deploy-ingresses), which will create `Ingress` objects for all components of the GitOps playground.

The ingress controller is based on the helm chart [`ingress-nginx`](https://kubernetes.github.io/ingress-nginx).

Additional parameters from this chart's values.yaml file can be added to the installation through the gitops-playground [configuration file](#configuration-file).

Example:
```yaml
features:
  ingressNginx:
    active: true
    helm:
      values:
        controller:
          replicaCount: 4
```
In this Example we override the default `controller.replicaCount` (GOP's default is 2).

This config file is merged with precedence over the defaults set by 
* [the GOP](applications/cluster-resources/ingress-nginx-helm-values.ftl.yaml) and
* [the charts itself](https://github.com/kubernetes/ingress-nginx/blob/main/charts/ingress-nginx/values.yaml).

##### Deploy Ingresses

It is possible to deploy `Ingress` objects for all components. You can either

* set a common base url (`--base-url=https://example.com`) or
* individual URLS:
```
--argocd-url https://argocd.example.com 
--grafana-url https://grafana.example.com 
--vault-url https://vault.example.com 
--mailhog-url https://mailhog.example.com 
--petclinic-base-domain petclinic.example.com 
--nginx-base-domain nginx.example.com
```
* or both, where the individual URLs take precedence.

Note: 
* `jenkins-url` and `scmm-url` are for external services and do not lead to ingresses, but you can set them via `--base-url` for now.
* In order to make use of the `Ingress` you need an ingress controller.
  If your cluster does not provide one, the Playground can deploy one for you, via the [`--ingress-nginx` parameter](#deploy-ingress-controller).
* For this to work, you need to set an `*.example.com` DNS record to the externalIP of the ingress controller.

Alternatively, [hyphen-separated ingresses](#subdomains-vs-hyphen-separated-ingresses) can be created,
like http://argocd-example.com

###### Subdomains vs hyphen-separated ingresses

* By default, the ingresses are built as subdomains of `--base-url`.  
* You can change this behavior using the parameter `--url-separator-hyphen`.  
* With this, hyphens are used instead of dots to separate application name from base URL.
* Examples: 
  * `--base-url=https://xyz.example.org`: `argocd.xyz.example.org` (default)  
  * `--base-url=https://xyz.example.org`: `argocd-xyz.example.org` (`--url-separator-hyphen`)
* This is useful when you have a wildcard certificate for the TLD, but use a subdomain as base URL.  
  Here, browsers accept the validity only for the first level of subdomains.

###### Local ingresses

The ingresses can also be used when running the playground on your local machine:

* Ingresses might be easier to remember than arbitrary port numbers and look better in demos 
* With ingresses, we can execute our [local clusters](docs/k3d.md) in higher isolation or multiple playgrounds concurrently
* Ingresses are required [for running on Windows/Mac](#windows-or-mac).

To use them locally, 
* init your cluster (`init-cluster.sh`).
* apply your playground with the following parameters  
  * `--base-url=http://localhost` 
    * this is possible on Windows (tested on 11), Mac (tested on Ventura) or when using Linux with [systemd-resolved](https://www.freedesktop.org/software/systemd/man/systemd-resolved.service.html) (default in Ubuntu, not Debian)  
      As an alternative, you could add all `*.localhost` entries to your `hosts` file.  
      Use `kubectl get ingress -A` to get a full list 
    * Then, you can reach argocd on `http://argocd.localhost`, for example
  * `--base-url=http://local.gd` (or `127.0.0.1.nip.io`, `127.0.0.1.sslip.io`, or others)
    * This should work for all other machines that have access to the internet without further config 
    * Then, you can reach argocd on `http://argocd.local.gd`, for example
* Note that when using port 80, the URLs are shorter, but you run into issues because port 80 is regarded as a privileged port. 
  Java applications seem not to be able to reach `localhost:80` or even `127.0.0.1:80` (`NoRouteToHostException`)
* You can change the port using `init-cluster.sh --bind-ingress-port=8080`.  
  When you do, make sure to append the same port when applying the playground: `--base-url=http://localhost:8080`
* If your setup requires you to bind to a specific interface, you can just pass it with e.g. `--bind-ingress-port=127.0.0.1:80`

##### Deploy GitOps operators

* `--argocd` - deploy Argo CD GitOps operator

> ⚠️ **Note** that switching between operators is not supported.  
> That is, expect errors (for example with cluster-resources) if you apply the playground once with Argo CD and the next
> time without it. We recommend resetting the cluster with `init-cluster.sh` beforehand.

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
            kubectl: 'bitnamilegacy/kubectl:1.29',
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
* `--maven-image someRegistry/someImage:1.0.0`

Note that specifying a tag is mandatory.  
  
  
  
##### Argo CD-Notifications

If you are using a remote cluster, you can set the `--argocd-url` parameter so that argocd-notification messages have a
link to the corresponding application.
Otherwise, `argocd.$base-url` is used.

You can specify email addresses for notifications (note that by default, MailHog will not actually send emails)

* `--argocd-email-from`: Sender E-Mail address. Default is `argocd@example.org`)
* `--argocd-email-to-admin`: Alerts send to admin. Default is `infra@example.org`)
* `--argocd-email-to-user`: Alerts send to user. Default is `app-team@example.org`)


##### Monitoring

Set the parameter `--monitoring` to enable deployment of monitoring and alerting tools like prometheus, grafana and mailhog.

See [Monitoring tools](#monitoring-tools) for details.

You can specify email addresses for notifications (note that by default, MailHog will not actually send emails)

* `--grafana-email-from`: Sender E-Mail address. Default is `grafana@example.org`
* `--grafana-email-to`: Recipient E-Mail address. Default is `infra@example.org`

##### Mail server

The gitops-playground uses MailHog to showcase notifications.  
Alternatively, you can configure an external mailserver.

Note that you can't use both at the same time.   
If you set either `--mailhog` or `--mail` parameter, MailHog will be installed  
If you set `--smtp-*` parameters, a external Mailserver will be used and MailHog will not be deployed.

##### MailHog
Set the parameter `--mailhog` to enable MailHog.

This will deploy MailHog and configure Argo CD and Grafana to send mails to MailHog.  
Sender and recipient email addresses can be set via parameters in some applications, e.g. `--grafana-email-from` or `--argocd-email-to-user`.

Parameters:
* `--mailhog`: Activate MailHog as internal Mailserver
* `--mailhog-url`: Specify domain name (ingress) under which MailHog will be served

##### External Mailserver
If you want to use an external Mailserver you can set it with these parameters

* `--smtp-address`: External Mailserver SMTP address or IP
* `--smtp-port`: External Mailserver SMTP port
* `--smtp-user`: External Mailserver login username
* `--smtp-password`: External Mailservers login password. Make sure to put your password in single quotes.

This will configure Argo CD and Grafana to send mails using your external mailserver.  
In addition you should set matching sender and recipient email addresses, e.g. `--grafana-email-from` or `--argocd-email-to-user`.

##### Secrets Management

Set the parameter `--vault=[dev|prod]` to enable deployment of secret management tools hashicorp vault and external
secrets operator.

`--vault-url` specifies domain name, Otherwise, `vault.$base-url` is used.



See [Secrets management tools](#secrets-management-tools) for details.

##### Certificate Management
Is implemented by cert-manager. 
Set the parameter `--cert-manager` to enable cert-manager.
For custom images use this parameters to override defaults:
- --cert-manager-image 
- --cert-manager-webhook-image 
- --cert-manager-cainjector-image 
- --cert-manager-acme-solver-image
- --cert-manager-startup-api-check-image

i.e.
```
--cert-manager-image someRegistry/cert-manager-controller:latest
``` 
#### Profiles
GOP includes some pre-defined profiles for easy usage.
e.g. set `--profile=full` to start GOP with all features enabled.


Current existing profiles for argocd in non-operator mode:
- `full` - all features enabled     
- `minimal` - starts only with ArgoCD and SCM-Manger
- `content-examples` - starts with ArgoCD, Jenkins, SCM-Manager and Petclinic

Follow profils for ArgoCD in Operator mode which has to be installed first:
- `operator-full` - all features enabled
- `operator-minimal` - starts only with ArgoCD and SCM-Manger
- `operator-petclinic` - starts with ArgoCD, Jenkins, SCM-Manager and Petclinic
- `operator-mandant` - starts mandant/tenant example


### Remove playground

For k3d, you can just `k3d cluster delete gitops-playground`. This will delete the whole cluster.
If you want to delete k3d use `rm .local/bin/k3d`.

To remove the playground without deleting the cluster, use the option `--destroy`.
You need to pass the same parameters when deploying the playground to ensure that the destroy script 
can authenticate with all tools.
Note that this option has limitations. It does not remove CRDs, namespaces, locally deployed SCM-Manager, Jenkins and registry, plugins for SCM-Manager and Jenkins 

### Running on Windows or Mac

* In general: We cannot use the `host` network, so it's easiest to access via [ingress controller](#deploy-ingress-controller) and [ingresses](#local-ingresses).
* `--base-url=http://localhost --ingress-nginx` should work on both Windows and Mac.
* In case of problems resolving e.g. `jenkins.localhost`, you could try using `--base-url=http://local.gd` or similar, as described in [local ingresses](#local-ingresses).

#### Mac and Windows WSL

On macOS and when using the Windows Subsystem Linux on Windows (WSL), you can just run our [TL;DR command](#tldr) after installing Docker.

For Windows, we recommend using [Windows Subsystem for Linux version 2](https://learn.microsoft.com/en-us/windows/wsl/install#install-wsl-command) (WSL2) with a [native installation of Docker Engine](https://docs.docker.com/engine/install/), because it's easier to set up and less prone to errors.

For macOS, please increase the Memory limit in Docker Desktop (for your DockerVM) to be > 10 GB.
Recommendation: 16GB.

```bash
bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) \
  && docker run --rm -t --pull=always -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd --ingress-nginx --base-url=http://localhost
# If you want to try all features, you might want to add these params: --mail --monitoring --vault=dev
```

When you encounter errors with port 80 you might want to use e.g. 
* `init-cluster.sh) --bind-ingress-port=8080` and 
* `--base-url=http://localhost:8080` instead.

#### Windows Docker Desktop

* As mentioned in the previous section, we recommend using WSL2 with a native Docker Engine.
* If you must, you can also run using Docker Desktop from native Windows console (see bellow)
* However, there seems to be a problem when the Jenkins Jobs running the playground access docker, e.g.   
```
$ docker run -t -d -u 0:133 -v ... -e ******** bitnamilegacy/kubectl:1.25.4 cat
docker top e69b92070acf3c1d242f4341eb1fa225cc40b98733b0335f7237a01b4425aff3 -eo pid,comm
process apparently never started in /tmp/gitops-playground-jenkins-agent/workspace/xample-apps_petclinic-plain_main/.configRepoTempDir@tmp/durable-7f109066
(running Jenkins temporarily with -Dorg.jenkinsci.plugins.durabletask.BourneShellScript.LAUNCH_DIAGNOSTICS=true might make the problem clearer)
Cannot contact default-1bg7f: java.nio.file.NoSuchFileException: /tmp/gitops-playground-jenkins-agent/workspace/xample-apps_petclinic-plain_main/.configRepoTempDir@tmp/durable-7f109066/output.txt
```
* In Docker Desktop, it's recommended to use WSL2 as backend. 
* Using the Hyper-V backend should also work, but we experienced random `CrashLoopBackoff`s of running pods due to liveness probe timeouts.  
  Same as for macOS, increasing the Memory limit in Docker Desktop (for your DockerVM) to be > 10 GB might help.  
  Recommendation: 16GB.

Here is how you can start the playground from a Windows-native PowerShell console:

* [Install k3d](https://k3d.io/#installation), see [init-cluster.sh](scripts/init-cluster.sh) for `K3D_VERSION`, e.g. using `winget`
```powershell
winget install k3d --version x.y.z
```
* Create k3d cluster.
  See `K3S_VERSION` in [init-cluster.sh](scripts/init-cluster.sh) for `$image`, then execute  
```powershell
$ingress_port = "80"
$registry_port = "30000"
$image = "rancher/k3s:v1.25.5-k3s2"
# Note that ou can query the image version used by playground like so: 
# (Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh').Content -split "`r?`n" | Select-String -Pattern 'K8S_VERSION=|K3S_VERSION='

k3d cluster create gitops-playground `
    --k3s-arg=--kube-apiserver-arg=service-node-port-range=8010-65535@server:0 `
    -p ${ingress_port}:80@server:0:direct `
    -v /var/run/docker.sock:/var/run/docker.sock@server:0 `
    --image=${image} `
    -p ${registry_port}:30000@server:0:direct

# Write $HOME/.config/k3d/kubeconfig-gitops-playground.yaml
k3d kubeconfig write gitops-playground
```
* Note that
  * You can ignore the warning about docker.sock
  * We're mounting the docker socket, so it can be used by the Jenkins Agents for the docker-plugin.
  * Windows seems not to provide a group id for the docker socket. So the Jenkins Agents run as root user.
  * If you prefer running with an unprivileged user, consider running on WSL2, Mac or Linux
  * You could also add `-v gitops-playground-build-cache:/tmp@server:0 ` to persist the Cache of the Jenkins agent between restarts of k3d containers.
* Apply playground:  
  Note that when using a `$registry_port` other than `30000` append the command `--internal-registry-port=$registry_port` bellow
  
```powershell
docker run --rm -t --pull=always `
    -v $HOME/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config `
    --net=host `
    ghcr.io/cloudogu/gitops-playground --yes --argocd --ingress-nginx --base-url=http://localhost:$ingress_port # more params go here
```

## Stack

As described [above](#what-is-the-gitops-playground) the GitOps playground creates a complete GitOps-based operational
stack that can be used as an internal developer platform (IDP) on your Kubernetes cluster.

The stack is composed of multiple applications, where some of them can be accessed via a web UI.
* Argo CD
* Prometheus/Grafana
* Jenkins
* SCM-Manager
* Vault
* Ingress-nginx
* Cert-Manager

In addition, there are example applications that provide a turnkey solution for GitOps-Pipelines from a developer's
point of view.
See [Example Applications](#example-applications).

We recommend using the `--ingress-nginx` and `--base-url` Parameters.
With these, the applications are made available as subdomains of `base-url`.

For example, `--base-url=http://localhost` leads to `
* http://argocd.localhost
* http://grafana.localhost
* http://jenkins.localhost
* http://scmm.localhost
* http://vault.localhost

Of course, this would also work for production instances with proper domains, see [Deploy Ingresses](#deploy-ingresses).

All applications are deployed via GitOps and can be found in the `cluster-resources` repository.
See [Argo CD](#argo-cd) for more details on the repository structure.

### Credentials

If deployed within the cluster, all applications can be accessed via: `admin/admin`

Note that you can change (and should for a remote cluster!) the password with the `--password` argument.
There also is a `--username` parameter, which is ignored for argocd. That is, for now Argo CD's username always is `admin`.

### Argo CD

Argo CD is installed in a production-ready way that allows for operating Argo CD with Argo CD, using GitOps and
providing a [repo per team pattern](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#repo-per-team).

When installing the GitOps playground, the following steps are performed to bootstrap Argo CD:

* The following repos are created and initialized:
    * `argocd` (management and config of Argo CD itself),
    * `example-apps` (example for a developer/application team's GitOps repo) and
    * `cluster-resources` (example for a cluster admin or infra/platform team's repo; see below for details)
* Argo CD is installed imperatively via a helm chart.
* Two resources are applied imperatively to the cluster: an `AppProject` called `argocd` and an `Application` called
  `bootstrap`. These are also contained within the `argocd` repository.

From there, everything is managed via GitOps. This diagram shows how it works.

[![](docs/argocd-repos.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/argocd-repos.svg "View full size")

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

[![](docs/autopilot-repo.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/autopilot-repo.svg "View full size")

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
`argocd/cluster-resources`.

When installing without Argo CD, the tools are installed using helm imperatively.
We fall back to using imperative helm installation as a kind of neutral ground.

### Jenkins

You can set an external jenkins server via the following parameters when applying the playground.
See [parameters](#overview-of-all-cli-and-config-options) for examples.

* `--jenkins-url`,
* `--jenkins-username`,
* `--jenkins-password`

The user has to have the following privileges:
* install plugins
* set credentials
* create jobs
* restarting

To apply additional global environments for jenkins you can use `--jenkins-additional-envs "KEY1=value1,KEY2=value2"` parameter.

Note that the [example applications](#example-applications) pipelines will only run on a Jenkins that uses agents that provide
a docker host. That is, Jenkins must be able to run e.g. `docker ps` successfully on the agent.

## SCMs

You can choose between the following Git providers:

- SCM-Manager
- GitLab

For configuration details, see the CLI or configuration parameters above ([SCM](#scmtenant)).

### GitLab

When using GitLab, you must provide a valid **parent group ID**.
This group will serve as the main group for the GOP to create and manage all required repositories.

[![gitlab ParentID](docs/gitlab-parentid.png)](https://docs.gitlab.com/user/group/#find-the-group-id)

To authenticate with Gitlab provide a token token as password. More information can be found [here](https://docs.gitlab.com/api/rest/authentication/)  or [here](https://docs.gitlab.com/user/profile/personal_access_tokens/)
The username should remain 'oauth2.0' to access the API, unless stated otherwise by GitLab documentation.
### SCM-Manager

You can set an external SCM-Manager via the following parameters when applying the playground.
See [parameters](#overview-of-all-cli-and-config-options) for examples.

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
is being deployed including dashboards for 
- ArgoCD
- Ingress Nginx Controller
- Prometheus
- SCMManager
- Jenkins.


Grafana can be used to query and visualize metrics via prometheus.
It is exposed via ingress, e.g. http://grafana.localhost.
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

#### prod mode

When using `vault=prod` you'll have to initialize vault manually but on the other hand it will persist changes.

If you want the example app to work, you'll have to manually
* set up vault, unseal it and
* authorize the `vault` service accounts in `argocd-production` and `argocd-staging` namspaces. See `SecretStore`s and
  [dev-post-start.sh](system/secrets/vault/dev-post-start.sh) for an example.


#### Example app

With vault in `dev` mode and ArgoCD enabled, the example app `applications/nginx/argocd/helm-jenkins` will be deployed
in a way that exposes the vault secrets `secret/<environment>/nginx-secret` via HTTP on the URL `http://<host>/secret`,
for example `http://staging.nginx-helm.nginx.localhost/secret`.

While exposing secrets on the web is a bad practice, it's good for demoing auto reload of a secret changed in
vault.

To demo this, you could
* change the [staging secret](http://vault.localhost/ui/vault/secrets/secret/edit/staging/nginx-helm-jenkins)
* Wait for the change to show on the web, e.g. like so
```shell
while ; do echo -n "$(date '+%Y-%m-%d %H:%M:%S'): " ; \
  curl http://staging.nginx-helm.nginx.localhost/secret/ ; echo; sleep 1; done
```

This usually takes between a couple of seconds and 1-2 minutes.  
This time consists of `ExternalSecret`'s `refreshInterval`, as well as the [kubelet sync period](https://v1-25.docs.kubernetes.io/docs/concepts/configuration/configmap/#mounted-configmaps-are-updated-automatically)
(defaults to [1 Minute](https://kubernetes.io/docs/reference/config-api/kubelet-config.v1beta1/#kubelet-config-k8s-io-v1beta1-KubeletConfiguration))
+ cache propagation delay

The following video shows this demo in time-lapse:

[secrets-demo-video](https://user-images.githubusercontent.com/1824962/215204174-eadf180b-2a82-4273-8cbb-6e7c187267c6.mp4)

### Example Applications

The playground comes with example applications that provide a turnkey solution for GitOps-Pipelines  
from a developer's point of view.

These can be enabled using `--content-examples`.  
They require a registry, so locally use `--registry` or pass in an existing instance using `registry-url`.  
The examples very much rely on jenkins. So it is recommended to enable it using `--jenkins` or pass in an existing 
instance using `--jenkins-url`.  

The examples include staging and production environments, providing a ready-to-use solution for promotion.

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

[![app-repo-vs-gitops-repo](docs/app-repo-vs-gitops-repo.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/app-repo-vs-gitops-repo.svg "View full size")

Note that the GitOps-related logic is implemented in the
[gitops-build-lib](https://github.com/cloudogu/gitops-build-lib) for Jenkins. See the README there for more options like
* staging,
* resource creation,
* validation (fail early / shift left). 

For further understanding, also take a look at our GitOps pattern repository
[cloudogu/gitops-patterns](https://github.com/cloudogu/gitops-patterns?tab=readme-ov-file#gitops-playground)

Please note that it might take about a minute after the pull request has been accepted for the GitOps operator to start
deploying.
Alternatively, you can trigger the deployment via ArgoCD's UI or CLI.


We recommend using the `--ingress-nginx` and `--base-url` Parameters.
With these, the applications are made available as subdomains of `base-url`.

For example, `--base-url=http://localhost` leads to 
http://staging.petclinic-plain.petclinic.localhost/.

The `.petlinic.` part can be overridden using
`--petclinic-base-domain` (for the petlinic examples/exercises), or 
`--nginx-base-domain` (for the nginx examples/exercises).

#### PetClinic with plain k8s resources

[Jenkinsfile](applications/petclinic/argocd/plain-k8s/Jenkinsfile) for `plain` deployment

* Staging: http://staging.petclinic-plain.petclinic.localhost/
* Production: http://production.petclinic-plain.petclinic.localhost/  
  Note that you have to accept a [pull request](http://scmm.localhost/scm/repo/argocd/example-apps/pull-requests/) for deployment

#### PetClinic with helm

[Jenkinsfile](applications/petclinic/argocd/helm/Jenkinsfile) for `helm` deployment

* Staging: http://staging.petclinic-helm.petclinic.localhost/
* Production: http://production.petclinic-helm.petclinic.localhost/  
   Note that you have to accept a [pull request](http://scmm.localhost/scm/repo/argocd/example-apps/pull-requests/) for deployment

#### 3rd Party app (NGINX) with helm, templated in Jenkins

[Jenkinsfile](applications/nginx/argocd/helm-jenkins/Jenkinsfile)

* Staging: http://staging.nginx-helm.nginx.localhost/
* Production: http://production.nginx-helm.nginx.localhost/  
  Note that you have to accept a [pull request](http://scmm.localhost/scm/repo/argocd/example-apps/pull-requests/) for deployment


#### 3rd Party app (NGINX) with helm, using Helm dependency mechanism

* http://production.nginx-helm-umbrella.nginx.localhost/

## Development

See [docs/developers.md](docs/developers.md)

## License
Copyright © 2020 - present Cloudogu GmbH

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along with this program. If not, see https://www.gnu.org/licenses/.  

See [LICENSE](LICENSE) for details.

GitOps Playground© for use with  Argo™, Git™, Jenkins®, Kubernetes®, Grafana®, Prometheus®, Vault® and SCM-Manager 

Argo™ is an unregistered trademark of The Linux Foundation®  
Git™ is an unregistered trademark of Software Freedom Conservancy Inc.  
Jenkins® is a registered trademark of LF Charities Inc.  
Kubernetes® and the Kubernetes logo® are registered trademarks of The Linux Foundation®  
K8s® is a registered trademark of The Linux Foundation®  
The Grafana Labs Marks are trademarks of Grafana Labs, and are used with Grafana Labs’ permission. We are not affiliated with, endorsed or sponsored by Grafana Labs or its affiliates.  
Prometheus® is a registered trademark of The Linux Foundation®  
Vault® and the Vault logo® are registered trademarks of HashiCorp® (http://www.hashicorp.com/)  

## Written Offer
Written Offer for Source Code:

Information on the license conditions and - if required by the license - on the source code is available free of charge on request.  
However, some licenses require providing physical copies of the source or object code. If this is the case, you can request a copy of the source code. A small fee is charged for these services to cover the cost of physical distribution.

To receive a copy of the source code, you can either submit a written request to

Cloudogu GmbH  
Garküche 1  
38100 Braunschweig

or you may email hello@cloudogu.com.

Your request must be sent within three years from the date you received the software from Cloudogu that is the subject of your request or, in the case of source code licensed under the AGPL/GPL/LGPL v3, for as long as Cloudogu offers spare parts or customer support
for the product, including the components or binaries that are the subject of your request.