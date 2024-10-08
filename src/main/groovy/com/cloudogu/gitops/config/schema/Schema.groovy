//file:noinspection unused
package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import groovy.transform.Immutable

import static com.cloudogu.gitops.config.ConfigConstants.*

/**
 * The global configuration object.
 *
 * Also used to create the schema for the configuration file or map, which is used to validate the passed YAML file.
 *
 * Note that all properties marked with {@link JsonPropertyDescription} are written into the Schema and there are
 * external properties that can be changed by the user.
 * All other properties are internal.
 *
 * When changing values make sure to
 * * recreate file configuration.schema.json using JsonSchemaGenerator (copy output into file an format using IDE)
 * * modify GitOpsPlaygroundCli as well.
 * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli* @see com.cloudogu.gitops.config.ApplicationConfigurator
 */
@Immutable
class Schema {

    // When updating please also update in Dockerfile
    public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:3.15.4-1"
    // When updating please also adapt in Dockerfile, vars.tf and init-cluster.sh
    public static final String K8S_VERSION = "1.29"
    public static final String DEFAULT_ADMIN_USER = 'admin'
    public static final String DEFAULT_ADMIN_PW = 'admin'
    public static final int DEFAULT_REGISTRY_PORT = 30000

    @JsonPropertyDescription(REGISTRY_DESCRIPTION)
    RegistrySchema registry = new RegistrySchema()
    @JsonPropertyDescription(JENKINS_DESCRIPTION)
    JenkinsSchema jenkins = new JenkinsSchema()
    @JsonPropertyDescription(SCMM_DESCRIPTION)
    ScmmSchema scmm = new ScmmSchema()
    @JsonPropertyDescription(APPLICATION_DESCRIPTION)
    ApplicationSchema application = new ApplicationSchema()
    @JsonPropertyDescription(IMAGES_DESCRIPTION)
    ImagesSchema images = new ImagesSchema()
    @JsonPropertyDescription(REPOSITORIES_DESCRIPTION)
    RepositoriesSchema repositories = new RepositoriesSchema()
    @JsonPropertyDescription(FEATURES_DESCRIPTION)
    FeaturesSchema features = new FeaturesSchema()

    /* Non-immutable type allowing for extension */
    static class BaseHelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_CHART_DESCRIPTION)
        String chart = ""
        @JsonPropertyDescription(HELM_CONFIG_REPO_URL_DESCRIPTION)
        String repoURL = ""
        @JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
        String version = ""
    }

    @Immutable
    static class HelmConfig extends BaseHelmConfig {}

    /* Non-immutable type allowing for extension */
    static class BaseHelmConfigWithValues extends BaseHelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
        Map<String, Object> values = [:]
    }

    @Immutable
    static class HelmConfigWithValues extends BaseHelmConfigWithValues {}

    @Immutable
    static class RegistrySchema {
        boolean internal = true
        boolean twoRegistries = false

        @JsonPropertyDescription(REGISTRY_INTERNAL_PORT_DESCRIPTION)
        int internalPort = DEFAULT_REGISTRY_PORT
        @JsonPropertyDescription(REGISTRY_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(REGISTRY_PATH_DESCRIPTION)
        String path = ""
        @JsonPropertyDescription(REGISTRY_USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(REGISTRY_PASSWORD_DESCRIPTION)
        String password = ""
        // Alternative: Use different registries, e.g. in air-gapped envs
        // "Proxy" registry for 3rd party images, e.g. base images
        @JsonPropertyDescription(REGISTRY_PROXY_URL_DESCRIPTION)
        String proxyUrl = ""
        @JsonPropertyDescription(REGISTRY_PROXY_USERNAME_DESCRIPTION)
        String proxyUsername = ""
        @JsonPropertyDescription(REGISTRY_PROXY_PASSWORD_DESCRIPTION)
        String proxyPassword = ""
        // Alternative set of credentials for url, used only for image pull secrets
        @JsonPropertyDescription(REGISTRY_USERNAME_RO_DESCRIPTION)
        String readOnlyUsername = ''
        @JsonPropertyDescription(REGISTRY_PASSWORD_RO_DESCRIPTION)
        String readOnlyPassword = ''
        @JsonPropertyDescription(REGISTRY_CREATE_IMAGE_PULL_SECRETS_DESCRIPTION)
        Boolean createImagePullSecrets = false

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        HelmConfig helm = new HelmConfig(
                chart: 'docker-registry',
                repoURL: 'https://helm.twun.io',
                version: '2.2.3')
    }

    @Immutable
    static class JenkinsSchema {
        Boolean internal = true
        /* This is the URL configured in SCMM inside the Jenkins Plugin, e.g. at http://scmm.localhost/scm/admin/settings/jenkins
          We use the K8s service as default name here, because it is the only option:
          "jenkins.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9090) will not work on Windows and MacOS.

          For production we overwrite this when config.jenkins["url"] is set.
          See addJenkinsConfig() and the comment at scmm.urlForJenkins */
        String urlForScmm = "http://jenkins"

        @JsonPropertyDescription(JENKINS_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(JENKINS_USERNAME_DESCRIPTION)
        String username = DEFAULT_ADMIN_USER
        @JsonPropertyDescription(JENKINS_PASSWORD_DESCRIPTION)
        String password = DEFAULT_ADMIN_PW
        @JsonPropertyDescription(JENKINS_METRICS_USERNAME_DESCRIPTION)
        String metricsUsername = "metrics"
        @JsonPropertyDescription(JENKINS_METRICS_PASSWORD_DESCRIPTION)
        String metricsPassword = "metrics"
        @JsonPropertyDescription(MAVEN_CENTRAL_MIRROR_DESCRIPTION)
        String mavenCentralMirror = ""
        @JsonPropertyDescription(JENKINS_ADDITIONAL_ENVS_DESCRIPTION)
        Map<String, String> jenkinsAdditionalEnvs = [:]

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        JenkinsHelmSchema helm = new JenkinsHelmSchema()
        @Immutable
        static class JenkinsHelmSchema {
            // Once these can be used get rid of this class and use HelmConfig instead
            // String chart = "jenkins"
            // String repoURL = "https://charts.jenkins.io"
            /* When Upgrading helm chart, also upgrade controller.tag in jenkins/values.yaml
            In addition:
             - Also upgrade plugins. See docs/developers.md
             */
            @JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
            String version = '5.5.11'
        }
    }

    @Immutable
    static class ScmmSchema {
        Boolean internal = true
        String gitOpsUsername = ""
        /* This corresponds to the "Base URL" in SCMM Settings.
           We use the K8s service as default name here, to make the build on push feature (webhooks from SCMM to Jenkins that trigger builds) work in k3d.
           The webhook contains repository URLs that start with the "Base URL" Setting of SCMM.
           Jenkins checks these repo URLs and triggers all builds that match repo URLs.
           In k3d, we have to define the repos in Jenkins using the K8s Service name, because they are the only option.
           "scmm.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9091) will not work on Windows and MacOS.
           So, we have to use the matching URL in SCMM as well.

           For production we overwrite this when config.scmm["url"] is set.
           See addScmmConfig() */
        String urlForJenkins = 'http://scmm-scm-manager/scm'
        String host = ""
        String protocol = ""
        String ingress = ""

        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = DEFAULT_ADMIN_USER
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = DEFAULT_ADMIN_PW

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        HelmConfig helm = new HelmConfig(
                chart: 'scm-manager',
                repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                version: '3.2.1')
    }

    @Immutable
    static class ApplicationSchema {
        Boolean runningInsideK8s = false
        String namePrefixForEnvVars = ""

        // Setting these in the config file makes little sense
        // String configFile
        // String configMap
        // boolean outputConfigFile = false
        // These cannot be set via config file but might be read by features at runtime
        // e.g. to make legacy bash files output trace
        boolean debug = false
        boolean trace = false

        @JsonPropertyDescription(REMOTE_DESCRIPTION)
        boolean remote = false
        @JsonPropertyDescription(INSECURE_DESCRIPTION)
        boolean insecure = false
        // TODO only for dev, can we remove this from config file?
        // Take from env because the Dockerfile provides a local copy of the repo for air-gapped mode
        @JsonPropertyDescription(LOCAL_HELM_CHART_FOLDER_DESCRIPTION)
        String localHelmChartFolder = System.getenv('LOCAL_HELM_CHART_FOLDER')
        @JsonPropertyDescription(OPENSHIFT_DESCRIPTION)
        boolean openshift = false

        @JsonPropertyDescription(USERNAME_DESCRIPTION)
        String username = DEFAULT_ADMIN_USER
        @JsonPropertyDescription(PASSWORD_DESCRIPTION)
        String password = DEFAULT_ADMIN_PW
        @JsonPropertyDescription(PIPE_YES_DESCRIPTION)
        boolean yes = false
        @JsonPropertyDescription(NAME_PREFIX_DESCRIPTION)
        String namePrefix = ""
        @JsonPropertyDescription(DESTROY_DESCRIPTION)
        boolean destroy = false
        @JsonPropertyDescription(POD_RESOURCES_DESCRIPTION)
        boolean podResources = false
        @JsonPropertyDescription(GIT_NAME_DESCRIPTION)
        String gitName = 'Cloudogu'
        @JsonPropertyDescription(GIT_EMAIL_DESCRIPTION)
        String gitEmail = 'hello@cloudogu.com'
        @JsonPropertyDescription(BASE_URL_DESCRIPTION)
        String baseUrl = null
        @JsonPropertyDescription(URL_SEPARATOR_HYPHEN_DESCRIPTION)
        boolean urlSeparatorHyphen = false
        @JsonPropertyDescription(MIRROR_REPOS_DESCRIPTION)
        boolean mirrorRepos = false
        @JsonPropertyDescription(SKIP_CRDS_DESCRIPTION)
        boolean skipCrds = false
        @JsonPropertyDescription(NAMESPACE_ISOLATION_DESCRIPTION)
        boolean namespaceIsolation = false
        @JsonPropertyDescription(NETPOLS_DESCRIPTION)
        boolean netpols = false
    }

    @Immutable
    static class ImagesSchema {
        @JsonPropertyDescription(KUBECTL_IMAGE_DESCRIPTION)
        String kubectl = "bitnami/kubectl:$K8S_VERSION"
        // cloudogu/helm also contains kubeval and helm kubeval plugin. Using the same image makes builds faster
        @JsonPropertyDescription(HELM_IMAGE_DESCRIPTION)
        String helm = HELM_IMAGE
        @JsonPropertyDescription(KUBEVAL_IMAGE_DESCRIPTION)
        String kubeval = HELM_IMAGE
        @JsonPropertyDescription(HELMKUBEVAL_IMAGE_DESCRIPTION)
        String helmKubeval = HELM_IMAGE
        @JsonPropertyDescription(YAMLLINT_IMAGE_DESCRIPTION)
        String yamllint = "cytopia/yamllint:1.25-0.7"
        @JsonPropertyDescription(NGINX_IMAGE_DESCRIPTION)
        String nginx = null
        @JsonPropertyDescription(PETCLINIC_IMAGE_DESCRIPTION)
        String petclinic = 'eclipse-temurin:11-jre-alpine'
        @JsonPropertyDescription(MAVEN_IMAGE_DESCRIPTION)
        String maven = null
    }

    @Immutable
    static class RepositoriesSchema {
        @JsonPropertyDescription(SPRING_BOOT_HELM_CHART_DESCRIPTION)
        RepositorySchemaWithRef springBootHelmChart = new RepositorySchemaWithRef(
                // Take from env or use default because the Dockerfile provides a local copy of the repo
                url: System.getenv('SPRING_BOOT_HELM_CHART_REPO') ?: 'https://github.com/cloudogu/spring-boot-helm-chart.git',
                ref: '0.3.2'
        )
        @JsonPropertyDescription(SPRING_PETCLINIC_DESCRIPTION)
        RepositorySchemaWithRef springPetclinic = new RepositorySchemaWithRef(
                url: System.getenv('SPRING_PETCLINIC_REPO') ?: 'https://github.com/cloudogu/spring-petclinic.git',
                ref: 'b0e0d18'
        )
        @JsonPropertyDescription(GITOPS_BUILD_LIB_DESCRIPTION)
        RepositorySchema gitopsBuildLib = new RepositorySchema(
                url: System.getenv('GITOPS_BUILD_LIB_REPO') ?: 'https://github.com/cloudogu/gitops-build-lib.git'
        )
        @JsonPropertyDescription(CES_BUILD_LIB_DESCRIPTION)
        RepositorySchema cesBuildLib = new RepositorySchema(
                url: System.getenv('CES_BUILD_LIB_REPO') ?: 'https://github.com/cloudogu/ces-build-lib.git'
        )
    }

    static class BaseRepositorySchema {
        @JsonPropertyDescription(REPO_URL_DESCRIPTION)
        String url = ''
    }

    @Immutable
    static class RepositorySchema extends BaseRepositorySchema {}

    @Immutable
    static class RepositorySchemaWithRef extends BaseRepositorySchema {
        @JsonPropertyDescription(REPO_REF_DESCRIPTION)
        String ref = ''
    }

    @Immutable
    static class FeaturesSchema {
        @JsonPropertyDescription(ARGOCD_DESCRIPTION)
        ArgoCDSchema argocd = new ArgoCDSchema()
        @JsonPropertyDescription(MAIL_DESCRIPTION)
        MailSchema mail = new MailSchema()
        @JsonPropertyDescription(MONITORING_DESCRIPTION)
        MonitoringSchema monitoring = new MonitoringSchema()
        @JsonPropertyDescription(SECRETS_DESCRIPTION)
        SecretsSchema secrets = new SecretsSchema()
        @JsonPropertyDescription(INGRESS_NGINX_DESCRIPTION)
        IngressNginxSchema ingressNginx = new IngressNginxSchema()
        @JsonPropertyDescription(CERTMANAGER_DESCRIPTION)
        CertManagerSchema certManager
        IngressNginxSchema ingressNginx = new IngressNginxSchema()
        @JsonPropertyDescription(EXAMPLE_APPS_DESCRIPTION)
        ExampleAppsSchema exampleApps = new ExampleAppsSchema()
    }

    @Immutable
    static class ArgoCDSchema {
        @JsonPropertyDescription(ARGOCD_ENABLE_DESCRIPTION)
        boolean active = false
        @JsonPropertyDescription(ARGOCD_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(ARGOCD_EMAIL_FROM_DESCRIPTION)
        String emailFrom = 'argocd@example.org'
        @JsonPropertyDescription(ARGOCD_EMAIL_TO_USER_DESCRIPTION)
        String emailToUser = 'app-team@example.org'
        @JsonPropertyDescription(ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
        String emailToAdmin = 'infra@example.org'
    }

    @Immutable
    static class MailSchema {
        boolean active = false

        @JsonPropertyDescription(MAILHOG_ENABLE_DESCRIPTION)
        boolean mailhog = false
        @JsonPropertyDescription(MAILHOG_URL_DESCRIPTION)
        String mailhogUrl = ""
        @JsonPropertyDescription(SMTP_ADDRESS_DESCRIPTION)
        String smtpAddress = ""
        @JsonPropertyDescription(SMTP_PORT_DESCRIPTION)
        Integer smtpPort = null
        @JsonPropertyDescription(SMTP_USER_DESCRIPTION)
        String smtpUser = ""
        @JsonPropertyDescription(SMTP_PASSWORD_DESCRIPTION)
        String smtpPassword = ""

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        MailHelmSchema helm = new MailHelmSchema(
                chart: 'mailhog',
                repoURL: 'https://codecentric.github.io/helm-charts',
                version: '5.0.1')
        @Immutable
        static class MailHelmSchema extends BaseHelmConfig {
            @JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
            String image = 'ghcr.io/cloudogu/mailhog:v1.0.1'
        }
    }

    @Immutable
    static class MonitoringSchema {
        @JsonPropertyDescription(MONITORING_ENABLE_DESCRIPTION)
        boolean active = false
        @JsonPropertyDescription(GRAFANA_URL_DESCRIPTION)
        String grafanaUrl = ""
        @JsonPropertyDescription(GRAFANA_EMAIL_FROM_DESCRIPTION)
        String grafanaEmailFrom = 'grafana@example.org'
        @JsonPropertyDescription(GRAFANA_EMAIL_TO_DESCRIPTION)
        String grafanaEmailTo = 'infra@example.org'

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        @SuppressWarnings('GroovyAssignabilityCheck') // Because of values
        MonitoringHelmSchema helm = new MonitoringHelmSchema(
                chart: 'kube-prometheus-stack',
                repoURL: 'https://prometheus-community.github.io/helm-charts',
                /* When updating this make sure to also test if air-gapped mode still works */
                version: '58.2.1',
                values: [:] // Otherwise values is null 🤷‍♂️
        )
        @Immutable
        static class MonitoringHelmSchema extends BaseHelmConfigWithValues {
            @JsonPropertyDescription(GRAFANA_IMAGE_DESCRIPTION)
            String grafanaImage = ""
            @JsonPropertyDescription(GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
            String grafanaSidecarImage = ""
            @JsonPropertyDescription(PROMETHEUS_IMAGE_DESCRIPTION)
            String prometheusImage = ""
            @JsonPropertyDescription(PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
            String prometheusOperatorImage = ""
            @JsonPropertyDescription(PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
            String prometheusConfigReloaderImage = ""
        }
    }

    @Immutable
    static class SecretsSchema {
        boolean active = false

        @JsonPropertyDescription(ESO_DESCRIPTION)
        ESOSchema externalSecrets = new ESOSchema()
        @JsonPropertyDescription(VAULT_DESCRIPTION)
        VaultSchema vault = new VaultSchema()

        @Immutable
        static class ESOSchema {
            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            ESOHelmSchema helm = new ESOHelmSchema(
                    chart: 'external-secrets',
                    repoURL: 'https://charts.external-secrets.io',
                    version: '0.9.16'
            )
            @Immutable
            static class ESOHelmSchema extends BaseHelmConfig {
                @JsonPropertyDescription(EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
                String image = ""
                @JsonPropertyDescription(EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
                String certControllerImage = ""
                @JsonPropertyDescription(EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
                String webhookImage = ""
            }
        }

        @Immutable
        static class VaultSchema {
            @JsonPropertyDescription(VAULT_ENABLE_DESCRIPTION)
            String mode = ""
            @JsonPropertyDescription(VAULT_URL_DESCRIPTION)
            String url = ""

            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            VaultHelmSchema helm = new VaultHelmSchema(
                    chart: 'vault',
                    repoURL: 'https://helm.releases.hashicorp.com',
                    version: '0.25.0'
            )
            @Immutable
            static class VaultHelmSchema extends BaseHelmConfig {
                @JsonPropertyDescription(VAULT_IMAGE_DESCRIPTION)
                String image = ""
            }
        }
    }

    @Immutable
    static class IngressNginxSchema {
        @JsonPropertyDescription(INGRESS_NGINX_ENABLE_DESCRIPTION)
        boolean active = false
        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        IngressNginxHelmSchema helm = new IngressNginxHelmSchema(
                chart: 'ingress-nginx',
                repoURL: 'https://kubernetes.github.io/ingress-nginx',
                version: '4.9.1'
        )
        @Immutable
        static class IngressNginxHelmSchema extends BaseHelmConfigWithValues {
            @JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
            String image = ""
        }
    }

    static class CertManagerSchema {
        @JsonPropertyDescription(CERTMANAGER_ENABLE_DESCRIPTION)
        Boolean active = false

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        CertManagerHelmSchema helm
        static class CertManagerHelmSchema extends HelmConfig {
            @JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
            Map<String, Object> values

            @JsonPropertyDescription(CERTMANAGER_IMAGE_DESCRIPTION)
            String image = ""

            @JsonPropertyDescription(CERTMANAGER_WEBHOOK_IMAGE_DESCRIPTION)
            String webhookImage = ""

            @JsonPropertyDescription(CERTMANAGER_CAINJECTOR_IMAGE_DESCRIPTION)
            String cainjectorImage = ""

            @JsonPropertyDescription(CERTMANAGER_ACME_SOLVER_IMAGE_DESCRIPTION)
            String acmeSolverImage = ""

            @JsonPropertyDescription(CERTMANAGER_STARTUP_API_CHECK_IMAGE_DESCRIPTION)
            String startupAPICheckImage = ""

        }
    }

    @Immutable
    static class ExampleAppsSchema {
        @JsonPropertyDescription(PETCLINIC_DESCRIPTION)
        ExampleAppSchema petclinic = new ExampleAppSchema()
        @JsonPropertyDescription(NGINX_DESCRIPTION)
        ExampleAppSchema nginx = new ExampleAppSchema()

        @Immutable
        static class ExampleAppSchema {
            @JsonPropertyDescription(BASE_DOMAIN_DESCRIPTION)
            String baseDomain = ""
        }
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new SimpleModule().addSerializer(GString, new JsonSerializer<GString>() {
                @Override
                void serialize(GString value, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
                    jsonGenerator.writeString(value.toString())
                }
            }))

    static Schema fromMap(Map map) {
        objectMapper.convertValue(map, Schema)
    }

    Map toMap() {
        objectMapper.convertValue(this, Map)
    }
}
