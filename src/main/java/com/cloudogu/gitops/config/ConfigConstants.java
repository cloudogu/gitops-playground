package com.cloudogu.gitops.config;

public final class ConfigConstants {

	public static final String BINARY_NAME = "apply-ng";
	public static final String APP_NAME = "gitops-playground (GOP)";
	public static final String APP_DESCRIPTION = "CLI-tool to deploy gitops-playground.";

	// group registry
	public static final String REGISTRY_ENABLE_DESCRIPTION = "Installs a simple cluster-local registry for demonstration purposes. Warning: Registry does not provide authentication!";
	public static final String REGISTRY_DESCRIPTION = "Config parameters for Registry";
	public static final String REGISTRY_INTERNAL_PORT_DESCRIPTION = "Port of registry registry. Ignored when a registry*url params are set";
	public static final String REGISTRY_URL_DESCRIPTION = "The url of your external registry, used for pushing images";
	public static final String REGISTRY_OPTIONAL_WHEN_URL_SET_DESCRIPTION = "Optional when registry-url is set";
	public static final String REGISTRY_PATH_DESCRIPTION = REGISTRY_OPTIONAL_WHEN_URL_SET_DESCRIPTION;
	public static final String REGISTRY_USERNAME_DESCRIPTION = REGISTRY_OPTIONAL_WHEN_URL_SET_DESCRIPTION;
	public static final String REGISTRY_PASSWORD_DESCRIPTION = REGISTRY_OPTIONAL_WHEN_URL_SET_DESCRIPTION;

	public static final String REGISTRY_PROXY_URL_DESCRIPTION = "The url of your proxy-registry. Used in pipelines to authorize pull base images. Use in conjunction with petclinic base image. Used in helm charts when create-image-pull-secrets is set. Use in conjunction with helm.*image fields.";
	public static final String REGISTRY_PROXY_PATH_DESCRIPTION = "Optional when registry-proxy-url is set and the registry is running on a non root web path.";
	public static final String REGISTRY_PROXY_USERNAME_DESCRIPTION = "Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set.";
	public static final String REGISTRY_PROXY_PASSWORD_DESCRIPTION = "Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set.";

	public static final String REGISTRY_USERNAME_RO_DESCRIPTION = "Optional alternative username for registry-url with read-only permissions that is used when create-image-pull-secrets is set.";
	public static final String REGISTRY_PASSWORD_RO_DESCRIPTION = "Optional alternative password for registry-url with read-only permissions that is used when create-image-pull-secrets is set.";
	public static final String REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION = "Create image pull secrets for registry and proxy-registry for all GOP namespaces and helm charts. Uses proxy-username, read-only-username or registry-username (in this order).  Use this if your cluster is not auto-provisioned with credentials for your private registries or if you configure individual helm images to be pulled from the proxy-registry that requires authentication.";
	public static final String REGISTRY_NAMESPACE = "Optional defines the kubernetes namespace for registry.";

	public static final String FEATURES_DESCRIPTION = "Config parameters for features or tools";

	public static final String CONTENT_DESCRIPTION = "Config parameters for content, i.e. end-user or tenant applications as opposed to cluster-resources";

	// ContentLoader
	public static final String CONTENT_NAMESPACES_DESCRIPTION = "Additional kubernetes namespaces. These are authorized to Argo CD, supplied with image pull secrets, monitored by prometheus, etc. Namespaces can be templates, e.g. ${config.application.namePrefix}staging";
	public static final String CONTENT_REPO_DESCRIPTION = "ContentLoader repos to push into target environment";
	public static final String CONTENT_REPO_URL_DESCRIPTION = "URL of the content repo. Mandatory for each type.";
	public static final String CONTENT_REPO_PATH_DESCRIPTION = "Path within the content repo to process";
	public static final String CONTENT_REPO_REF_DESCRIPTION = "Reference for a specific branch, tag, or commit. Emtpy defaults to default branch of the repo. With type MIRROR: ref must not be a commit hash; Choosing a ref only mirrors the ref but does not delete other branches/tags!";
	public static final String CONTENT_REPO_TARGET_REF_DESCRIPTION = "Reference for a specific branch or tag in the target repo of a MIRROR or COPY repo. If ref is a tag, targetRef is treated as tag as well. Except: targetRef is full ref like refs/heads/my-branch or refs/tags/my-tag. Empty defaults to the source ref.";
	public static final String CONTENT_REPO_CREDENTIALS_DESCRIPTION = "Credentials Object to authenticate against content repo. Allows using a K8s Secret";
	public static final String CONTENT_REPO_TEMPLATING_DESCRIPTION = "When true, template all files ending in .ftl within the repo";
	public static final String CONTENT_REPO_TYPE_DESCRIPTION = "ContentLoader Repos can either be:\ncopied (only the files, starting on ref, starting at path within the repo. Requires target)\n, mirrored (FORCE pushes ref or the whole git repo if no ref set). Requires target, does not allow path and template.)\nfolderBased (folder structure is interpreted as repos. That is, root folder becomes namespace in SCM, sub folders become repository names in SCM, files are copied. Requires target.)";
	public static final String CONTENT_REPO_TARGET_DESCRIPTION = "Target repo for the repository in the for of namespace/name. Must contain one slash to separate namespace from name.";
	public static final String CONTENT_REPO_TARGET_OVERWRITE_MODE_DESCRIPTION = "This defines, how customer repos will be updated.\nINIT - push only if repo does not exist.\nRESET - delete all files after cloning source - files not in content are deleted\nUPGRADE - clone and copy - existing files will be overwritten, files not in content are kept. For type: MIRROR reset and upgrade have same result: in both cases source repo will be force pushed to target repo.";
	public static final String CONTENT_REPO_CREATE_JENKINS_JOB_DESCRIPTION = "If true, creates a Jenkins job, if jenkinsfile exists in one of the content repo's branches.";
	public static final String CONTENT_VARIABLES_DESCRIPTION = "Additional variables to use in custom templates.";
	public static final String CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION = "Enables the whitelist for statics in content templating";
	public static final String CONTENT_STATICSWHITELIST_DESCRIPTION = "Whitelist for Statics freemarker is allowing in user templates";
	public static final String CONTENT_HELM_RELEASES_DESCRIPTION = "Additional Helm releases to deploy through Argo CD without requiring a content Git repository.";
	public static final String CONTENT_HELM_RELEASE_NAME_DESCRIPTION = "Logical name of the Helm release. Used as the feature folder name under 'apps/<name>' and as default for 'releaseName' if not set.";

	public static final String CONTENT_HELM_RELEASE_REPO_URL_DESCRIPTION = "Helm repository URL to fetch the chart from. Use an HTTP(S) Helm repo (must provide an index.yaml) or an OCI registry URL (oci://...).";
	public static final String CONTENT_HELM_RELEASE_CHART_DESCRIPTION = "Helm chart name to install. For HTTP(S) repos this is the chart name from the repo index; for OCI this is the chart artifact name.";
	public static final String CONTENT_HELM_RELEASE_VERSION_DESCRIPTION = "Chart version to deploy. Required for Helm charts in Argo CD. For HTTP(S) Helm repos you may use a SemVer range like '*' to always pick the newest version. For OCI registries, specify an explicit version/tag.";
	public static final String CONTENT_HELM_RELEASE_NAMESPACE_DESCRIPTION = "Kubernetes namespace to deploy the release into.";
	public static final String CONTENT_HELM_RELEASE_RELEASE_NAME_DESCRIPTION = "Helm release name. If empty, the value of 'name' is used.";
	public static final String CONTENT_HELM_RELEASE_VALUES_FILE_DESCRIPTION = "Optional path to a YAML values file to load Helm values from.The file must be accessible locally on the machine running GOP. Inline 'values' will be merged on top (inline overrides file).";
	public static final String CONTENT_HELM_RELEASE_VALUES_DESCRIPTION = "Optional inline Helm values. These values are merged on top of 'valuesFile' (if set) and override keys from the file. Use this for small overrides without maintaining a separate file.";

	// group jenkins
	public static final String JENKINS_ENABLE_DESCRIPTION = "Installs Jenkins as CI server";
	public static final String JENKINS_SKIP_RESTART_DESCRIPTION = "Skips restarting Jenkins after plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.";
	public static final String JENKINS_SKIP_PLUGINS_DESCRIPTION = "Skips plugin installation. Use with caution! If the plugins are not installed up front, the installation will likely fail. The intended use case for this is after the first installation, for config changes only. Do not use on first installation or upgrades.";
	public static final String JENKINS_DESCRIPTION = "Config parameters for Jenkins CI/CD Pipeline Server";
	public static final String JENKINS_URL_DESCRIPTION = "The url of your external jenkins";
	public static final String JENKINS_USERNAME_DESCRIPTION = "Mandatory when jenkins-url is set";
	public static final String JENKINS_PASSWORD_DESCRIPTION = "Mandatory when jenkins-url is set";
	public static final String JENKINS_METRICS_USERNAME_DESCRIPTION = "Mandatory when jenkins-url is set and monitoring enabled";
	public static final String JENKINS_METRICS_PASSWORD_DESCRIPTION = "Mandatory when jenkins-url is set and monitoring enabled";
	public static final String JENKINS_IMAGE_DESCRIPTION = "Sets image for Jenkins";
	public static final String MAVEN_CENTRAL_MIRROR_DESCRIPTION = "URL for maven mirror, used by applications built in Jenkins";
	public static final String JENKINS_ADDITIONAL_ENVS_DESCRIPTION = "Set additional environments to Jenkins";
	public static final String JENKINS_NAMESPACE = "Optional defines the kubernetes namespace for Jenkins.";

	// group scmm
	public static final String SCM_DESCRIPTION = "Config parameters for Scm";
	public static final String GIT_NAME_DESCRIPTION = "Sets git author and committer name used for initial commits";
	public static final String GIT_EMAIL_DESCRIPTION = "Sets git author and committer email used for initial commits";

	// MutliTentant
	public static final String MULTITENANT_DESCRIPTION = "Multi Tenant Configs";

	// group remote
	public static final String INSECURE_DESCRIPTION = "Sets insecure-mode in cURL which skips cert validation";

	// group tool configuration
	public static final String APPLICATION_DESCRIPTION = "Application configuration parameter for GOP";
	public static final String GRAFANA_IMAGE_DESCRIPTION = "Sets image for grafana";
	public static final String GRAFANA_SIDECAR_IMAGE_DESCRIPTION = "Sets image for grafana's sidecar";
	public static final String PROMETHEUS_IMAGE_DESCRIPTION = "Sets image for prometheus";
	public static final String PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION = "Sets image for prometheus-operator";
	public static final String PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION = "Sets image for prometheus-operator's config-reloader";
	public static final String EXTERNAL_SECRETS_IMAGE_DESCRIPTION = "Sets image for external secrets operator";
	public static final String EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION = "Sets image for external secrets operator's controller";
	public static final String EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION = "Sets image for external secrets operator's webhook";
	public static final String VAULT_IMAGE_DESCRIPTION = "Sets image for vault";
	public static final String BASE_URL_DESCRIPTION = "the external base url (TLD) for all tools, e.g. https://example.com or http://localhost:8080. The individual -url params for argocd, grafana and vault take precedence.";
	public static final String URL_SEPARATOR_HYPHEN_DESCRIPTION = "Use hyphens instead of dots to separate application name from base-url";
	public static final String SKIP_CRDS_DESCRIPTION = "Skip installation of CRDs. This requires prior installation of CRDs";
	public static final String NAMESPACE_ISOLATION_DESCRIPTION = "Configure tools to explicitly work with the given namespaces only, and not cluster-wide. This way GOP can be installed without having cluster-admin permissions.";
	public static final String MIRROR_REPOS_DESCRIPTION = "Changes the sources of deployed tools so they are not pulled from the internet, but are pulled from git and work in air-gapped environments.";
	public static final String NETPOLS_DESCRIPTION = "Sets Network Policies";
	public static final String CLUSTER_ADMIN_DESCRIPTION = "Binds ArgoCD controllers to cluster-admin ClusterRole";
	public static final String OPENSHIFT_DESCRIPTION = "When set, openshift specific resources and configurations are applied";
	public static final String APPLICATION_PROFIL = "Use predefined profile (full, only-argocd, operator-mandants aso.)";
	public static final String APPLICATION_GOP_NAMESPACE = "If set, GOP stores specific information in this namespace.";
	public static final String APPLICATION_NAMESPACE = "If set, GOP uses the same Kubernetes namespace for all tools and examples. Attention! Only use for test purposes.";
	// group metrics
	public static final String MONITORING_DESCRIPTION = "Config parameters for the Monitoring system (prometheus)";
	public static final String MONITORING_ENABLE_DESCRIPTION = "Installs the Kube-Prometheus-Stack. This includes Prometheus, the Prometheus operator, Grafana and some extra resources";
	public static final String MONITORING_NAMESPACE = "Optional defines the kubernetes namespace for monitoring.";
	public static final String GRAFANA_URL_DESCRIPTION = "Sets url for grafana";
	public static final String GRAFANA_EMAIL_FROM_DESCRIPTION = "Notifications, define grafana alerts sender email address";
	public static final String GRAFANA_EMAIL_TO_DESCRIPTION = "Notifications, define grafana alerts recipient email address";

	// group vault / secrets
	public static final String SECRETS_DESCRIPTION = "Config parameters for the secrets management";
	public static final String ESO_DESCRIPTION = "Config parameters for the external secrets operator";
	public static final String VAULT_DESCRIPTION = "Config parameters for the secrets-vault";
	public static final String VAULT_ENABLE_DESCRIPTION = "Installs Hashicorp vault and the external secrets operator. Possible values: dev, prod.";
	public static final String VAULT_URL_DESCRIPTION = "Sets url for vault ui";
	public static final String SECRETS_NAMESPACE = "Optional defines the kubernetes namespace for secrets.";

	// group external Mailserver
	public static final String MAIL_DESCRIPTION = "Config parameters for mail servers";
	public static final String SMTP_ADDRESS_DESCRIPTION = "Sets smtp port of external Mailserver";
	public static final String SMTP_PORT_DESCRIPTION = "Sets smtp port of external Mailserver";
	public static final String SMTP_USER_DESCRIPTION = "Sets smtp username for external Mailserver";
	public static final String SMTP_PASSWORD_DESCRIPTION = "Sets smtp password of external Mailserver";

	// group debug
	public static final String DEBUG_DESCRIPTION = "Debug output";
	public static final String TRACE_DESCRIPTION = "Debug + Show each command executed (set -x)";

	// group configuration
	public static final String USERNAME_DESCRIPTION = "Set initial admin username";
	public static final String PASSWORD_DESCRIPTION = "Set initial admin passwords";
	public static final String PIPE_YES_DESCRIPTION = "Skip confirmation";
	public static final String NAME_PREFIX_DESCRIPTION = "Set name-prefix for repos, jobs, namespaces";
	public static final String DESTROY_DESCRIPTION = "Unroll playground";
	public static final String CONFIG_FILE_DESCRIPTION = "Config file for the application";
	public static final String CONFIG_MAP_DESCRIPTION = "Kubernetes configuration map. Should contain a key `config.yaml`.";
	public static final String OUTPUT_CONFIG_FILE_DESCRIPTION = "Output current config as config file as much as possible";
	public static final String POD_RESOURCES_DESCRIPTION = "Write kubernetes resource requests and limits on each pod";

	// group ArgoCD Operator
	public static final String ARGOCD_DESCRIPTION = "Config Parameter for the ArgoCD Operator";
	public static final String ARGOCD_ENABLE_DESCRIPTION = "Install ArgoCD";
	public static final String ARGOCD_URL_DESCRIPTION = "The URL where argocd is accessible. It has to be the full URL with http:// or https://";
	public static final String ARGOCD_EMAIL_FROM_DESCRIPTION = "Notifications, define Argo CD sender email address";
	public static final String ARGOCD_EMAIL_TO_USER_DESCRIPTION = "Notifications, define Argo CD user / app-team recipient email address";
	public static final String ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION = "Notifications, define Argo CD admin recipient email address";
	public static final String ARGOCD_OPERATOR_DESCRIPTION = "Install ArgoCD via an already running ArgoCD Operator";
	public static final String ARGOCD_ENV_DESCRIPTION = "Pass a list of env vars to Argo CD components. Currently only works with operator";
	public static final String ARGOCD_RESOURCE_INCLUSIONS_CLUSTER = "Internal Kubernetes API Server URL https://IP:PORT (kubernetes.default.svc). Needed in argocd-operator resourceInclusions. Use this parameter if argocd.operator=true and NOT running inside a Pod (remote mode). Full URL needed, for example: https://100.125.0.1:443";
	public static final String ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION = "Defines the kubernetes namespace for ArgoCD";

	// group ingress-class
	public static final String INGRESS_DESCRIPTION = "Config parameters for the Ingress Controller";
	public static final String INGRESS_ENABLE_DESCRIPTION = "Sets and enables Ingress Controller";
	public static final String INGRESS_NAMESPACE = "Optional defines the kubernetes namespace for Ingress Controller";

	// group CERTMANAGER
	public static final String CERTMANAGER_DESCRIPTION = "Config parameters for the Cert Manager";
	public static final String CERTMANAGER_ENABLE_DESCRIPTION = "Sets and enables Cert Manager";
	public static final String CERTMANAGER_IMAGE_DESCRIPTION = "Sets image for Cert Manager";
	public static final String CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION = "Sets webhook Image for Cert Manager";
	public static final String CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION = "Sets cainjector Image for Cert Manager";
	public static final String CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION = "Sets acmeSolver Image for Cert Manager";
	public static final String CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION = "Sets startupAPICheck Image for Cert Manager";
	public static final String CERTMANAGER_NAMESPACE = "Optional defines the kubernetes namespace for Cert Manager";

	// group helm
	public static final String HELM_CONFIG_DESCRIPTION = "Common Config parameters for the Helm package manager: Name of Chart (chart), URl of Helm-Repository (repoURL) and Chart Version (version). Note: These config is intended to obtain the chart from a different source (e.g. in air-gapped envs), not to use a different version of a helm chart. Using a different helm chart or version to the one used in the GOP version will likely cause errors.";
	public static final String HELM_CONFIG_CHART_DESCRIPTION = "Name of the Helm chart";
	public static final String HELM_CONFIG_REPO_URL_DESCRIPTION = "Repository url from which the Helm chart should be obtained";
	public static final String HELM_CONFIG_VERSION_DESCRIPTION = "The version of the Helm chart to be installed";
	public static final String HELM_CONFIG_IMAGE_DESCRIPTION = "The image of the Helm chart to be installed";
	public static final String HELM_CONFIG_VALUES_DESCRIPTION = "Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration";

	public static final String OIDC_DESCPRIPTION = "OIDC Config for this tool. See docs for more infos";

	private ConfigConstants() {
	}
}
