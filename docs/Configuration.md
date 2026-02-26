# Overview of all CLI and config options

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
    - [Ingress](#ingress)
    - [Cert Manager](#cert-manager)
- [Content](#content)
- [Multitenant](#multitenant)
  - [SCMM](#scm-managercentral)
  - [GITLAB](#gitlabcentral)

## Application

| CLI                      | Config                             | Default | Type     | Description                                                                   |
|--------------------------|------------------------------------|---------|----------|-------------------------------------------------------------------------------|
| `--config-file`          | -                                  | `''` | String   | Config file path                                                              |
| `--config-map`           | -                                  | `''` | String   | Config map name                                                               |
| `-d, --debug`            | `application.debug`                | - | Boolean  | Enable debug mode                                                             |
| `-x, --trace`            | `application.trace`                | - | Boolean  | Enable trace mode                                                             |
| `--output-config-file`   | `application.outputConfigFile`     | `false` | Boolean  | Output configuration file                                                     |
| `-v, --version`          | `application.versionInfoRequested` | `false` | Boolean  | Display version and license info                                              |
| `-h, --help`             | `application.usageHelpRequested`   | `false` | Boolean  | Display help message                                                          |
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
| `-p, --profile`         | `application.profile`              | `''` | String   | Sets a profile for pre-defined parameter                                      |


## Registry

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

## Jenkins

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

## Scm(Tenant)

| CLI              | Config                          | Default      | Type                    | Description                                                           |
|------------------|---------------------------------|--------------|-------------------------|-----------------------------------------------------------------------|
| `--scm-provider` | `scmTenant.scmProviderType`     | `SCM_MANAGER` | ScmProviderType         | Specifies the SCM provider type. Possible values: `SCM_MANAGER`, `GITLAB`. |
|                  | `scmTenant.gitOpsUsername`      | `''`         | String                  | The username for the GitOps user.                                      |
|                  | `scmTenant.gitlab`              | `''`         | GitlabTenantConfig      | Configuration for GitLab, including URL, username, token, and parent group ID. |
|                  | `scmTenant.scmManager`          | `''`         | ScmManagerTenantConfig  | Configuration for SCM Manager, such as internal setup or plugin handling. |

## SCMM(Tenant)

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


## Gitlab(Tenant)

| CLI                 | Config             | Default   | Type   | Description                                                                                                |
|---------------------|--------------------|-----------|--------|------------------------------------------------------------------------------------------------------------|
| `--gitlab-url`      | `gitlabTenant.url` | `''`      | String | Base URL for the GitLab instance.                                                                          |
| `--gitlab-username` | `gitlabTenant.username` | `'oauth2.0'` | String | Defaults to: `oauth2.0` when a PAT token is provided.                                                      |
| `--gitlab-token`    | `gitlabTenant.password` | `''`      | String | PAT token for the account.                                                                                 |
| `--gitlab-group-id` | `gitlabTenant.parentGroupId` | `''`  | String | The numeric ID for the GitLab Group where repositories and subgroups should be created.                    |
|                     | `gitlabTenant.internal` | `false`  | Boolean | Indicates if GitLab is running in the same Kubernetes cluster. Currently only external URLs are supported. |


## Images

| CLI | Config | Default | Type | Description |
|-----|--------|---------|------|-------------|
| `--kubectl-image` | `images.kubectl` | `"bitnamilegacy/kubectl:1.29"` | String | Sets image for kubectl |
| `--helm-image` | `images.helm` | `"ghcr.io/cloudogu/helm:3.16.4-1"` | String | Sets image for helm |
| `--kubeval-image` | `images.kubeval` | `"ghcr.io/cloudogu/helm:3.16.4-1"` | String | Sets image for kubeval |
| `--helmkubeval-image` | `images.helmKubeval` | `"ghcr.io/cloudogu/helm:3.16.4-1"` | String | Sets image for helmkubeval |
| `--yamllint-image` | `images.yamllint` | `"cytopia/yamllint:1.25-0.7"` | String | Sets image for yamllint |
| `--petclinic-image` | `images.petclinic` | `'eclipse-temurin:17-jre-alpine'` | String | Sets image for petclinic |
| `--maven-image` | `images.maven` | `''` | String | Sets image for maven |

## ArgoCD

| CLI | Config                                      | Default | Type    | Description                                 |
|-----|---------------------------------------------|---------|---------|---------------------------------------------|
| `--argocd` | `features.argocd.active`                    | `false` | Boolean | Installs ArgoCD as GitOps CD tool           |
| `--argocd-operator` | `features.argocd.operator`                  | `false` | Boolean | Install ArgoCD operator                     |
| `--argocd-url` | `features.argocd.url`                       | `''` | String  | The url of your external argocd             |
| `--argocd-email-from` | `features.argocd.emailFrom`                 | `'argocd@example.org'` | String  | Email from address for ArgoCD notifications |
| `--argocd-email-to-user` | `features.argocd.emailToUser`               | `'app-team@example.org'` | String  | Email to address for user notifications     |
| `--argocd-email-to-admin` | `features.argocd.emailToAdmin`              | `'infra@example.org'` | String  | Email to address for admin notifications    |
| `--argocd-resource-inclusions-cluster` | `features.argocd.resourceInclusionsCluster` | `''` | String  | ArgoCD resource inclusions for cluster      |
| `--argocd-namespace` | `features.argocd.namespace`                 | `'argocd'` | String  | ArgoCD namespace                            |
| - | `features.argocd.env`                       | - | List    | Environment variables for ArgoCD            |
| - | `features.argocd.values`                    | - | Map     | To override ArgoCD Operator file            |

## Mail

| CLI | Config                       | Default | Type | Description                                                 |
|-----|------------------------------|---------|------|-------------------------------------------------------------|
| `--mail` | `features.mail.mailServer`   | `false` | Boolean | Installs a dedicated mail server                            |
| `--mail-url` | `features.mail.mailUrl`      | `''` | String | The url of the mail server's frontend                       |
| `--smtp-address` | `features.mail.smtpAddress`  | `''` | String | SMTP server address                                         |
| `--smtp-port` | `features.mail.smtpPort`     | `null` | Integer | SMTP server port                                            |
| `--smtp-user` | `features.mail.smtpUser`     | `''` | String | SMTP username                                               |
| `--smtp-password` | `features.mail.smtpPassword` | `''` | String | SMTP password                                               |
| `--mail-image` | `features.mail.helm.image`   | `'ghcr.io/cloudogu/mailhog:v1.0.1'` | String | Container image to use for the mail server                  |
| - | `features.mail.helm.chart`   | `'mailhog'` | String | Name of the Helm chart                                      |
| - | `features.mail.helm.repoURL` | `'https://codecentric.github.io/helm-charts'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.mail.helm.version` | `'5.0.1'` | String | The version of the Helm chart to be installed               |
| - | `features.mail.helm.values`  | `[:]` | Map | Helm values of the chart                                    |

## Monitoring

| CLI                                  | Config                                                   | Default                                                | Type    | Description                                                 |
|--------------------------------------|----------------------------------------------------------|--------------------------------------------------------|---------|-------------------------------------------------------------|
| `--metrics, --monitoring`            | `features.monitoring.active`                             | `false`                                                | Boolean | Installs monitoring stack (Prometheus, Grafana)             |
| `--grafana-url`                      | `features.monitoring.grafanaUrl`                         | `''`                                                   | String  | The url of your external grafana                            |
| `--grafana-email-from`               | `features.monitoring.grafanaEmailFrom`                   | `'grafana@example.org'`                                | String  | Email from address for Grafana notifications                |
| `--grafana-email-to`                 | `features.monitoring.grafanaEmailTo`                     | `'infra@example.org'`                                  | String  | Email to address for Grafana notifications                  |
| `--grafana-image`                    | `features.monitoring.helm.grafanaImage`                  | `''`                                                   | String  | Grafana container image                                     |
| `--grafana-sidecar-image`            | `features.monitoring.helm.grafanaSidecarImage`           | `''`                                                   | String  | Grafana sidecar container image                             |
| `--prometheus-image`                 | `features.monitoring.helm.prometheusImage`               | `''`                                                   | String  | Prometheus container image                                  |
| `--prometheus-operator-image`        | `features.monitoring.helm.prometheusOperatorImage`       | `''`                                                   | String  | Prometheus operator container image                         |
| `--prometheus-config-reloader-image` | `features.monitoring.helm.prometheusConfigReloaderImage` | `''`                                                   | String  | Prometheus config reloader container image                  |
| -                                    | `features.monitoring.helm.chart`                         | `'kube-prometheus-stack'`                              | String  | Name of the Helm chart                                      |
| -                                    | `features.monitoring.helm.repoURL`                       | `'https://prometheus-community.github.io/helm-charts'` | String  | Repository url from which the Helm chart should be obtained |
| -                                    | `features.monitoring.helm.version`                       | `'80.2.2'`                                             | String  | The version of the Helm chart to be installed               |
| -                                    | `features.monitoring.helm.values`                        | `[:]`                                                  | Map     | Helm values of the chart                                    |

## Secrets

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

## Ingress

| CLI | Config | Default                              | Type | Description |
|-----|--------|--------------------------------------|------|-------------|
| `--ingress` | `features.ingress.active` | `false`                              | Boolean | Install Ingress controller |
| `--ingress-image` | `features.ingress.helm.image` | `''`                                 | String | Ingress controller image |
| - | `features.ingress.helm.chart` | `'traefik'`                          | String | Name of the Helm chart |
| - | `features.ingress.helm.repoURL` | `'https://traefik.github.io/charts'` | String | Repository url from which the Helm chart should be obtained |
| - | `features.ingress.helm.version` | `'39.0.0'`                           | String | The version of the Helm chart to be installed |
| - | `features.ingress.helm.values` | `[:]`                                | Map | Helm values of the chart |

## Cert Manager

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

## Content

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

## MultiTenant

| CLI                          | Config                              | Default       | Type                     | Description                                                    |
|------------------------------|-------------------------------------|---------------|--------------------------|----------------------------------------------------------------|
| `--dedicated-instance`       | `multiTenant.useDedicatedInstance`  | `false`       | Boolean                  | Toggles the Dedicated Instances Mode. See docs for more info   |
| `--central-argocd-namespace` | `multiTenant.centralArgocdNamespace`| `'argocd'`    | String                   | Namespace for the centralized Argocd                           |
| `--central-scm-provider`     | `multiTenant.scmProviderType`       | `SCM_MANAGER` | ScmProviderType          | The SCM provider type. Possible values: `SCM_MANAGER`, `GITLAB`|
|                              | `multiTenant.gitlab`                | ``        | GitlabCentralConfig      | Config for GITLAB                                              |
|                              | `multiTenant.scmManager`            | ``        | ScmManagerCentralConfig  | Config for SCM Manager                                         |

## Gitlab(Central)
If you decide to use GOP in an multi-tenant setup, the "central" instance of Gitlab will be the source of truth of the whole deployment.

| CLI                          | Config                         | Default     | Type    | Description                                                      |
|------------------------------|--------------------------------|-------------|---------|------------------------------------------------------------------|
| `--central-gitlab-url`       | `multiTenant.gitlab.url`       | `''`        | String  | URL for external Gitlab                                          |
| `--central-gitlab-username`  | `multiTenant.gitlab.username`  | `'oauth2.0'`| String  | Username for GitLab authentication                               |
| `--central-gitlab-token`     | `multiTenant.gitlab.password`  | `''`        | String  | Password for SCM Manager authentication                          |
| `--central-gitlab-group-id`  | `multiTenant.gitlab.parentGroupId` | `''`    | String  | Main Group for Gitlab where the GOP creates it's groups/repos    |
|                              | `multiTenant.gitlab.internal`  | `false`     | Boolean | SCM is running on the same cluster (only external supported now) |

## Scm-Manager(Central)
If you decide to use GOP in an multi-tenant setup, the "central" instance of SCMManager will be the source of truth of the whole deployment.

| CLI                          | Config                              | Default         | Type    | Description                                                                          |
|------------------------------|-------------------------------------|-----------------|---------|--------------------------------------------------------------------------------------|
| `--central-scmm-internal`    | `multiTenant.scmManager.internal`   | `false`         | Boolean | SCM for Central Management is running on the same cluster, so k8s internal URLs can be used for access |
| `--central-scmm-url`         | `multiTenant.scmManager.url`        | `''`            | String  | URL for the centralized Management Repo                                              |
| `--central-scmm-username`    | `multiTenant.scmManager.username`   | `''`            | String  | CENTRAL SCMM USERNAME                                                                |
| `--central-scmm-password`    | `multiTenant.scmManager.password`   | `''`            | String  | CENTRAL SCMM Password                                                                |
| `--central-scmm-root-path`   | `multiTenant.scmManager.rootPath`   | `'repo'`        | String  | Root path for SCM Manager                                                            |
| `--central-scmm-namespace`   | `multiTenant.scmManager.namespace`  | `'scm-manager'` | String  | Namespace where to find the Central SCMM                                             |


## Configuration file

You can also use a configuration file to specify the parameters (`--config-file` or `--config-map`).
That file must be a YAML file. 

You can use `--output-config-file` to output the current config as set by defaults and CLI parameters.
In addition, For easier validation and auto-completion, we provide a [schema file](https://raw.githubusercontent.com/cloudogu/gitops-playground/main/docs/configuration.schema.json).


## Apply via Docker

To apply your config file to a GOP instance in a docker container, simply mount the file like so:
```bash
docker run --rm -t --pull=always -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    -v $(pwd)/gitops-playground.yaml:/config/gitops-playground.yaml \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd --config-file=/config/gitops-playground.yaml
```


## Apply via kubectl

To apply your config file to a GOP instance within a kubernetes pod, first create a config map:
```bash
kubectl create configmap gitops-config --from-file=gitops-playground.yaml
```

And then reference it when starting the GOP:
```bash

kubectl run gitops-playground -i --tty --restart=Never \
  --overrides='{ "spec": { "serviceAccount": "gitops-playground-job-executer" } }' \
  --image ghcr.io/cloudogu/gitops-playground \
  -- --yes --argocd --config-map=gitops-config
```


## Print all CLI parameters

To get a full list of all supported CLI/config options, run GOP with '--help'
```shell
docker run -t --rm ghcr.io/cloudogu/gitops-playground --help
```
