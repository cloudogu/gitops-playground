package com.cloudogu.gitops.config

interface ConfigConstants {
    companion object {
        const val BINARY_NAME = "apply-ng"
        const val APP_NAME = "gitops-playground (GOP)"
        const val APP_DESCRIPTION = "CLI-tool to deploy gitops-playground."

        // group registry
        const val REGISTRY_ENABLE_DESCRIPTION = "Installs a simple cluster-local registry for demonstration purposes. Warning: Registry does not provide authentication!"
        const val REGISTRY_DESCRIPTION = "Config parameters for Registry"
        const val REGISTRY_INTERNAL_PORT_DESCRIPTION = "Port of registry registry. Ignored when a registry*url params are set"
        const val REGISTRY_URL_DESCRIPTION = "The url of your external registry, used for pushing images"
        const val REGISTRY_PATH_DESCRIPTION = "Optional when registry-url is set"
        const val REGISTRY_USERNAME_DESCRIPTION = "Optional when registry-url is set"
        const val REGISTRY_PASSWORD_DESCRIPTION = "Optional when registry-url is set"

        const val REGISTRY_PROXY_URL_DESCRIPTION = "The url of your proxy-registry. Used in pipelines to authorize pull base images. Use in conjunction with petclinic base image. Used in helm charts when create-image-pull-secrets is set. Use in conjunction with helm.*image fields."
        const val REGISTRY_PROXY_PATH_DESCRIPTION = "Optional when registry-proxy-url is set and the registry is running on a non root web path."
        const val REGISTRY_PROXY_USERNAME_DESCRIPTION = "Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set."
        const val REGISTRY_PROXY_PASSWORD_DESCRIPTION = "Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set."

        const val REGISTRY_USERNAME_RO_DESCRIPTION = "Optional alternative username for registry-url with read-only permissions that is used when create-image-pull-secrets is set."
        const val REGISTRY_PASSWORD_RO_DESCRIPTION = "Optional alternative password for registry-url with read-only permissions that is used when create-image-pull-secrets is set."
        const val REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION = "Create image pull secrets for registry and proxy-registry for all GOP namespaces and helm charts. Uses proxy-username, read-only-username or registry-username (in this order).  Use this if your cluster is not auto-provisioned with credentials for your private registries or if you configure individual helm images to be pulled from the proxy-registry that requires authentication."
        const val REGISTRY_NAMESPACE = "Optional defines the kubernetes namespace for registry."

        const val FEATURES_DESCRIPTION = "Config parameters for features or tools"

        const val CONTENT_DESCRIPTION = "Config parameters for content, i.e. end-user or tenant applications as opposed to cluster-resources"

        // ContentLoader
        const val CONTENT_NAMESPACES_DESCRIPTION = "Additional kubernetes namespaces. These are authorized to Argo CD, supplied with image pull secrets, monitored by prometheus, etc. Namespaces can be templates, e.g. \${config.application.namePrefix}staging"
        const val CONTENT_REPO_DESCRIPTION = "ContentLoader repos to push into target environment"
        const val CONTENT_REPO_URL_DESCRIPTION = "URL of the content repo. Mandatory for each type."
        const val CONTENT_REPO_PATH_DESCRIPTION = "Path within the content repo to process"
        const val CONTENT_REPO_REF_DESCRIPTION = "Reference for a specific branch, tag, or commit. Emtpy defaults to default branch of the repo. With type MIRROR: ref must not be a commit hash; Choosing a ref only mirrors the ref but does not delete other branches/tags!"
        const val CONTENT_REPO_TARGET_REF_DESCRIPTION = "Reference for a specific branch or tag in the target repo of a MIRROR or COPY repo. If ref is a tag, targetRef is treated as tag as well. Except: targetRef is full ref like refs/heads/my-branch or refs/tags/my-tag. Empty defaults to the source ref."
        const val CONTENT_REPO_CREDENTIALS_DESCRIPTION = "Credentials Object to authenticate against content repo. Allows using a K8s Secret"
        const val CONTENT_REPO_TEMPLATING_DESCRIPTION = "When true, template all files ending in .ftl within the repo"
        const val CONTENT_REPO_TYPE_DESCRIPTION = "ContentLoader Repos can either be:\ncopied (only the files, starting on ref, starting at path within the repo. Requires target)\n, mirrored (FORCE pushes ref or the whole git repo if no ref set). Requires target, does not allow path and template.)\nfolderBased (folder structure is interpreted as repos. That is, root folder becomes namespace in SCM, sub folders become repository names in SCM, files are copied. Requires target.)"
        const val CONTENT_REPO_TARGET_DESCRIPTION = "Target repo for the repository in the for of namespace/name. Must contain one slash to separate namespace from name."
        const val CONTENT_REPO_TARGET_OVERWRITE_MODE_DESCRIPTION = "This defines, how customer repos will be updated.\nINIT - push only if repo does not exist.\nRESET - delete all files after cloning source - files not in content are deleted\nUPGRADE - clone and copy - existing files will be overwritten, files not in content are kept. For type: MIRROR reset and upgrade have same result: in both cases source repo will be force pushed to target repo."
        const val CONTENT_REPO_CREATE_JENKINS_JOB_DESCRIPTION = "If true, creates a Jenkins job, if jenkinsfile exists in one of the content repo's branches."
        const val CONTENT_VARIABLES_DESCRIPTION = "Additional variables to use in custom templates."
        const val CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION = "Enables the whitelist for statics in content templating"
        const val CONTENT_STATICSWHITELIST_DESCRIPTION = "Whitelist for Statics freemarker is allowing in user templates"
        const val CONTENT_HELM_RELEASE_NAME_DESCRIPTION = "Logical name of the Helm release. Used as the feature folder name under 'apps/<name>' and as default for 'releaseName' if not set."

        const val CONTENT_HELM_RELEASE_REPO_URL_DESCRIPTION = "Helm repository URL to fetch the chart from. Use an HTTP(S) Helm repo (must provide an index.yaml) or an OCI registry URL (oci://...)."
        const val CONTENT_HELM_RELEASE_CHART_DESCRIPTION = "Helm chart name to install. For HTTP(S) repos this is the chart name from the repo index; for OCI this is the chart artifact name."
        const val CONTENT_HELM_RELEASE_VERSION_DESCRIPTION = "Chart version to deploy. Required for Helm charts in Argo CD. For HTTP(S) Helm repos you may use a SemVer range like '*' to always pick the newest version. For OCI registries, specify an explicit version/tag."
        const val CONTENT_HELM_RELEASE_NAMESPACE_DESCRIPTION = "Kubernetes namespace to deploy the release into."
        const val CONTENT_HELM_RELEASE_RELEASE_NAME_DESCRIPTION = "Helm release name. If empty, the value of 'name' is used."
        const val CONTENT_HELM_RELEASE_VALUES_FILE_DESCRIPTION = "Optional path to a YAML values file to load Helm values from.The file must be accessible locally on the machine running GOP. Inline 'values' will be merged on top (inline overrides file)."
        const val CONTENT_HELM_RELEASE_VALUES_DESCRIPTION = "Optional inline Helm values. These values are merged on top of 'valuesFile' (if set) and override keys from the file. Use this for small overrides without maintaining a separate file."

        // group jenkins
        const val JENKINS_ENABLE_DESCRIPTION = "Installs Jenkins as CI server"
        const val JENKINS_SKIP_RESTART_DESCRIPTION = "Skips restarting Jenkins after plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades."
        const val JENKINS_SKIP_PLUGINS_DESCRIPTION = "Skips plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades."
        const val JENKINS_DESCRIPTION = "Config parameters for Jenkins CI/CD Pipeline Server"
        const val JENKINS_URL_DESCRIPTION = "The url of your external jenkins"
        const val JENKINS_USERNAME_DESCRIPTION = "Mandatory when jenkins-url is set"
        const val JENKINS_PASSWORD_DESCRIPTION = "Mandatory when jenkins-url is set"
        const val JENKINS_METRICS_USERNAME_DESCRIPTION = "Mandatory when jenkins-url is set and monitoring enabled"
        const val JENKINS_METRICS_PASSWORD_DESCRIPTION = "Mandatory when jenkins-url is set and monitoring enabled"
        const val JENKINS_IMAGE_DESCRIPTION = "Sets image for Jenkins"
        const val MAVEN_CENTRAL_MIRROR_DESCRIPTION = "URL for maven mirror, used by applications built in Jenkins"
        const val JENKINS_ADDITIONAL_ENVS_DESCRIPTION = "Set additional environments to Jenkins"
        const val JENKINS_NAMESPACE = "Optional defines the kubernetes namespace for Jenkins."

        // group scmm
        const val SCM_DESCRIPTION = "Config parameters for Scm"
        const val GIT_NAME_DESCRIPTION = "Sets git author and committer name used for initial commits"
        const val GIT_EMAIL_DESCRIPTION = "Sets git author and committer email used for initial commits"

        //MutliTentant
        const val MULTITENANT_DESCRIPTION = "Multi Tenant Configs"

        // group remote
        const val INSECURE_DESCRIPTION = "Sets insecure-mode in cURL which skips cert validation"

        // group tool configuration
        const val APPLICATION_DESCRIPTION = "Application configuration parameter for GOP"
        const val GRAFANA_IMAGE_DESCRIPTION = "Sets image for grafana"
        const val GRAFANA_SIDECAR_IMAGE_DESCRIPTION = "Sets image for grafana's sidecar"
        const val PROMETHEUS_IMAGE_DESCRIPTION = "Sets image for prometheus"
        const val PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION = "Sets image for prometheus-operator"
        const val PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION = "Sets image for prometheus-operator's config-reloader"
        const val EXTERNAL_SECRETS_IMAGE_DESCRIPTION = "Sets image for external secrets operator"
        const val EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION = "Sets image for external secrets operator's controller"
        const val EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION = "Sets image for external secrets operator's webhook"
        const val VAULT_IMAGE_DESCRIPTION = "Sets image for vault"
        const val BASE_URL_DESCRIPTION = "the external base url (TLD) for all tools, e.g. https://example.com or http://localhost:8080. The individual -url params for argocd, grafana and vault take precedence."
        const val URL_SEPARATOR_HYPHEN_DESCRIPTION = "Use hyphens instead of dots to separate application name from base-url"
        const val SKIP_CRDS_DESCRIPTION = "Skip installation of CRDs. This requires prior installation of CRDs"
        const val NAMESPACE_ISOLATION_DESCRIPTION = "Configure tools to explicitly work with the given namespaces only, and not cluster-wide. This way GOP can be installed without having cluster-admin permissions."
        const val MIRROR_REPOS_DESCRIPTION = "Changes the sources of deployed tools so they are not pulled from the internet, but are pulled from git and work in air-gapped environments."
        const val NETPOLS_DESCRIPTION = "Sets Network Policies"
        const val CLUSTER_ADMIN_DESCRIPTION = "Binds ArgoCD controllers to cluster-admin ClusterRole"
        const val OPENSHIFT_DESCRIPTION = "When set, openshift specific resources and configurations are applied"
        const val APPLICATION_PROFIL = "Use predefined profile (full, only-argocd, operator-mandants aso.)"
        const val APPLICATION_GOP_NAMESPACE = "If set, GOP stores specific information in this namespace."
        const val APPLICATION_NAMESPACE = "If set, GOP uses the same Kubernetes namespace for all tools and examples. Attention! Only use for test purposes."
        // group metrics
        const val MONITORING_DESCRIPTION = "Config parameters for the Monitoring system (prometheus)"
        const val MONITORING_ENABLE_DESCRIPTION = "Installs the Kube-Prometheus-Stack. This includes Prometheus, the Prometheus operator, Grafana and some extra resources"
        const val MONITORING_NAMESPACE = "Optional defines the kubernetes namespace for monitoring."
        const val GRAFANA_URL_DESCRIPTION = "Sets url for grafana"
        const val GRAFANA_EMAIL_FROM_DESCRIPTION = "Notifications, define grafana alerts sender email address"
        const val GRAFANA_EMAIL_TO_DESCRIPTION = "Notifications, define grafana alerts recipient email address"

        // group vault / secrets
        const val SECRETS_DESCRIPTION = "Config parameters for the secrets management"
        const val ESO_DESCRIPTION = "Config parameters for the external secrets operator"
        const val VAULT_DESCRIPTION = "Config parameters for the secrets-vault"
        const val VAULT_ENABLE_DESCRIPTION = "Installs Hashicorp vault and the external secrets operator. Possible values: dev, prod."
        const val VAULT_URL_DESCRIPTION = "Sets url for vault ui"
        const val SECRETS_NAMESPACE = "Optional defines the kubernetes namespace for secrets."

        // group external Mailserver
        const val MAIL_DESCRIPTION = "Config parameters for mail servers"
        const val SMTP_ADDRESS_DESCRIPTION = "Sets smtp port of external Mailserver"
        const val SMTP_PORT_DESCRIPTION = "Sets smtp port of external Mailserver"
        const val SMTP_USER_DESCRIPTION = "Sets smtp username for external Mailserver"
        const val SMTP_PASSWORD_DESCRIPTION = "Sets smtp password of external Mailserver"

        // group debug
        const val DEBUG_DESCRIPTION = "Debug output"
        const val TRACE_DESCRIPTION = "Debug + Show each command executed (set -x)"

        // group configuration
        const val USERNAME_DESCRIPTION = "Set initial admin username"
        const val PASSWORD_DESCRIPTION = "Set initial admin passwords"
        const val PIPE_YES_DESCRIPTION = "Skip confirmation"
        const val NAME_PREFIX_DESCRIPTION = "Set name-prefix for repos, jobs, namespaces"
        const val DESTROY_DESCRIPTION = "Unroll playground"
        const val CONFIG_FILE_DESCRIPTION = "Config file for the application"
        const val CONFIG_MAP_DESCRIPTION = "Kubernetes configuration map. Should contain a key `config.yaml`."
        const val OUTPUT_CONFIG_FILE_DESCRIPTION = "Output current config as config file as much as possible"
        const val POD_RESOURCES_DESCRIPTION = "Write kubernetes resource requests and limits on each pod"

        // group ArgoCD Operator
        const val ARGOCD_DESCRIPTION = "Config Parameter for the ArgoCD Operator"
        const val ARGOCD_ENABLE_DESCRIPTION = "Install ArgoCD"
        const val ARGOCD_URL_DESCRIPTION = "The URL where argocd is accessible. It has to be the full URL with http:// or https://"
        const val ARGOCD_EMAIL_FROM_DESCRIPTION = "Notifications, define Argo CD sender email address"
        const val ARGOCD_EMAIL_TO_USER_DESCRIPTION = "Notifications, define Argo CD user / app-team recipient email address"
        const val ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION = "Notifications, define Argo CD admin recipient email address"
        const val ARGOCD_OPERATOR_DESCRIPTION = "Install ArgoCD via an already running ArgoCD Operator"
        const val ARGOCD_ENV_DESCRIPTION = "Pass a list of env vars to Argo CD components. Currently only works with operator"
        const val ARGOCD_RESOURCE_INCLUSIONS_CLUSTER = "Internal Kubernetes API Server URL https://IP:PORT (kubernetes.default.svc). Needed in argocd-operator resourceInclusions. Use this parameter if argocd.operator=true and NOT running inside a Pod (remote mode). Full URL needed, for example: https://100.125.0.1:443"
        const val ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION = "Defines the kubernetes namespace for ArgoCD"

        // group ingress-class
        const val INGRESS_DESCRIPTION = "Config parameters for the Ingress Controller"
        const val INGRESS_ENABLE_DESCRIPTION = "Sets and enables Ingress Controller"
        const val INGRESS_NAMESPACE = "Optional defines the kubernetes namespace for Ingress Controller"

        // group CERTMANAGER
        const val CERTMANAGER_DESCRIPTION = "Config parameters for the Cert Manager"
        const val CERTMANAGER_ENABLE_DESCRIPTION = "Sets and enables Cert Manager"
        const val CERTMANAGER_IMAGE_DESCRIPTION = "Sets image for Cert Manager"
        const val CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION = "Sets webhook Image for Cert Manager"
        const val CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION = "Sets cainjector Image for Cert Manager"
        const val CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION = "Sets acmeSolver Image for Cert Manager"
        const val CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION = "Sets startupAPICheck Image for Cert Manager"
        const val CERTMANAGER_NAMESPACE = "Optional defines the kubernetes namespace for Cert Manager"

        // group helm
        const val HELM_CONFIG_DESCRIPTION = "Common Config parameters for the Helm package manager: Name of Chart (chart), URl of Helm-Repository (repoURL) and Chart Version (version). Note: These config is intended to obtain the chart from a different source (e.g. in air-gapped envs), not to use a different version of a helm chart. Using a different helm chart or version to the one used in the GOP version will likely cause errors."
        const val HELM_CONFIG_CHART_DESCRIPTION = "Name of the Helm chart"
        const val HELM_CONFIG_REPO_URL_DESCRIPTION = "Repository url from which the Helm chart should be obtained"
        const val HELM_CONFIG_VERSION_DESCRIPTION = "The version of the Helm chart to be installed"
        const val HELM_CONFIG_IMAGE_DESCRIPTION = "The image of the Helm chart to be installed"
        const val HELM_CONFIG_VALUES_DESCRIPTION = "Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration"

        const val OIDC_DESCPRIPTION = "OIDC Config for this tool. See docs for more infos"
    }
}
