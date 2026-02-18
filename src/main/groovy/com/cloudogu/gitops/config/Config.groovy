package com.cloudogu.gitops.config

import static com.cloudogu.gitops.config.ConfigConstants.*
import static picocli.CommandLine.ScopeType

import jakarta.inject.Singleton

import groovy.transform.MapConstructor

import org.apache.http.client.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

import com.cloudogu.gitops.features.git.config.ScmTenantSchema

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

/**
 * The global configuration object.
 *
 * Also used to create the schema for the configuration file or map, which is used to validate the passed YAML file.
 *
 * Note that all properties marked with
 * * {@link JsonPropertyDescription} (written into the Config for config file and config map)
 * * {@link Option} (CLI Options)
 *
 * are external properties that can be changed by the user.
 * All other properties are internal.
 *
 * When changing values make sure to recreate file configuration.schema.json using JsonSchemaGenerator
 * (copy output into file an format using IDE).
 *
 * Make sure not to forget {@link Mixin} at sub types that contain CLI {@link Option}s. Otherwise they are ignored by
 * picocli.
 *
 * Default values
 * - Boolean is set to false
 * - String uses empty string, because of too many null checks in freemarker and usages.
 *
 * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli - initializes from file, and CLI
 */
@Singleton
@MapConstructor(noArg = true, includeSuperProperties = true, includeFields = true)
@Command(name = BINARY_NAME, description = APP_DESCRIPTION)
class Config {

    // When updating please also update in Dockerfile
    public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:3.16.4-1"
    // When updating please also adapt in Dockerfile, vars.tf and init-cluster.sh
    public static final String K8S_VERSION = "1.29"
    public static final String DEFAULT_ADMIN_USER = 'admin'
    public static final String DEFAULT_ADMIN_PW = 'admin'
    public static final int DEFAULT_REGISTRY_PORT = 30000

    @JsonPropertyDescription(REGISTRY_DESCRIPTION)
    @Mixin
    RegistrySchema registry = new RegistrySchema()

    @JsonPropertyDescription(JENKINS_DESCRIPTION)
    @Mixin
    JenkinsSchema jenkins = new JenkinsSchema()

    @JsonPropertyDescription(MULTITENANT_DESCRIPTION)
    @Mixin
    MultiTenantSchema multiTenant = new MultiTenantSchema()

    @JsonPropertyDescription(SCM_DESCRIPTION)
    @Mixin
    ScmTenantSchema scm = new ScmTenantSchema()

    @JsonPropertyDescription(APPLICATION_DESCRIPTION)
    @Mixin
    ApplicationSchema application = new ApplicationSchema()

    @JsonPropertyDescription(FEATURES_DESCRIPTION)
    @Mixin
    FeaturesSchema features = new FeaturesSchema()

    @JsonPropertyDescription(CONTENT_DESCRIPTION)
    @Mixin
    ContentSchema content = new ContentSchema()

    static class ContentSchema {
        @Option(names = ['--content-examples'], description = CONTENT_EXAMPLES_DESCRIPTION)
        @JsonPropertyDescription(CONTENT_EXAMPLES_DESCRIPTION)
        Boolean examples = false

        @Option(names = ['--multi-tenancy-examples'], description = CONTENT_MULTI_TENANCY_EXAMPLES_DESCRIPTION)
        @JsonPropertyDescription(CONTENT_MULTI_TENANCY_EXAMPLES_DESCRIPTION)
        Boolean multitenancyExamples = false

        @JsonPropertyDescription(CONTENT_NAMESPACES_DESCRIPTION)
        List<String> namespaces = []

        @JsonPropertyDescription(CONTENT_REPO_DESCRIPTION)
        List<ContentRepositorySchema> repos = []

        @JsonPropertyDescription(CONTENT_VARIABLES_DESCRIPTION)
        Map<String, Object> variables = [:]

        @Option(names = ['--content-whitelist'], description = CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION)
        @JsonPropertyDescription(CONTENT_STATICSWHITELIST_ENABLED_DESCRIPTION)
        Boolean useWhitelist = false

        @JsonPropertyDescription(CONTENT_STATICSWHITELIST_DESCRIPTION)
        Set<String> allowedStaticsWhitelist = [
                'java.lang.String',
                'java.lang.Integer',
                'java.lang.Long',
                'java.lang.Double',
                'java.lang.Float',
                'java.lang.Boolean',
                'java.lang.Math',
                'com.cloudogu.gitops.utils.DockerImageParser'
        ] as Set<String>

        static class ContentRepositorySchema {
            static final String DEFAULT_PATH = '.'
            // This is controversial. Forcing users to explicitly choose a type requires them to understand the concept
            // of types. What would be a good default? The simplest use case ist MIRROR from url to target.
            // COPY and FOLDER_BASED are more advanced use cases. So we choose MIRROR as the default.
            static final ContentRepoType DEFAULT_TYPE = ContentRepoType.MIRROR

            @JsonPropertyDescription(CONTENT_REPO_URL_DESCRIPTION)
            String url = ''

            @JsonPropertyDescription(CONTENT_REPO_PATH_DESCRIPTION)
            String path = DEFAULT_PATH

            @JsonPropertyDescription(CONTENT_REPO_REF_DESCRIPTION)
            String ref = ''

            @JsonPropertyDescription(CONTENT_REPO_TARGET_REF_DESCRIPTION)
            String targetRef = ''

            @JsonPropertyDescription(CONTENT_REPO_CREDENTIALS_DESCRIPTION)
            Credentials credentials

            @JsonPropertyDescription(CONTENT_REPO_TEMPLATING_DESCRIPTION)
            Boolean templating = false

            @JsonPropertyDescription(CONTENT_REPO_TYPE_DESCRIPTION)
            ContentRepoType type = DEFAULT_TYPE

            @JsonPropertyDescription(CONTENT_REPO_TARGET_DESCRIPTION)
            String target = ''

            @JsonPropertyDescription(CONTENT_REPO_TARGET_OVERWRITE_MODE_DESCRIPTION)
            OverwriteMode overwriteMode = OverwriteMode.INIT
            // Defensively use init to not override existing files by default

            @JsonPropertyDescription(CONTENT_REPO_CREATE_JENKINS_JOB_DESCRIPTION)
            Boolean createJenkinsJob = false

        }
    }

    static class HelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_CHART_DESCRIPTION)
        String chart = ''
        @JsonPropertyDescription(HELM_CONFIG_REPO_URL_DESCRIPTION)
        String repoURL = ''
        @JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
        String version = ''
    }

    static class HelmConfigWithValues extends HelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
        Map<String, Object> values = [:]
    }

    static class RegistrySchema {
        Boolean internal = true
        Boolean twoRegistries = false

        @Option(names = ['--registry'], description = REGISTRY_ENABLE_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_ENABLE_DESCRIPTION)
        Boolean active = false

        @Option(names = ['--internal-registry-port'], description = REGISTRY_INTERNAL_PORT_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_INTERNAL_PORT_DESCRIPTION)
        Integer internalPort = DEFAULT_REGISTRY_PORT

        @Option(names = ['--registry-url'], description = REGISTRY_URL_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--registry-path'], description = REGISTRY_PATH_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_PATH_DESCRIPTION)
        String path = ''

        @Option(names = ['--registry-username'], description = REGISTRY_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_USERNAME_DESCRIPTION)
        String username = ''

        @Option(names = ['--registry-password'], description = REGISTRY_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_PASSWORD_DESCRIPTION)
        String password = ''

        // Alternative: Use different registries, e.g. in air-gapped envs
        // "Proxy" registry for 3rd party images, e.g. base images
        @Option(names = ['--registry-proxy-url'], description = REGISTRY_PROXY_URL_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_PROXY_URL_DESCRIPTION)
        String proxyUrl = ''

        @Option(names = ['--registry-proxy-username'], description = REGISTRY_PROXY_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_PROXY_USERNAME_DESCRIPTION)
        String proxyUsername = ''

        @Option(names = ['--registry-proxy-password'], description = 'Optional when --registry-proxy-url is set')
        @JsonPropertyDescription(REGISTRY_PROXY_PASSWORD_DESCRIPTION)
        String proxyPassword = ''

        // Alternative set of credentials for url, used only for image pull secrets
        @Option(names = ['--registry-username-read-only'], description = REGISTRY_USERNAME_RO_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_USERNAME_RO_DESCRIPTION)
        String readOnlyUsername = ''

        @Option(names = ['--registry-password-read-only'], description = REGISTRY_PASSWORD_RO_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_PASSWORD_RO_DESCRIPTION)
        String readOnlyPassword = ''

        @Option(names = ['--create-image-pull-secrets'], description = REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION)
        @JsonPropertyDescription(REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION)
        Boolean createImagePullSecrets = false

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        HelmConfigWithValues helm = new HelmConfigWithValues(
                chart: 'docker-registry',
                repoURL: 'https://twuni.github.io/docker-registry.helm',
                version: '3.0.0')

    }

    static class JenkinsSchema {
        Boolean internal = true
        /* When installing via Docker we have to distinguish jenkins.url (which is a local IP address) from
           the Jenkins URL used by SCMM.

           This is the URL configured in SCMM inside the Jenkins Plugin, e.g. at http://scmm.localhost/scm/admin/settings/jenkins
           See addJenkinsConfig() and the comment at scmm.urlForJenkins */
        String urlForScm = ''
        String ingress = ''
        // Bash image used with internal Jenkins only
        String internalBashImage = 'bash:5'
        /* Docker client image, downloaded on internal Jenkins only
          For updating, delete pvc jenkins-docker-client
          When updating, we should not use too recent version, to not break support for LTS distros like debian
          https://docs.docker.com/engine/install/debian/#os-requirements -> oldstable
          For example:
          $ curl -s https://download.docker.com/linux/debian/dists/bullseye/stable/binary-amd64/Packages  | grep -EA5 'Package\: docker-ce$' | grep Version | sort | uniq | tail -n1
          Version: 5:27.1.1-1~debian.11~bullseye */
        String internalDockerClientVersion = '27.1.2'

        @Option(names = ['--jenkins'], description = JENKINS_ENABLE_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_ENABLE_DESCRIPTION)
        Boolean active = false

        @Option(names = ['--jenkins-skip-restart'], description = JENKINS_SKIP_RESTART_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_SKIP_RESTART_DESCRIPTION)
        Boolean skipRestart = false

        @Option(names = ['--jenkins-skip-plugins'], description = JENKINS_SKIP_PLUGINS_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_SKIP_PLUGINS_DESCRIPTION)
        Boolean skipPlugins = false

        @Option(names = ['--jenkins-url'], description = JENKINS_URL_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_URL_DESCRIPTION)
        String url = ''

        @Option(names = ['--jenkins-username'], description = JENKINS_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_USERNAME_DESCRIPTION)
        String username = DEFAULT_ADMIN_USER

        @Option(names = ['--jenkins-password'], description = JENKINS_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_PASSWORD_DESCRIPTION)
        String password = DEFAULT_ADMIN_PW

        @Option(names = ['--jenkins-metrics-username'], description = JENKINS_METRICS_USERNAME_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_METRICS_USERNAME_DESCRIPTION)
        String metricsUsername = "metrics"

        @Option(names = ['--jenkins-metrics-password'], description = JENKINS_METRICS_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(JENKINS_METRICS_PASSWORD_DESCRIPTION)
        String metricsPassword = "metrics"

        @Option(names = ['--maven-central-mirror'], description = MAVEN_CENTRAL_MIRROR_DESCRIPTION)
        @JsonPropertyDescription(MAVEN_CENTRAL_MIRROR_DESCRIPTION)
        String mavenCentralMirror = ''

        @Option(names = ["--jenkins-additional-envs"], description = JENKINS_ADDITIONAL_ENVS_DESCRIPTION, split = ",", required = false)
        @JsonPropertyDescription(JENKINS_ADDITIONAL_ENVS_DESCRIPTION)
        Map<String, String> additionalEnvs = [:]

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        HelmConfigWithValues helm = new HelmConfigWithValues(
                chart: 'jenkins',
                repoURL: 'https://charts.jenkins.io',
                version: '5.8.43')
    }

    static class ApplicationSchema {
        Boolean runningInsideK8s = false
        String namePrefixForEnvVars = ''
        String internalKubernetesApiUrl = ''
        String localHelmChartFolder = System.getenv('LOCAL_HELM_CHART_FOLDER')

        NamespaceSchema namespaces = new NamespaceSchema()

        @Option(names = ['--config-file'], description = CONFIG_FILE_DESCRIPTION, split = ',')
        List<String> configFiles = []

        @Option(names = ['--config-map'], description = CONFIG_MAP_DESCRIPTION, split = ',')
        List<String> configMaps = []

        @Option(names = ['-d', '--debug'], description = DEBUG_DESCRIPTION, scope = ScopeType.INHERIT)
        Boolean debug

        @Option(names = ['-x', '--trace'], description = TRACE_DESCRIPTION, scope = ScopeType.INHERIT)
        Boolean trace

        @Option(names = ['--output-config-file'], description = OUTPUT_CONFIG_FILE_DESCRIPTION, help = true)
        Boolean outputConfigFile = false

        @Option(names = ["-v", "--version"], help = true, description = "Display version and license info")
        Boolean versionInfoRequested = false

        // We define or own --version, so we need to define our own help param.
        // The param itself is not used, "usageHelp = true" leads to hel being printed
        @Option(names = ["-h", "--help"], usageHelp = true, description = "Display this help message")
        Boolean usageHelpRequested = false

        @Option(names = ['--remote'], description = REMOTE_DESCRIPTION)
        @JsonPropertyDescription(REMOTE_DESCRIPTION)
        Boolean remote = false

        @Option(names = ['--insecure'], description = INSECURE_DESCRIPTION)
        @JsonPropertyDescription(INSECURE_DESCRIPTION)
        Boolean insecure = false

        @Option(names = ['--openshift'], description = OPENSHIFT_DESCRIPTION)
        @JsonPropertyDescription(OPENSHIFT_DESCRIPTION)
        Boolean openshift = false

        @Option(names = ['--username'], description = USERNAME_DESCRIPTION)
        @JsonPropertyDescription(USERNAME_DESCRIPTION)
        String username = DEFAULT_ADMIN_USER

        @Option(names = ['--password'], description = PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(PASSWORD_DESCRIPTION)
        String password = DEFAULT_ADMIN_PW

        @Option(names = ['-y', '--yes'], description = PIPE_YES_DESCRIPTION)
        @JsonPropertyDescription(PIPE_YES_DESCRIPTION)
        Boolean yes = false

        @Option(names = ['--name-prefix'], description = NAME_PREFIX_DESCRIPTION)
        @JsonPropertyDescription(NAME_PREFIX_DESCRIPTION)
        String namePrefix = ''

        @Option(names = ['--destroy'], description = DESTROY_DESCRIPTION)
        @JsonPropertyDescription(DESTROY_DESCRIPTION)
        Boolean destroy = false

        @Option(names = ['--pod-resources'], description = POD_RESOURCES_DESCRIPTION)
        @JsonPropertyDescription(POD_RESOURCES_DESCRIPTION)
        Boolean podResources = false

        @Option(names = ['--git-name'], description = GIT_NAME_DESCRIPTION)
        @JsonPropertyDescription(GIT_NAME_DESCRIPTION)
        String gitName = 'Cloudogu'

        @Option(names = ['--git-email'], description = GIT_EMAIL_DESCRIPTION)
        @JsonPropertyDescription(GIT_EMAIL_DESCRIPTION)
        String gitEmail = 'hello@cloudogu.com'

        @Option(names = ['--base-url'], description = BASE_URL_DESCRIPTION)
        @JsonPropertyDescription(BASE_URL_DESCRIPTION)
        String baseUrl = ''

        @Option(names = ['--url-separator-hyphen'], description = URL_SEPARATOR_HYPHEN_DESCRIPTION)
        @JsonPropertyDescription(URL_SEPARATOR_HYPHEN_DESCRIPTION)
        Boolean urlSeparatorHyphen = false

        @Option(names = ['--mirror-repos'], description = MIRROR_REPOS_DESCRIPTION)
        @JsonPropertyDescription(MIRROR_REPOS_DESCRIPTION)
        Boolean mirrorRepos = false

        @Option(names = ['--skip-crds'], description = SKIP_CRDS_DESCRIPTION)
        @JsonPropertyDescription(SKIP_CRDS_DESCRIPTION)
        Boolean skipCrds = false

        @Option(names = ['--namespace-isolation'], description = NAMESPACE_ISOLATION_DESCRIPTION)
        @JsonPropertyDescription(NAMESPACE_ISOLATION_DESCRIPTION)
        Boolean namespaceIsolation = false

        @Option(names = ['--netpols'], description = NETPOLS_DESCRIPTION)
        @JsonPropertyDescription(NETPOLS_DESCRIPTION)
        Boolean netpols = false

        @Option(names = ['--cluster-admin'], description = CLUSTER_ADMIN_DESCRIPTION)
        @JsonPropertyDescription(CLUSTER_ADMIN_DESCRIPTION)
        Boolean clusterAdmin = false

        @Option(names = ["-p", "--profile"], description = APPLICATION_PROFIL)
        String profile

        static class NamespaceSchema {
            LinkedHashSet<String> dedicatedNamespaces = new LinkedHashSet<>()
            LinkedHashSet<String> tenantNamespaces = new LinkedHashSet<>()

            LinkedHashSet<String> getActiveNamespaces() {
                return new LinkedHashSet<>(dedicatedNamespaces + tenantNamespaces)
            }
        }

        @JsonIgnore
        String getTenantName() {
            return namePrefix.replaceAll(/-$/, "")
        }
    }

    static class FeaturesSchema {

        @Mixin
        @JsonPropertyDescription(ARGOCD_DESCRIPTION)
        ArgoCDSchema argocd = new ArgoCDSchema()

        @Mixin
        @JsonPropertyDescription(MAIL_DESCRIPTION)
        MailSchema mail = new MailSchema()

        @Mixin
        @JsonPropertyDescription(MONITORING_DESCRIPTION)
        MonitoringSchema monitoring = new MonitoringSchema()

        @Mixin
        @JsonPropertyDescription(SECRETS_DESCRIPTION)
        SecretsSchema secrets = new SecretsSchema()

        @Mixin
        @JsonPropertyDescription(INGRESS_DESCRIPTION)
        IngressSchema ingress = new IngressSchema()

        @Mixin
        @JsonPropertyDescription(CERTMANAGER_DESCRIPTION)
        CertManagerSchema certManager = new CertManagerSchema()
    }

    static class ArgoCDSchema {
        Boolean configOnly = false

        @Option(names = ['--argocd'], description = ARGOCD_ENABLE_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_ENABLE_DESCRIPTION)
        Boolean active = false

        @Option(names = ['--argocd-operator'], description = ARGOCD_OPERATOR_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_OPERATOR_DESCRIPTION)
        Boolean operator = false

        @Option(names = ['--argocd-url'], description = ARGOCD_URL_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_URL_DESCRIPTION)
        String url = ''

        @JsonPropertyDescription(ARGOCD_ENV_DESCRIPTION)
        List<Map<String, String>> env

        @Option(names = ['--argocd-email-from'], description = ARGOCD_EMAIL_FROM_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_EMAIL_FROM_DESCRIPTION)
        String emailFrom = 'argocd@example.org'

        @Option(names = ['--argocd-email-to-user'], description = ARGOCD_EMAIL_TO_USER_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_EMAIL_TO_USER_DESCRIPTION)
        String emailToUser = 'app-team@example.org'

        @Option(names = ['--argocd-email-to-admin'], description = ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
        String emailToAdmin = 'infra@example.org'

        @Option(names = ['--argocd-resource-inclusions-cluster'], description = ARGOCD_RESOURCE_INCLUSIONS_CLUSTER)
        @JsonPropertyDescription(ARGOCD_RESOURCE_INCLUSIONS_CLUSTER)
        String resourceInclusionsCluster = ''

        @Option(names = ['--argocd-namespace'], description = ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION)
        @JsonPropertyDescription(ARGOCD_CUSTOM_NAMESPACE_DESCRIPTION)
        String namespace = 'argocd'

        @JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
        Map<String, Object> values = [:]
    }

    static class MailSchema {

        Boolean active = false

        @Option(names = ['--mailhog', '--mail'], description = MAILHOG_ENABLE_DESCRIPTION, scope = ScopeType.INHERIT)
        @JsonPropertyDescription(MAILHOG_ENABLE_DESCRIPTION)
        Boolean mailhog = false

        @Option(names = ['--mailhog-url'], description = MAILHOG_URL_DESCRIPTION)
        @JsonPropertyDescription(MAILHOG_URL_DESCRIPTION)
        String mailhogUrl = ''

        @Option(names = ['--smtp-address'], description = SMTP_ADDRESS_DESCRIPTION)
        @JsonPropertyDescription(SMTP_ADDRESS_DESCRIPTION)
        String smtpAddress = ''

        @Option(names = ['--smtp-port'], description = SMTP_PORT_DESCRIPTION)
        @JsonPropertyDescription(SMTP_PORT_DESCRIPTION)
        Integer smtpPort = null

        @Option(names = ['--smtp-user'], description = SMTP_USER_DESCRIPTION)
        @JsonPropertyDescription(SMTP_USER_DESCRIPTION)
        String smtpUser = ''

        @Option(names = ['--smtp-password'], description = SMTP_PASSWORD_DESCRIPTION)
        @JsonPropertyDescription(SMTP_PASSWORD_DESCRIPTION)
        String smtpPassword = ''

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        @Mixin
        MailHelmSchema helm = new MailHelmSchema(
                chart: 'mailhog',
                repoURL: 'https://codecentric.github.io/helm-charts',
                version: '5.0.1')

        static class MailHelmSchema extends HelmConfigWithValues {
            @Option(names = ['--mailhog-image'], description = HELM_CONFIG_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
            String image = 'ghcr.io/cloudogu/mailhog:v1.0.1'
        }
    }

    static class MonitoringSchema {
        @Option(names = ['--metrics', '--monitoring'], description = MONITORING_ENABLE_DESCRIPTION)
        @JsonPropertyDescription(MONITORING_ENABLE_DESCRIPTION)
        Boolean active = false

        @Option(names = ['--grafana-url'], description = GRAFANA_URL_DESCRIPTION)
        @JsonPropertyDescription(GRAFANA_URL_DESCRIPTION)
        String grafanaUrl = ''

        @Option(names = ['--grafana-email-from'], description = GRAFANA_EMAIL_FROM_DESCRIPTION)
        @JsonPropertyDescription(GRAFANA_EMAIL_FROM_DESCRIPTION)
        String grafanaEmailFrom = 'grafana@example.org'

        @Option(names = ['--grafana-email-to'], description = GRAFANA_EMAIL_TO_DESCRIPTION)
        @JsonPropertyDescription(GRAFANA_EMAIL_TO_DESCRIPTION)
        String grafanaEmailTo = 'infra@example.org'

        @Mixin
        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        @SuppressWarnings('GroovyAssignabilityCheck')
        // Because of values
        MonitoringHelmSchema helm = new MonitoringHelmSchema(
                chart: 'kube-prometheus-stack',
                repoURL: 'https://prometheus-community.github.io/helm-charts',
                /* When updating this make sure to also test if air-gapped mode still works */
                version: '80.2.2',
                values: [:] // Otherwise values is null 🤷‍♂️
        )
        static class MonitoringHelmSchema extends HelmConfigWithValues {
            @Option(names = ['--grafana-image'], description = GRAFANA_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(GRAFANA_IMAGE_DESCRIPTION)
            String grafanaImage = ''

            @Option(names = ['--grafana-sidecar-image'], description = GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
            String grafanaSidecarImage = ''

            @Option(names = ['--prometheus-image'], description = PROMETHEUS_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(PROMETHEUS_IMAGE_DESCRIPTION)
            String prometheusImage = ''

            @Option(names = ['--prometheus-operator-image'], description = PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
            String prometheusOperatorImage = ''

            @Option(names = ['--prometheus-config-reloader-image'], description = PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
            String prometheusConfigReloaderImage = ''
        }
    }

    static class SecretsSchema {
        Boolean active = false

        @Mixin
        @JsonPropertyDescription(ESO_DESCRIPTION)
        ESOSchema externalSecrets = new ESOSchema()

        @Mixin
        @JsonPropertyDescription(VAULT_DESCRIPTION)
        VaultSchema vault = new VaultSchema()

        static class ESOSchema {

            @Mixin
            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            ESOHelmSchema helm = new ESOHelmSchema(
                    chart: 'external-secrets',
                    repoURL: 'https://charts.external-secrets.io',
                    version: '0.9.16'
            )
            static class ESOHelmSchema extends HelmConfigWithValues {
                @Option(names = ['--external-secrets-image'], description = EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
                @JsonPropertyDescription(EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
                String image = ''

                @Option(names = ['--external-secrets-certcontroller-image'], description = EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
                @JsonPropertyDescription(EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
                String certControllerImage = ''

                @Option(names = ['--external-secrets-webhook-image'], description = EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
                @JsonPropertyDescription(EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
                String webhookImage = ''
            }
        }

        static class VaultSchema {
            @Option(names = ['--vault'], description = VAULT_ENABLE_DESCRIPTION)
            @JsonPropertyDescription(VAULT_ENABLE_DESCRIPTION)
            VaultMode mode

            @Option(names = ['--vault-url'], description = VAULT_URL_DESCRIPTION)
            @JsonPropertyDescription(VAULT_URL_DESCRIPTION)
            String url = ''

            @Mixin
            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            VaultHelmSchema helm = new VaultHelmSchema(
                    chart: 'vault',
                    repoURL: 'https://helm.releases.hashicorp.com',
                    version: '0.25.0'
            )
            static class VaultHelmSchema extends HelmConfigWithValues {
                @Option(names = ['--vault-image'], description = VAULT_IMAGE_DESCRIPTION)
                @JsonPropertyDescription(VAULT_IMAGE_DESCRIPTION)
                String image = ''
            }
        }
    }

    static class IngressSchema {

        @Option(names = ['--ingress'], description = INGRESS_ENABLE_DESCRIPTION)
        @JsonPropertyDescription(INGRESS_ENABLE_DESCRIPTION)
        Boolean active = false

        @Mixin
        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        IngressHelmSchema helm = new IngressHelmSchema(
                chart: 'traefik',
                repoURL: 'https://traefik.github.io/charts',
                version: '39.0.0'
        )
        static class IngressHelmSchema extends HelmConfigWithValues {
            @Option(names = ['--ingress-image'], description = HELM_CONFIG_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
            String image = ''
        }

        String ingressNamespace = 'ingress'
    }

    static class CertManagerSchema {
        @Option(names = ['--cert-manager'], description = CERTMANAGER_ENABLE_DESCRIPTION)
        @JsonPropertyDescription(CERTMANAGER_ENABLE_DESCRIPTION)
        Boolean active = false

        @Mixin
        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        CertManagerHelmSchema helm = new CertManagerHelmSchema(
                chart: 'cert-manager',
                repoURL: 'https://charts.jetstack.io',
                version: '1.16.1'
        )
        static class CertManagerHelmSchema extends HelmConfigWithValues {

            @Option(names = ['--cert-manager-image'], description = CERTMANAGER_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(CERTMANAGER_IMAGE_DESCRIPTION)
            String image = ''

            @Option(names = ['--cert-manager-webhook-image'], description = CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION)
            String webhookImage = ''

            @Option(names = ['--cert-manager-cainjector-image'], description = CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION)
            String cainjectorImage = ''

            @Option(names = ['--cert-manager-acme-solver-image'], description = CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION)
            String acmeSolverImage = ''

            @Option(names = ['--cert-manager-startup-api-check-image'], description = CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION)
            @JsonPropertyDescription(CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION)
            String startupAPICheckImage = ''

        }
    }

    static enum ContentRepoType {
        FOLDER_BASED, COPY, MIRROR
    }

    static enum VaultMode {
        dev, prod
    }

    /**
     * This defines, how customer repos will be updated.
     * See {@link ConfigConstants#CONTENT_REPO_TARGET_OVERWRITE_MODE_DESCRIPTION}
     */
    static enum OverwriteMode {
        INIT, RESET, UPGRADE
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(GString, new JsonSerializer<GString>() {
                @Override
                void serialize(GString value, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
                    jsonGenerator.writeString(value.toString())
                }
            }))

    static Config fromMap(Map map) {
        objectMapper.convertValue(map, Config)
    }

    Map toMap() {
        objectMapper.convertValue(this, Map)
    }

    String toYaml(boolean includeInternals) {
        createYamlMapper(includeInternals)
                .writeValueAsString(this)
    }

    private static YAMLMapper createYamlMapper(boolean includeInternals) {
        if (!includeInternals) {
            new YAMLMapper()
                    .registerModule(new SimpleModule().setSerializerModifier(new BeanSerializerModifier() {
                        @Override
                        List<BeanPropertyWriter> changeProperties(SerializationConfig serializationConfig, BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
                            beanProperties.findAll { writer -> writer.getAnnotation(JsonPropertyDescription) != null }
                        }
                    })) as YAMLMapper
        } else {
            new YAMLMapper()
        }
    }
}
