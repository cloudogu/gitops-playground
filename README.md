# GitOps Playground

> "Mom, can we get an IDP?" - "We have an IDP in our local kubernetes cluster!" 
 
[![Playground features](docs/images/gitops-playground-features.drawio.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/images/gitops-playground-features.drawio.svg "View full size")

Create a complete GitOps-based operational stack with all the tools you need for an internal developer platform, on your machine, in your datacenter or in the cloud! 

* __Deployment__: GitOps via Argo CD with a ready-to-use [repo structure](#argo-cd)
* __Monitoring: [Prometheus and Grafana](#monitoring-tools)
* __Secrets__ Management:  [Vault and External Secrets Operator](#secrets-management-tools)
* __Notifications__/Alerts: Grafana and ArgoCD can be predefined with either an external mailserver or [MailHog](https://github.com/mailhog/MailHog) for demo purposes.
* __Pipelines__: Example applications using [Jenkins](#jenkins) with the [gitops-build-lib](https://github.com/cloudogu/gitops-build-lib) and [SCM-Manager](#scm-manager)
* __Ingress__ Controller: [ingress](https://traefik.github.io/charts)
* __Certificate__ Management: [cert-manager](#certificate-management)
* [Content Loader](docs/content-loader/content-loader.md): Completely customize what is pushed to Git during installation.
  This allows for adding your own end-user or IDP apps, creating repos, adding Argo CD tenants, etc.
* Runs on: 
  * local cluster (try it [with only one command](#tldr)), 
  * in the public cloud, 
  * and even air-gapped environments.

The gitops-playground is derived from our experiences in [consulting](https://platform.cloudogu.com/consulting/kubernetes-und-gitops/?mtm_campaign=gitops-playground&mtm_kwd=consulting&mtm_source=github&mtm_medium=link),
operating our internal developer platform (IDP) at [Cloudogu](https://cloudogu.com/?mtm_campaign=gitops-playground&mtm_kwd=cloudogu&mtm_source=github&mtm_medium=link) and is used in our [GitOps trainings](https://platform.cloudogu.com/en/trainings/gitops-continuous-operations/?mtm_campaign=gitops-playground&mtm_kwd=training&mtm_source=github&mtm_medium=link).  


## TL;DR

You can try the GitOps Playground on a local Kubernetes cluster by running a single command:

```shell
bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) \
  && docker run --rm -t --pull=always -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd --ingress --base-url=http://localhost
# More IDP-features: --mail --monitoring --vault=dev --cert-manager
# More features for developers: --jenkins --registry --content-examples
```

Note that on some linux distros like debian do not support subdomains of localhost.
There you might have to use `--base-url=http://local.gd` (see [local ingresses](#local-ingresses)).

We recommend running this command as an unprivileged user, that is inside the [docker group](https://docs.docker.com/engine/install/linux-postinstall/#manage-docker-as-a-non-root-user).

## What is the GitOps Playground?

The GitOps Playground provides a reproducible environment for setting up a complete GitOps-based operational stack 
that can be used as an internal developer platform (IDP) on your Kubernetes clusters.
It provides an image for automatically setting up a Kubernetes Cluster including CI-server (Jenkins),
source code management (SCM-Manager), Monitoring and Alerting (Prometheus, Grafana, MailHog), Secrets Management (Hashicorp
Vault and External Secrets Operator) and of course, Argo CD as GitOps operator.

The playground also deploys a number of [example applications](#example-applications).The GitOps Playground lowers the barriers for operating your application on Kubernetes using GitOps.

It creates a complete GitOps-based operational stack on your Kubernetes clusters.
No need to read lots of books and operator docs, getting familiar with CLIs, 
ponder about GitOps Repository folder structures and promotion to different environments, etc.
The GitOps Playground is a pre-configured environment to see GitOps in motion, including more advanced use cases like
notifications, monitoring and secret management.

We aim to be compatible with various environments, we even run in an air-gapped networks.

## Installation and Components

A detailed document on how to install GOP in all possible environments can be found [here](docs/Installation.md).
For a deep-dive into all components that GOP can install for you, see [Applications](docs/Applications.md)


## Configuration

You can configure GOP using CLI params, config file and/or config map.
Config file and map have the same format and offer a [schema file](https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/configuration.schema.json). 
Please find an overview of all CLI and config options [here](docs/Configuration.md)

**Configuration precedence (highest to lowest):**
1. Command-line parameters
2. Configuration files (`--config-file`)
3. Config maps (`--config-map`)

That is, if you pass a param via CLI, for example, it will overwrite the corresponding value in the configuration.

For a deep-dive into GOPs configuration, see [Configuration.md](docs/Configuration.md)

### Profiles
GOP includes some pre-defined profiles for easy usage.
e.g. set `--profile=full` to start GOP with all features enabled.


Current existing profiles for argocd in non-operator mode:
- `full` - all features enabled     
- `minimal` - starts only with ArgoCD and SCM-Manger
- `content-examples` - starts with ArgoCD, Jenkins, SCM-Manager and Petclinic

Follow profils for ArgoCD in Operator mode which has to be installed first:
- `operator-full` - all features enabled
- `operator-minimal` - starts only with ArgoCD and SCM-Manger
- `operator-content-examples` - starts with ArgoCD, Jenkins, SCM-Manager and Petclinic
- `operator-mandant` - starts mandant/tenant example


## Remove playground

For k3d, you can just `k3d cluster delete gitops-playground`. This will delete the whole cluster.
If you want to delete k3d use `rm .local/bin/k3d`.


## Additional Ressources

We compiled a few helpful documents for the most common use-cases/scenarios:
- [Deploying an ingress controller](docs/Deploy-Ingress-Controller.md)
- [Deploy with a Cloudogu Ecosystem](docs/Deploy-with-CES.md)
- [Running GOP on Windows or Mac](docs/Running-on-Windows-Mac.md)


## Development

See [docs/Developers.md](docs/Developers.md)


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
