# Applications

The GitOps playground creates a complete GitOps-based operational
stack that can be used as an internal developer platform (IDP) on your Kubernetes cluster.

The stack is composed of multiple applications, where some of them can be accessed via a web UI.
* Argo CD
* Prometheus/Grafana
* Jenkins
* SCM-Manager
* Vault
* Ingress
* Cert-Manager

In addition, there are example applications that provide a turnkey solution for GitOps-Pipelines from a developer's
point of view.
See [Example Applications](#example-applications).

We recommend using the `--ingress` and `--base-url` Parameters.
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


## Argo CD

Argo CD is installed in a production-ready way that allows for operating Argo CD with Argo CD, using GitOps and
providing a [repo per team pattern](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#repo-per-team).

### Repositories and layout

When installing the GitOps playground, the following Git repositories are created and initialized:

* example-apps – example GitOps repository for a developer / application team
* cluster-resources – example GitOps repository for a cluster admin or infra / platform team

Argo CD’s own management and configuration, which previously lived in a dedicated `argocd` repository, 
is now part of the `cluster-resources` repo under `apps/argocd`:

![example of argocd repo structure](docs/argocd.png)

### Bootstrapping Argo CD
When the GitOps playground is installed, Argo CD is bootstrapped as follows:
1. Argo CD is installed imperatively via a Helm chart.
2. Two resources are applied imperatively to the cluster:
      * an `AppProject` called `argocd`
      * an `Application` called `bootstrap`

   Both are stored in the `cluster-resources` repository under `apps/argocd/applications`.

From there, everything is managed via GitOps.

### How Argo CD manages itself
The following Argo CD Applications live in `apps/argocd/applications`:

* The `bootstrap` application manages the `apps/argocd/applications` folder (including itself).
  This allows changes to the bootstrap application and further application manifests to be managed via GitOps.
* The `argocd` application manages the folder `apps/argocd/argocd`, which contains Argo CD’s resources as an umbrella Helm chart.
  * The umbrella chart pattern allows us to:
    * describe configuration in `values.yaml`
    * deploy additional resources (such as secrets and ingresses) via the `templates` folder
  * `Chart.yaml` declares the Argo CD Helm chart as a dependency.
  * `Chart.lock` pins the chart to a deterministic version from the upstream chart repository.
  * This mechanism can be used to upgrade Argo CD via GitOps (by updating the chart version and syncing).
* The `projects` application manages the folder apps/argocd/projects, which contains the following `AppProject` resources:
  * the `argocd` project (used for bootstrapping)
  * the built-in default `project` (restricted for security)
  * one project per team, for example:
    * `cluster-resources` (platform admins, more cluster permissions)
    * `example-apps` (application developers, fewer permissions)

### Multi-source applications for features
Feature deployments (for example, monitoring, ingress, or other GOP features) are modeled as multi-source Argo CD Applications instead of using an App-of-Apps pattern.

For some features, the GitOps Playground Operator (GOP):
1. Writes values files into the `cluster-resources` repository under:
   ```powershell
       apps/<feature>/
          <feature>-gop-helm.yaml
          <feature>-user-values.yaml
   ```
   The `*-gop-helm.yaml` file is managed by GOP, while `*-user-values.yaml` is intended for user overrides and is not overwritten.

2. Generates an Argo CD `Application` in `apps/argocd/applications/<feature>.yaml` that uses two sources:
     * Helm source (external chart)
       * `repoURL`: the external Helm repository
       * `chart` or `path`: the chart to deploy
       * `targetRevision`: the chart version
       * `helm.valueFiles`: includes the values from the `cluster-resources` repo via `$values/...`
         (for example `$values/apps/<feature>/<feature>-gop-helm.yaml` and
         `$values/apps/<feature>/<feature>-user-values.yaml`)
     * Git source (values and additional manifests)
       * `repoURL`: the `cluster-resources` repo
       * `targetRevision`: typically `main`
       * `ref`: set to `values` so the Helm source can reference `$values/...`
       * `path`: `apps/<feature>`
       *  `directory.recurse: true` to pick up additional manifests in the feature folder
       
          If users create a `misc` subfolder under `apps/<feature>` (for example `apps/<feature>/misc`) and add additional Kubernetes manifests there, these manifests are automatically included and deployed as part of the feature.

Argo CD merges these sources, so each feature application is defined by:
  * the external Helm chart (versioned and reproducible), and
  * Git-managed configuration and manifests in the `cluster-resources` repo.

This multi-source pattern replaces the previous App-of-Apps based approach for feature deployments while still following the repo-per-team model.

### Application repo: example-apps
The `example-apps` repository demonstrates how application teams can structure their own GitOps repositories. Its layout looks like this:

![example of example-apps repo structure](docs/example.png)

  * The folder `apps/argocd/applications` contains Argo CD `Application` manifests for the example workloads:
    * `petclinic-plain.yaml`
  * Each application implements the [Environment per App Pattern](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#global-vs-env-per-app)::
    * separate folders for `staging`and `production`
    * optional `generatedResources/` subfolders where CI pipelines can write generated manifests (for example, templated messages or index files)

For example:
* `apps/spring-petclinic-plain/production` and `apps/spring-petclinic-plain/staging` contain 
  plain Kubernetes manifests (`deployment.yaml`, `service.yaml`, `ingress.yaml`) plus generated resources.

The `example-apps` repo is thus a reference for how product teams can structure their GitOps repositories while
still integrating cleanly with Argo CD and the multi-source pattern used by the platform.

To keep things simpler, the GitOps playground only uses one kubernetes cluster, effectively implementing the [Standalone](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#standalone)
pattern. However, the repo structure could also be used to serve multiple clusters, in a [Hub and Spoke](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#hub-and-spoke) pattern:
Additional clusters could either be defined in the `vaules.yaml` or as secrets via the `templates` folder.

We're also working on an optional implementation of the [namespaced](https://github.com/cloudogu/gitops-patterns/tree/8e1056f#namespaced) pattern, using the [Argo CD operator](https://github.com/argoproj-labs/argocd-operator).

### cluster-resources

The playground installs cluster-resources (like prometheus, grafana, vault, external secrets operator, etc.) via the repo  
`argocd/cluster-resources`.

When installing without Argo CD, the tools are installed using helm imperatively.
We fall back to using imperative helm installation as a kind of neutral ground.

## Jenkins

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

## Monitoring tools

Set the parameter `--monitoring` so the [kube-prometheus-stack](https://github.com/prometheus-operator/kube-prometheus)
via its [helm-chart](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
is being deployed including dashboards for 
- ArgoCD
- Traefik Ingress Controller
- Prometheus
- SCMManager
- Jenkins.


Grafana can be used to query and visualize metrics via prometheus.
It is exposed via ingress, e.g. http://grafana.localhost.
Prometheus is not exposed by default.

In addition, argocd-notifications is set up. Applications deployed with Argo CD now will alert via email to mailhog
the sync status failed, for example.

**Note that this only works with Argo CD so far**

## Secrets Management Tools

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

### dev mode

For testing you can set the parameter `--vault=dev` to deploy vault in development mode. This will lead to
* vault being transient, i.e. all changes during runtime are not persisted. Meaning a restart will reset to default.
* Vault is initialized with some fixed secrets that are used in the example app, see below.
* Vault authorization is initialized with service accounts used in example `SecretStore`s for external secrets operator
* Vault is initialized with the usual `admin/admin` account (can be overriden with `--username` and `--password`)

The secrets are then picked up by the `vault-backend` `SecretStore`s (connects External Secrets Operator with Vault) in
the namespace `argocd-staging` and `argocd-production` namespaces

### prod mode

When using `vault=prod` you'll have to initialize vault manually but on the other hand it will persist changes.

If you want the example app to work, you'll have to manually
* set up vault, unseal it and
* authorize the `vault` service accounts in `argocd-production` and `argocd-staging` namspaces. See `SecretStore`s and
  [dev-post-start.sh](system/secrets/vault/dev-post-start.sh) for an example.


## Example app

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

[![app-repo-vs-gitops-repo](docs/images/app-repo-vs-gitops-repo.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/images/app-repo-vs-gitops-repo.svg "View full size")

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


We recommend using the `--ingress` and `--base-url` Parameters.
With these, the applications are made available as subdomains of `base-url`.

For example, `--base-url=http://localhost` leads to 
http://staging.petclinic-plain.petclinic.localhost/.

The `.petlinic.` part can be overridden using
`--petclinic-base-domain` (for the petlinic examples/exercises), or 
`--nginx-base-domain` (for the nginx examples/exercises).

#### PetClinic with plain k8s resources

[Jenkinsfile](examples/example-apps-via-content-loader/argocd/petclinic-plain/Jenkinsfile) for `plain` deployment

* Staging: http://staging.petclinic-plain.petclinic.localhost/
* Production: http://production.petclinic-plain.petclinic.localhost/  
  Note that you have to accept a [pull request](http://scmm.localhost/scm/repo/argocd/example-apps/pull-requests/) for deployment

#### PetClinic with helm

[Jenkinsfile](examples/example-apps-via-content-loader/argocd/petclinic-helm/Jenkinsfile) for `helm` deployment

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
