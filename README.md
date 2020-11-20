# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows. Derived from our [consulting experience](https://cloudogu.com/en/consulting/).

# Table of contents

<!-- Update with `doctoc --notitle README.md`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->

- [Prerequisites](#prerequisites)
- [Install k3s](#install-k3s)
- [Apply apps to cluster](#apply-apps-to-cluster)
- [Applications](#applications)
  - [Jenkins](#jenkins)
  - [SCM-Manager](#scm-manager)
- [Examples](#examples)
  - [PetClinic with plain k8s resources](#petclinic-with-plain-k8s-resources)
  - [3rd Party app (NGINX) via helm chart from helm repository](#3rd-party-app-nginx-via-helm-chart-from-helm-repository)
- [Remove apps from cluster](#remove-apps-from-cluster)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## Prerequisites

To be able to set up the infrastructure you need a linux machine (tested with Ubuntu 20.04) with docker installed.
All other tools like kubectl, k3s and helm are set up using the `./scripts/init-cluster.sh` script.

## Install k3s

You can use your own k3s cluster, or use the script provided.
Run this script from repo root with:

`./scripts/init-cluster.sh`

If you use your own cluster, note that jenkins relies on the `--docker` mode to be enabled.

In a real-life scenario, it would make sense to run Jenkins agents outside the cluster for security and load reasons, 
but in order to simplify the setup for this playground we use this slightly dirty workaround: 
Jenkins builds on the master and uses the docker agent that also runs the k8s pods. That's why we need the k3s' 
`--docker` mode.
 
**Don't use a setup such as this in production!** The diagrams bellow show an overview of the playground's architecture,
 and a possible production scenario using our [Ecosystem](https://cloudogu.com/en/ecosystem/) (more secure and better build performance using ephemeral build agents spawned in the cloud).


|Playground on local machine | A possible production environment with [Cloudogu Ecosystem](https://cloudogu.com/en/ecosystem/)|
|--------------------|----------|
|![Playground on local machine](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/gitops-playground.puml&fmt=svg) | [![A possible production environment](https://www.plantuml.com/plantuml/proxy?src=https://raw.githubusercontent.com/cloudogu/k8s-gitops-playground/main/docs/production-setting.puml&fmt=svg)   |

## Apply apps to cluster

[`scripts/apply.sh`](scripts/apply.sh)

The scripts also prints a little intro on how to get started with a GitOps deployment.


## Applications

### Jenkins

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - `scmadmin/scmadmin`
Change in `jenkins-credentials.yaml` if necessary.

### SCM-Manager

Find scm-manager on http://localhost:9091

Login with `scmadmin/scmadmin`

## Examples

### PetClinic with plain k8s resources

* [Jenkinsfile](petclinic/fluxv1/plain-k8s/Jenkinsfile)
* Deployed to 
  * [localhost:9093](http://localhost:9093) (Staging)
  * [localhost:9094](http://localhost:9094) (Production)

### 3rd Party app (NGINX) via helm chart from helm repository

* [Jenkinsfile](nginx/fluxv1/Jenkinsfile)
* Deployed to 
  * [localhost:9095](http://localhost:9095) (Staging)
  * [localhost:9096](http://localhost:9096) (Production)

## Remove apps from cluster

`scripts/destroy.sh`

## Remove apps from cluster

`scripts/destroy.sh`