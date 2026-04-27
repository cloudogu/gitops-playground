# Overview of all CLI and config options

All options can be set via a [config file](./configuration.schema.json). Most options are also available as CLI parameters.

## Table of Contents

- [Registry](#registry)
- [Jenkins](#jenkins)
- [Multi Tenant](#multi-tenant)
- [Scm](#scm)
- [Application](#application)
- [Content](#content)
- [Features](#features)
  - [Argocd](#feature-argocd)
  - [Mail](#feature-mail)
  - [Monitoring](#feature-monitoring)
  - [Secrets](#feature-secrets)
  - [Ingress](#feature-ingress)
  - [Cert Manager](#feature-cert-manager)

## Registry

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--registry` | `registry.active` | Boolean | `false` | Installs a simple cluster-local registry for demonstration purposes. Warning: Registry does not provide authentication! |
| `--internal-registry-port` | `registry.internalPort` | Integer | `30000` | Port of registry registry. Ignored when a registry*url params are set |
| `--registry-url` | `registry.url` | String | `` | The url of your external registry, used for pushing images |
| `--registry-path` | `registry.path` | String | `` | Optional when registry-url is set |
| `--registry-username` | `registry.username` | String | `` | Optional when registry-url is set |
| `--registry-password` | `registry.password` | String | `` | Optional when registry-url is set |
| `--registry-proxy-url` | `registry.proxyUrl` | String | `` | The url of your proxy-registry. Used in pipelines to authorize pull base images. Use in conjunction with petclinic base image. Used in helm charts when create-image-pull-secrets is set. Use in conjunction with helm.*image fields. |
| `--registry-proxy-path` | `registry.proxyPath` | String | `` | Optional when registry-proxy-url is set and the registry is running on a non root web path. |
| `--registry-proxy-username` | `registry.proxyUsername` | String | `` | Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set. |
| `--registry-proxy-password` | `registry.proxyPassword` | String | `` | Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set. |
| `--registry-username-read-only` | `registry.readOnlyUsername` | String | `` | Optional alternative username for registry-url with read-only permissions that is used when create-image-pull-secrets is set. |
| `--registry-password-read-only` | `registry.readOnlyPassword` | String | `` | Optional alternative password for registry-url with read-only permissions that is used when create-image-pull-secrets is set. |
| `--create-image-pull-secrets` | `registry.createImagePullSecrets` | Boolean | `false` | Create image pull secrets for registry and proxy-registry for all GOP namespaces and helm charts. Uses proxy-username, read-only-username or registry-username (in this order).  Use this if your cluster is not auto-provisioned with credentials for your private registries or if you configure individual helm images to be pulled from the proxy-registry that requires authentication. |
| - | `registry.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `registry.helm.chart` | String | `docker-registry` | Name of the Helm chart |
| - | `registry.helm.repoURL` | String | `https://twuni.github.io/docker-registry.helm` | Repository url from which the Helm chart should be obtained |
| - | `registry.helm.version` | String | `3.0.0` | The version of the Helm chart to be installed |

## Jenkins

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--jenkins` | `jenkins.active` | Boolean | `false` | Installs Jenkins as CI server |
| `--jenkins-skip-restart` | `jenkins.skipRestart` | Boolean | `false` | Skips restarting Jenkins after plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades. |
| `--jenkins-skip-plugins` | `jenkins.skipPlugins` | Boolean | `false` | Skips plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades. |
| `--jenkins-url` | `jenkins.url` | String | `` | The url of your external jenkins |
| `--jenkins-username` | `jenkins.username` | String | `admin` | Mandatory when jenkins-url is set |
| `--jenkins-password` | `jenkins.password` | String | `mK1KDmJOeg6Y` | Mandatory when jenkins-url is set |
| `--jenkins-metrics-username` | `jenkins.metricsUsername` | String | `metrics` | Mandatory when jenkins-url is set and monitoring enabled |
| `--jenkins-metrics-password` | `jenkins.metricsPassword` | String | `metrics` | Mandatory when jenkins-url is set and monitoring enabled |
| `--maven-central-mirror` | `jenkins.mavenCentralMirror` | String | `` | URL for maven mirror, used by applications built in Jenkins |
| `--jenkins-additional-envs` | `jenkins.additionalEnvs` | Map | `[:]` | Set additional environments to Jenkins |
| - | `jenkins.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `jenkins.helm.chart` | String | `jenkins` | Name of the Helm chart |
| - | `jenkins.helm.repoURL` | String | `https://charts.jenkins.io` | Repository url from which the Helm chart should be obtained |
| - | `jenkins.helm.version` | String | `5.9.18` | The version of the Helm chart to be installed |

## Multi Tenant

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--central-gitlab-url` | `multiTenant.gitlab.url` | String | `-` | URL for external Gitlab |
| `--central-gitlab-username` | `multiTenant.gitlab.username` | String | `-` | GitLab username for API access. Must be 'oauth2' when using Personal Access Token (PAT) authentication |
| `--central-gitlab-token` | `multiTenant.gitlab.password` | String | `-` | Password for SCM Manager authentication |
| `--central-gitlab-group-id` | `multiTenant.gitlab.parentGroupId` | String | `-` | Main Group for Gitlab where the GOP creates it's groups/repos |
| `--central-scmm-internal` | `multiTenant.scmManager.internal` | Boolean | `-` | SCM for Central Management is running on the same cluster, so k8s internal URLs can be used for access |
| `--central-scmm-url` | `multiTenant.scmManager.url` | String | `-` | URL for the centralized Management Repo |
| `--central-scmm-username` | `multiTenant.scmManager.username` | String | `-` | CENTRAL SCMM username |
| `--central-scmm-password` | `multiTenant.scmManager.password` | String | `-` | CENTRAL SCMM password |
| `--central-scmm-namespace` | `multiTenant.scmManager.namespace` | String | `-` | Namespace where to find the Central SCMM |
| `--central-argocd-namespace` | `multiTenant.centralArgocdNamespace` | String | `argocd` | Namespace for the centralized Argocd |
| `--dedicated-instance` | `multiTenant.useDedicatedInstance` | Boolean | `false` | Toggles the Dedicated Instances Mode. See docs for more info |

## Scm

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| - | `scm.gitlab.internal` | Boolean | `-` | True if Gitlab is running in the same K8s cluster. For now we only support access by external URL |
| `--gitlab-url` | `scm.gitlab.url` | String | `-` | Base URL for the Gitlab instance |
| `--gitlab-username` | `scm.gitlab.username` | String | `-` | Defaults to: oauth2.0 when PAT token is given. |
| `--gitlab-token` | `scm.gitlab.password` | String | `-` | PAT Token for the account. Needs read/write repo permissions. See docs for mor information |
| `--gitlab-group-id` | `scm.gitlab.parentGroupId` | String | `-` | Number for the Gitlab Group where the repos and subgroups should be created |
| - | `scm.gitlab.gitOpsUsername` | String | `-` | Username for the Gitops User |
| `--scmm-url` | `scm.scmManager.url` | String | `-` | The host of your external scm-manager |
| `--scmm-namespace` | `scm.scmManager.namespace` | String | `-` | Namespace where SCM-Manager should run |
| `--scmm-username` | `scm.scmManager.username` | String | `-` | Mandatory when scmm-url is set |
| `--scmm-password` | `scm.scmManager.password` | String | `-` | Mandatory when scmm-url is set |
| - | `scm.scmManager.helm.values` | Map | `-` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `scm.scmManager.helm.chart` | String | `-` | Name of the Helm chart |
| - | `scm.scmManager.helm.repoURL` | String | `-` | Repository url from which the Helm chart should be obtained |
| - | `scm.scmManager.helm.version` | String | `-` | The version of the Helm chart to be installed |
| `--scmm-skip-restart` | `scm.scmManager.skipRestart` | Boolean | `-` | Skips restarting SCM-Manager after plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.' |
| `--scmm-skip-plugins` | `scm.scmManager.skipPlugins` | Boolean | `-` | Skips plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades. |
| - | `scm.scmManager.gitOpsUsername` | String | `-` | Username for the Gitops User |

## Application

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--config-file` | `application.configFiles` | List&lt;String&gt; | `[]` | - |
| `--config-map` | `application.configMaps` | List&lt;String&gt; | `[]` | - |
| `-d`, `--debug` | `application.debug` | Boolean | `-` | - |
| `-x`, `--trace` | `application.trace` | Boolean | `-` | - |
| `--output-config-file` | `application.outputConfigFile` | Boolean | `false` | - |
| `-v`, `--version` | `application.versionInfoRequested` | Boolean | `false` | - |
| `-h`, `--help` | `application.usageHelpRequested` | Boolean | `false` | - |
| `--insecure` | `application.insecure` | Boolean | `false` | Sets insecure-mode in cURL which skips cert validation |
| `--openshift` | `application.openshift` | Boolean | `false` | When set, openshift specific resources and configurations are applied |
| `--username` | `application.username` | String | `admin` | Set initial admin username |
| `--password` | `application.password` | String | `mK1KDmJOeg6Y` | Set initial admin passwords |
| `-y`, `--yes` | `application.yes` | Boolean | `false` | Skip confirmation |
| `--name-prefix` | `application.namePrefix` | String | `` | Set name-prefix for repos, jobs, namespaces |
| `--destroy` | `application.destroy` | Boolean | `false` | Unroll playground |
| `--pod-resources` | `application.podResources` | Boolean | `false` | Write kubernetes resource requests and limits on each pod |
| `--git-name` | `application.gitName` | String | `Cloudogu` | Sets git author and committer name used for initial commits |
| `--git-email` | `application.gitEmail` | String | `hello@cloudogu.com` | Sets git author and committer email used for initial commits |
| `--base-url` | `application.baseUrl` | String | `` | the external base url (TLD) for all tools, e.g. https://example.com or http://localhost:8080. The individual -url params for argocd, grafana and vault take precedence. |
| `--url-separator-hyphen` | `application.urlSeparatorHyphen` | Boolean | `false` | Use hyphens instead of dots to separate application name from base-url |
| `--mirror-repos` | `application.mirrorRepos` | Boolean | `false` | Changes the sources of deployed tools so they are not pulled from the internet, but are pulled from git and work in air-gapped environments. |
| `--skip-crds` | `application.skipCrds` | Boolean | `false` | Skip installation of CRDs. This requires prior installation of CRDs |
| `--namespace-isolation` | `application.namespaceIsolation` | Boolean | `false` | Configure tools to explicitly work with the given namespaces only, and not cluster-wide. This way GOP can be installed without having cluster-admin permissions. |
| `--netpols` | `application.netpols` | Boolean | `false` | Sets Network Policies |
| `--cluster-admin` | `application.clusterAdmin` | Boolean | `false` | Binds ArgoCD controllers to cluster-admin ClusterRole |
| `-p`, `--profile` | `application.profile` | String | `-` | Use predefined profile (full, only-argocd, operator-mandants aso.) |
| `--gop-namespace` | `application.gopNamespace` | String | `` | If set, GOP stores specific information in this namespace. |

## Content

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| - | `content.namespaces` | List&lt;String&gt; | `[]` | Additional kubernetes namespaces. These are authorized to Argo CD, supplied with image pull secrets, monitored by prometheus, etc. Namespaces can be templates, e.g. ${config.application.namePrefix}staging |
| - | `content.repos` | List&lt;ContentRepositorySchema&gt; | `[]` | ContentLoader repos to push into target environment |
| - | `content.variables` | Map | `[:]` | Additional variables to use in custom templates. |
| - | `content.helmReleases` | List&lt;HelmReleaseSchema&gt; | `[]` | - |
| `--content-whitelist` | `content.useWhitelist` | Boolean | `false` | Enables the whitelist for statics in content templating |
| - | `content.allowedStaticsWhitelist` | Set&lt;String&gt; | `[]` | Whitelist for Statics freemarker is allowing in user templates |

## Features

Configuration of optional features supported by gitops-playground.

### Feature: Argocd

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--argocd` | `features.argocd.active` | Boolean | `false` | Install ArgoCD |
| `--argocd-operator` | `features.argocd.operator` | Boolean | `false` | Install ArgoCD via an already running ArgoCD Operator |
| `--argocd-url` | `features.argocd.url` | String | `` | The URL where argocd is accessible. It has to be the full URL with http:// or https:// |
| - | `features.argocd.env` | List&lt;java.util.Map<java.lang.String, java.lang.String>&gt; | `-` | Pass a list of env vars to Argo CD components. Currently only works with operator |
| `--argocd-email-from` | `features.argocd.emailFrom` | String | `argocd@example.org` | Notifications, define Argo CD sender email address |
| `--argocd-email-to-user` | `features.argocd.emailToUser` | String | `app-team@example.org` | Notifications, define Argo CD user / app-team recipient email address |
| `--argocd-email-to-admin` | `features.argocd.emailToAdmin` | String | `infra@example.org` | Notifications, define Argo CD admin recipient email address |
| `--argocd-resource-inclusions-cluster` | `features.argocd.resourceInclusionsCluster` | String | `` | Internal Kubernetes API Server URL https://IP:PORT (kubernetes.default.svc). Needed in argocd-operator resourceInclusions. Use this parameter if argocd.operator=true and NOT running inside a Pod (remote mode). Full URL needed, for example: https://100.125.0.1:443 |
| `--argocd-namespace` | `features.argocd.namespace` | String | `argocd` | Defines the kubernetes namespace for ArgoCD |
| - | `features.argocd.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |

### Feature: Mail

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--smtp-address` | `features.mail.smtpAddress` | String | `` | Sets smtp port of external Mailserver |
| `--smtp-port` | `features.mail.smtpPort` | Integer | `-` | Sets smtp port of external Mailserver |
| `--smtp-user` | `features.mail.smtpUser` | String | `` | Sets smtp username for external Mailserver |
| `--smtp-password` | `features.mail.smtpPassword` | String | `` | Sets smtp password of external Mailserver |

### Feature: Monitoring

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--metrics`, `--monitoring` | `features.monitoring.active` | Boolean | `false` | Installs the Kube-Prometheus-Stack. This includes Prometheus, the Prometheus operator, Grafana and some extra resources |
| `--grafana-url` | `features.monitoring.grafanaUrl` | String | `` | Sets url for grafana |
| `--grafana-email-from` | `features.monitoring.grafanaEmailFrom` | String | `grafana@example.org` | Notifications, define grafana alerts sender email address |
| `--grafana-email-to` | `features.monitoring.grafanaEmailTo` | String | `infra@example.org` | Notifications, define grafana alerts recipient email address |
| `--grafana-image` | `features.monitoring.helm.grafanaImage` | String | `` | Sets image for grafana |
| `--grafana-sidecar-image` | `features.monitoring.helm.grafanaSidecarImage` | String | `` | Sets image for grafana's sidecar |
| `--prometheus-image` | `features.monitoring.helm.prometheusImage` | String | `` | Sets image for prometheus |
| `--prometheus-operator-image` | `features.monitoring.helm.prometheusOperatorImage` | String | `` | Sets image for prometheus-operator |
| `--prometheus-config-reloader-image` | `features.monitoring.helm.prometheusConfigReloaderImage` | String | `` | Sets image for prometheus-operator's config-reloader |
| - | `features.monitoring.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `features.monitoring.helm.chart` | String | `kube-prometheus-stack` | Name of the Helm chart |
| - | `features.monitoring.helm.repoURL` | String | `https://prometheus-community.github.io/helm-charts` | Repository url from which the Helm chart should be obtained |
| - | `features.monitoring.helm.version` | String | `80.2.2` | The version of the Helm chart to be installed |

### Feature: Secrets

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--external-secrets-image` | `features.secrets.externalSecrets.helm.image` | String | `` | Sets image for external secrets operator |
| `--external-secrets-certcontroller-image` | `features.secrets.externalSecrets.helm.certControllerImage` | String | `` | Sets image for external secrets operator's controller |
| `--external-secrets-webhook-image` | `features.secrets.externalSecrets.helm.webhookImage` | String | `` | Sets image for external secrets operator's webhook |
| - | `features.secrets.externalSecrets.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `features.secrets.externalSecrets.helm.chart` | String | `external-secrets` | Name of the Helm chart |
| - | `features.secrets.externalSecrets.helm.repoURL` | String | `https://charts.external-secrets.io` | Repository url from which the Helm chart should be obtained |
| - | `features.secrets.externalSecrets.helm.version` | String | `0.9.16` | The version of the Helm chart to be installed |
| `--vault-url` | `features.secrets.vault.url` | String | `` | Sets url for vault ui |
| `--vault-image` | `features.secrets.vault.helm.image` | String | `` | Sets image for vault |
| - | `features.secrets.vault.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `features.secrets.vault.helm.chart` | String | `vault` | Name of the Helm chart |
| - | `features.secrets.vault.helm.repoURL` | String | `https://helm.releases.hashicorp.com` | Repository url from which the Helm chart should be obtained |
| - | `features.secrets.vault.helm.version` | String | `0.25.0` | The version of the Helm chart to be installed |

### Feature: Ingress

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--ingress` | `features.ingress.active` | Boolean | `false` | Sets and enables Ingress Controller |
| `--ingress-image` | `features.ingress.helm.image` | String | `` | The image of the Helm chart to be installed |
| - | `features.ingress.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `features.ingress.helm.chart` | String | `traefik` | Name of the Helm chart |
| - | `features.ingress.helm.repoURL` | String | `https://traefik.github.io/charts` | Repository url from which the Helm chart should be obtained |
| - | `features.ingress.helm.version` | String | `39.0.0` | The version of the Helm chart to be installed |

### Feature: Cert Manager

| CLI | Config key | Type | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `--cert-manager` | `features.certManager.active` | Boolean | `false` | Sets and enables Cert Manager |
| `--cert-manager-issuer` | `features.certManager.issuer` | String | `cluster-selfsigned` | Sets and enables Cert Manager |
| `--cert-manager-image` | `features.certManager.helm.image` | String | `` | Sets image for Cert Manager |
| `--cert-manager-webhook-image` | `features.certManager.helm.webhookImage` | String | `` | Sets webhook Image for Cert Manager |
| `--cert-manager-cainjector-image` | `features.certManager.helm.cainjectorImage` | String | `` | Sets cainjector Image for Cert Manager |
| `--cert-manager-acme-solver-image` | `features.certManager.helm.acmeSolverImage` | String | `` | Sets acmeSolver Image for Cert Manager |
| `--cert-manager-startup-api-check-image` | `features.certManager.helm.startupAPICheckImage` | String | `` | Sets startupAPICheck Image for Cert Manager |
| - | `features.certManager.helm.values` | Map | `[:]` | Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration |
| - | `features.certManager.helm.chart` | String | `cert-manager` | Name of the Helm chart |
| - | `features.certManager.helm.repoURL` | String | `https://charts.jetstack.io` | Repository url from which the Helm chart should be obtained |
| - | `features.certManager.helm.version` | String | `1.19.4` | The version of the Helm chart to be installed |

