package com.cloudogu.gitops.config

interface ConfigConstants {

    public static final String BINARY_NAME = 'apply-ng'
    public static final String APP_NAME = 'gitops-playground (GOP)'
    public static final String APP_DESCRIPTION = 'CLI-tool to deploy gitops-playground.'
    
    // group registry
    String REGISTRY_DESCRIPTION = 'Config parameters for Registry'
    String REGISTRY_INTERNAL_PORT_DESCRIPTION = 'Port of registry registry. Ignored when a registry*url params are set'
    String REGISTRY_URL_DESCRIPTION = 'The url of your external registry, used for pushing images'
    String REGISTRY_PATH_DESCRIPTION = 'Optional when registry-url is set'
    String REGISTRY_USERNAME_DESCRIPTION = 'Optional when registry-url is set'
    String REGISTRY_PASSWORD_DESCRIPTION = 'Optional when registry-url is set'

    String REGISTRY_PROXY_URL_DESCRIPTION = 'The url of your proxy-registry. Used in pipelines to authorize pull base images. Use in conjunction with petclinic base image. Used in helm charts when create-image-pull-secrets is set. Use in conjunction with helm.*image fields.'
    String REGISTRY_PROXY_USERNAME_DESCRIPTION = 'Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set.'
    String REGISTRY_PROXY_PASSWORD_DESCRIPTION = 'Use with registry-proxy-url, added to Jenkins as credentials and created as pull secrets, when create-image-pull-secrets is set.'
    
    String REGISTRY_USERNAME_RO_DESCRIPTION = 'Optional alternative username for registry-url with read-only permissions that is used when create-image-pull-secrets is set.'
    String REGISTRY_PASSWORD_RO_DESCRIPTION = 'Optional alternative password for registry-url with read-only permissions that is used when create-image-pull-secrets is set.'
    String REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION = 'Create image pull secrets for registry and proxy-registry for all GOP namespaces and helm charts. Uses proxy-username, read-only-username or registry-username (in this order).  Use this if your cluster is not auto-provisioned with credentials for your private registries or if you configure individual helm images to be pulled from the proxy-registry that requires authentication.'

    String FEATURES_DESCRIPTION = 'Config parameters for features or tools'

    String CONTENT_DESCRIPTION = 'Config parameters for content, i.e. end-user or tenant applications as opposed to cluster-resources'

    // Content
    String CONTENT_EXAMPLES_DESCRIPTION = 'Deploy example content: source repos, GitOps repos, Jenkins Job, Argo CD apps/project'
    
    // group jenkins
    String JENKINS_DESCRIPTION = 'Config parameters for Jenkins CI/CD Pipeline Server'
    String JENKINS_URL_DESCRIPTION = 'The url of your external jenkins'
    String JENKINS_USERNAME_DESCRIPTION = 'Mandatory when jenkins-url is set'
    String JENKINS_PASSWORD_DESCRIPTION = 'Mandatory when jenkins-url is set'
    String JENKINS_METRICS_USERNAME_DESCRIPTION = 'Mandatory when jenkins-url is set and monitoring enabled'
    String JENKINS_METRICS_PASSWORD_DESCRIPTION = 'Mandatory when jenkins-url is set and monitoring enabled'
    String MAVEN_CENTRAL_MIRROR_DESCRIPTION = 'URL for maven mirror, used by applications built in Jenkins'
    String JENKINS_ADDITIONAL_ENVS_DESCRIPTION = 'Set additional environments to Jenkins'

    // group scmm
    String SCMM_DESCRIPTION = 'Config parameters for SCMManager (Git repository Server, https://scm-manager.org/)'
    String SCMM_URL_DESCRIPTION = 'The host of your external scm-manager'
    String SCMM_USERNAME_DESCRIPTION = 'Mandatory when scmm-url is set'
    String SCMM_PASSWORD_DESCRIPTION = 'Mandatory when scmm-url is set'
    String GIT_NAME_DESCRIPTION = 'Sets git author and committer name used for initial commits'
    String GIT_EMAIL_DESCRIPTION = 'Sets git author and committer email used for initial commits'
    String SCM_ROOT_PATH_DESCRIPTION = 'Sets the root path for the Git Repositories. In SCM-Manager it is always "repo"'
    String SCM_PROVIDER_DESCRIPTION = 'Sets the scm Provider. Possible Options are "scm-manager" and "gitlab"'

    // group remote
    String REMOTE_DESCRIPTION = 'Expose services as LoadBalancers'
    String INSECURE_DESCRIPTION = 'Sets insecure-mode in cURL which skips cert validation'
    String LOCAL_HELM_CHART_FOLDER_DESCRIPTION = 'A local folder (within the GOP image mostly) where the local mirrors of all helm charts are loaded from when mirror-Repos is active. This is mostly needed for development.'

    // group tool configuration
    String APPLICATION_DESCRIPTION = 'Application configuration parameter for GOP'
    String IMAGES_DESCRIPTION = 'Config params for images that do not belong to specific features'
    String REPOSITORIES_DESCRIPTION = 'Config params for repositories used within GOP'
    String KUBECTL_IMAGE_DESCRIPTION = 'Sets image for kubectl'
    String HELM_IMAGE_DESCRIPTION = 'Sets image for helm'
    String KUBEVAL_IMAGE_DESCRIPTION = 'Sets image for kubeval'
    String HELMKUBEVAL_IMAGE_DESCRIPTION = 'Sets image for helmkubeval'
    String YAMLLINT_IMAGE_DESCRIPTION = 'Sets image for yamllint'
    String MAVEN_IMAGE_DESCRIPTION = 'Sets image for maven'
    String GRAFANA_IMAGE_DESCRIPTION = 'Sets image for grafana'
    String GRAFANA_SIDECAR_IMAGE_DESCRIPTION = 'Sets image for grafana\'s sidecar'
    String PROMETHEUS_IMAGE_DESCRIPTION = 'Sets image for prometheus'
    String PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION = 'Sets image for prometheus-operator'
    String PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION = 'Sets image for prometheus-operator\'s config-reloader'
    String EXTERNAL_SECRETS_IMAGE_DESCRIPTION = 'Sets image for external secrets operator'
    String EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION = 'Sets image for external secrets operator\'s controller'
    String EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION = 'Sets image for external secrets operator\'s webhook'
    String VAULT_IMAGE_DESCRIPTION = 'Sets image for vault'
    String NGINX_IMAGE_DESCRIPTION = 'Sets image for nginx used in various applications'
    String PETCLINIC_IMAGE_DESCRIPTION = 'Sets image for petclinic used in various applications'
    String BASE_URL_DESCRIPTION = 'the external base url (TLD) for all tools, e.g. https://example.com or http://localhost:8080. The individual -url params for argocd, grafana, vault and mailhog take precedence.'
    String URL_SEPARATOR_HYPHEN_DESCRIPTION = 'Use hyphens instead of dots to separate application name from base-url'
    String SKIP_CRDS_DESCRIPTION = 'Skip installation of CRDs. This requires prior installation of CRDs'
    String NAMESPACE_ISOLATION_DESCRIPTION = 'Configure tools to explicitly work with the given namespaces only, and not cluster-wide. This way GOP can be installed without having cluster-admin permissions.'
    String MIRROR_REPOS_DESCRIPTION = 'Changes the sources of deployed tools so they are not pulled from the internet, but are pulled from git and work in air-gapped environments.'
    String SPRING_BOOT_HELM_CHART_DESCRIPTION = 'Repo to pull the generic Spring Boot Helm chart, used in examples and exercises'
    String SPRING_PETCLINIC_DESCRIPTION = 'Repo to pull the Spring Petclinic, used in examples and exercises'
    String GITOPS_BUILD_LIB_DESCRIPTION = 'Repo to pull the gitops-build-lib, used in examples and exercises'
    String CES_BUILD_LIB_DESCRIPTION = 'Repo to pull the ces-build-lib, used in examples and exercises as depedency of the gitops-build-lib'
    String REPO_URL_DESCRIPTION = 'HTTP URL of the repo'
    String REPO_REF_DESCRIPTION = 'Ref of the repo to use, e.g. a tag, commit or branch'
    String NETPOLS_DESCRIPTION = 'Sets Network Policies'
    String OPENSHIFT_DESCRIPTION = 'When set, openshift specific resources and configurations are applied'

    // group metrics
    String MONITORING_DESCRIPTION = 'Config parameters for the Monitoring system (prometheus)'
    String MONITORING_ENABLE_DESCRIPTION = 'Installs the Kube-Prometheus-Stack. This includes Prometheus, the Prometheus operator, Grafana and some extra resources'
    String GRAFANA_URL_DESCRIPTION = 'Sets url for grafana'
    String GRAFANA_EMAIL_FROM_DESCRIPTION = 'Notifications, define grafana alerts sender email address'
    String GRAFANA_EMAIL_TO_DESCRIPTION = 'Notifications, define grafana alerts recipient email address'

    // group vault / secrets
    String SECRETS_DESCRIPTION = 'Config parameters for the secrets management'
    String ESO_DESCRIPTION = 'Config parameters for the external secrets operator'
    String VAULT_DESCRIPTION = 'Config parameters for the secrets-vault'
    String VAULT_ENABLE_DESCRIPTION = "Installs Hashicorp vault and the external secrets operator. Possible values: dev, prod."
    String VAULT_URL_DESCRIPTION = 'Sets url for vault ui'

    String MAIL_DESCRIPTION = 'Config parameters for mail servers'
    String MAILHOG_URL_DESCRIPTION = 'Sets url for MailHog'
    String MAILHOG_ENABLE_DESCRIPTION = 'Installs MailHog as Mail server.'

    // group external Mailserver
    String SMTP_ADDRESS_DESCRIPTION = 'Sets smtp port of external Mailserver'
    String SMTP_PORT_DESCRIPTION = 'Sets smtp port of external Mailserver'
    String SMTP_USER_DESCRIPTION = 'Sets smtp username for external Mailserver'
    String SMTP_PASSWORD_DESCRIPTION = 'Sets smtp password of external Mailserver'

    // group debug
    String DEBUG_DESCRIPTION = 'Debug output'
    String TRACE_DESCRIPTION = 'Debug + Show each command executed (set -x)'

    // group configuration
    String USERNAME_DESCRIPTION = 'Set initial admin username'
    String PASSWORD_DESCRIPTION = 'Set initial admin passwords'
    String PIPE_YES_DESCRIPTION = 'Skip confirmation'
    String NAME_PREFIX_DESCRIPTION = 'Set name-prefix for repos, jobs, namespaces'
    String DESTROY_DESCRIPTION = 'Unroll playground'
    String CONFIG_FILE_DESCRIPTION = 'Config file for the application'
    String CONFIG_MAP_DESCRIPTION = 'Kubernetes configuration map. Should contain a key `config.yaml`.'
    String OUTPUT_CONFIG_FILE_DESCRIPTION = 'Output current config as config file as much as possible'
    String POD_RESOURCES_DESCRIPTION = 'Write kubernetes resource requests and limits on each pod'

    // group ArgoCD Operator
    String ARGOCD_DESCRIPTION = 'Config Parameter for the ArgoCD Operator'
    String ARGOCD_ENABLE_DESCRIPTION = 'Install ArgoCD'
    String ARGOCD_URL_DESCRIPTION = 'The URL where argocd is accessible. It has to be the full URL with http:// or https://'
    String ARGOCD_EMAIL_FROM_DESCRIPTION = 'Notifications, define Argo CD sender email address'
    String ARGOCD_EMAIL_TO_USER_DESCRIPTION = 'Notifications, define Argo CD user / app-team recipient email address'
    String ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION = 'Notifications, define Argo CD admin recipient email address'
    String ARGOCD_OPERATOR_DESCRIPTION = 'Install ArgoCD via an already running ArgoCD Operator'
    String ARGOCD_ENV_DESCRIPTION = 'Pass a list of env vars to Argo CD components. Currently only works with operator'
    String ARGOCD_RESOURCE_INCLUSIONS_CLUSTER = 'Internal Kubernetes API Server URL https://IP:PORT (kubernetes.default.svc). Needed in argocd-operator resourceInclusions. Use this parameter if argocd.operator=true and NOT running inside a Pod (remote mode). Full URL needed, for example: https://100.125.0.1:443'

    // group example apps
    String EXAMPLE_APPS_DESCRIPTION = 'Config parameters for the example apps and exercises'
    String BASE_DOMAIN_DESCRIPTION = 'The domain under which a subdomain these applications will be made available'
    String PETCLINIC_DESCRIPTION = 'Config parameters for the petclinic example apps apps and exercises'
    String NGINX_DESCRIPTION = 'Config parameters for the NGINX example apps apps and exercises'

    // group ingress-class
    String INGRESS_NGINX_DESCRIPTION = 'Config parameters for the NGINX Ingress Controller'
    String INGRESS_NGINX_ENABLE_DESCRIPTION = 'Sets and enables Nginx Ingress Controller'

    // group CERTMANAGER
    String CERTMANAGER_DESCRIPTION = 'Config parameters for the Cert Manager'
    String CERTMANAGER_ENABLE_DESCRIPTION = 'Sets and enables Cert Manager'
    String CERTMANAGER_IMAGE_DESCRIPTION = 'Sets image for Cert Manager'
    String CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION = 'Sets webhook Image for Cert Manager'
    String CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION = 'Sets cainjector Image for Cert Manager'
    String CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION = 'Sets acmeSolver Image for Cert Manager'
    String CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION = 'Sets startupAPICheck Image for Cert Manager'

    // group helm
    String HELM_CONFIG_DESCRIPTION = 'Common Config parameters for the Helm package manager: Name of Chart (chart), URl of Helm-Repository (repoURL) and Chart Version (version). Note: These config is intended to obtain the chart from a different source (e.g. in air-gapped envs), not to use a different version of a helm chart. Using a different helm chart or version to the one used in the GOP version will likely cause errors.'
    String HELM_CONFIG_CHART_DESCRIPTION = 'Name of the Helm chart'
    String HELM_CONFIG_REPO_URL_DESCRIPTION = 'Repository url from which the Helm chart should be obtained'
    String HELM_CONFIG_VERSION_DESCRIPTION = 'The version of the Helm chart to be installed'
    String HELM_CONFIG_IMAGE_DESCRIPTION = 'The image of the Helm chart to be installed'
    String HELM_CONFIG_VALUES_DESCRIPTION = 'Helm values of the chart, allows overriding defaults and setting values that are not exposed as explicit configuration'
}