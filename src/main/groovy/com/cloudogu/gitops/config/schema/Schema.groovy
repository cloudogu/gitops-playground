//file:noinspection unused
package com.cloudogu.gitops.config.schema 

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

import static com.cloudogu.gitops.config.ConfigConstants.*

/**
 * The schema for the configuration file.
 * It is used to validate the passed yaml file.
 *
 * Currently only contains variables that are used in groovy only.
 *
 * When changing values make sure to
 * * recreate file configuration.schema.json using JsonSchemaGenerator (copy output into file an format using IDE)
 * * modify GitOpsPlaygroundCli and ApplicationConfigurator as well.
 * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli* @see com.cloudogu.gitops.config.ApplicationConfigurator
 */
class Schema {
    @JsonPropertyDescription(REGISTRY_DESCRIPTION)
    RegistrySchema registry
    @JsonPropertyDescription(JENKINS_DESCRIPTION)
    JenkinsSchema jenkins
    @JsonPropertyDescription(SCMM_DESCRIPTION)
    ScmmSchema scmm
    @JsonPropertyDescription(APPLICATION_DESCRIPTION)
    ApplicationSchema application
    @JsonPropertyDescription(IMAGES_DESCRIPTION)
    ImagesSchema images
    @JsonPropertyDescription(REPOSITORIES_DESCRIPTION)
    RepositoriesSchema repositories
    @JsonPropertyDescription(FEATURES_DESCRIPTION)
    FeaturesSchema features

    @JsonClassDescription(HELM_CONFIG_DESCRIPTION)
    static class HelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_CHART_DESCRIPTION)
        String chart = ""
        @JsonPropertyDescription(HELM_CONFIG_REPO_URL_DESCRIPTION)
        String repoURL = ""
        @JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
        String version = ""
    }
    
    @JsonClassDescription(HELM_CONFIG_DESCRIPTION)
    static class HelmConfigWithValues extends HelmConfig {
        @JsonPropertyDescription(HELM_CONFIG_VALUES_DESCRIPTION)
        Map<String, Object> values
    }

    static class RegistrySchema {
        // boolean internal = true
        // boolean twoRegistries = false
        @JsonPropertyDescription(REGISTRY_INTERNAL_PORT_DESCRIPTION)
        int internalPort
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

        HelmConfig helm
    }

    static class JenkinsSchema {
        // boolean internal = true
        @JsonPropertyDescription(JENKINS_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(JENKINS_USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(JENKINS_PASSWORD_DESCRIPTION)
        String password = ""
        // String urlForScmm = ""
        @JsonPropertyDescription(JENKINS_METRICS_USERNAME_DESCRIPTION)
        String metricsUsername = ""
        @JsonPropertyDescription(JENKINS_METRICS_PASSWORD_DESCRIPTION)
        String metricsPassword = ""
        @JsonPropertyDescription(MAVEN_CENTRAL_MIRROR_DESCRIPTION)
        String mavenCentralMirror = ""


        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        JenkinsHelmSchema helm
        static class JenkinsHelmSchema {
            // Once these can be used get rid of this class and use HelmConfig instead
            // String chart = ""
            // String repoURL = ""
            @JsonPropertyDescription(HELM_CONFIG_VERSION_DESCRIPTION)
            String version = ""
        }
    }

    static class ScmmSchema {
        // boolean internal = true
        @JsonPropertyDescription(SCMM_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(SCMM_USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(SCMM_PASSWORD_DESCRIPTION)
        String password = ""

        // String gitOpsUsername = ""
        // String urlForJenkins = ""
        // String host = ""
        // String protocol = ""
        // String ingress = ""

        HelmConfig helm
    }

    static class ApplicationSchema {
        // group remote
        @JsonPropertyDescription(REMOTE_DESCRIPTION)
        boolean remote = false
        @JsonPropertyDescription(INSECURE_DESCRIPTION)
        boolean insecure = false
        @JsonPropertyDescription(LOCAL_HELM_CHART_FOLDER_DESCRIPTION)
        String localHelmChartFolder = ""
        @JsonPropertyDescription(OPENSHIFT_DESCRIPTION)
        boolean openshift = false
        
        // args group configuration
        @JsonPropertyDescription(USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(PASSWORD_DESCRIPTION)
        String password = ""
        @JsonPropertyDescription(PIPE_YES_DESCRIPTION)
        boolean yes = false
        // boolean runningInsideK8s = ""
        @JsonPropertyDescription(NAME_PREFIX_DESCRIPTION)
        String namePrefix = ""

        // Setting these in the config file makes little sense
        // String configFile
        // String configMap
        // boolean outputConfigFile = false
        // These can't be set because they are evaluated first thing on app start, before config file is read
        // boolean debug = false
        // boolean trace = false
        @JsonPropertyDescription(DESTROY_DESCRIPTION)
        boolean destroy = false
        @JsonPropertyDescription(POD_RESOURCES_DESCRIPTION)
        boolean podResources = false

        // String namePrefixForEnvVars = ""
        @JsonPropertyDescription(GIT_NAME_DESCRIPTION)
        String gitName = ''
        @JsonPropertyDescription(GIT_EMAIL_DESCRIPTION)
        String gitEmail = ''

        @JsonPropertyDescription(BASE_URL_DESCRIPTION)
        String baseUrl = ""
        @JsonPropertyDescription(URL_SEPARATOR_HYPHEN_DESCRIPTION)
        boolean urlSeparatorHyphen = false
        @JsonPropertyDescription(MIRROR_REPOS_DESCRIPTION)
        boolean mirrorRepos = false
        @JsonPropertyDescription(SKIP_CRDS_DESCRIPTION)
        boolean skipCrds = false
        @JsonPropertyDescription(NAMESPACE_ISOLATION_DESCRIPTION)
        boolean namespaceIsolation = false
    }

    static class ImagesSchema {
        @JsonPropertyDescription(KUBECTL_IMAGE_DESCRIPTION)
        String kubectl = ""
        @JsonPropertyDescription(HELM_IMAGE_DESCRIPTION)
        String helm = ""
        @JsonPropertyDescription(KUBEVAL_IMAGE_DESCRIPTION)
        String kubeval = ""
        @JsonPropertyDescription(HELMKUBEVAL_IMAGE_DESCRIPTION)
        String helmKubeval = ""
        @JsonPropertyDescription(YAMLLINT_IMAGE_DESCRIPTION)
        String yamllint = ""
        @JsonPropertyDescription(NGINX_IMAGE_DESCRIPTION)
        String nginx = ""
        @JsonPropertyDescription(PETCLINIC_IMAGE_DESCRIPTION)
        String petclinic = ""
        @JsonPropertyDescription(MAVEN_IMAGE_DESCRIPTION)
        String maven = ""
    }

    static class RepositoriesSchema {
        @JsonPropertyDescription(SPRING_BOOT_HELM_CHART_DESCRIPTION)
        RepositorySchemaWithRef springBootHelmChart
        @JsonPropertyDescription(SPRING_PETCLINIC_DESCRIPTION)
        RepositorySchemaWithRef springPetclinic
        @JsonPropertyDescription(GITOPS_BUILD_LIB_DESCRIPTION)
        RepositorySchema gitopsBuildLib
        @JsonPropertyDescription(CES_BUILD_LIB_DESCRIPTION)
        RepositorySchema cesBuildLib
    }

    static class RepositorySchema {
        @JsonPropertyDescription(REPO_URL_DESCRIPTION)
        String url
    }

    static class RepositorySchemaWithRef extends RepositorySchema {
        @JsonPropertyDescription(REPO_REF_DESCRIPTION)
        String ref
    }

    static class FeaturesSchema {
        @JsonPropertyDescription(ARGOCD_DESCRIPTION)
        ArgoCDSchema argocd
        @JsonPropertyDescription(MAILHOG_DESCRIPTION)
        MailSchema mail
        @JsonPropertyDescription(MONITORING_DESCRIPTION)
        MonitoringSchema monitoring
        @JsonPropertyDescription(SECRETS_DESCRIPTION)
        SecretsSchema secrets
        @JsonPropertyDescription(INGRESS_NGINX_DESCRIPTION)
        IngressNginxSchema ingressNginx
        @JsonPropertyDescription(EXAMPLE_APPS_DESCRIPTION)
        ExampleAppsSchema exampleApps
    }

    static class ArgoCDSchema {
        @JsonPropertyDescription(ARGOCD_ENABLE_DESCRIPTION)
        boolean active = false
        @JsonPropertyDescription(ARGOCD_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(ARGOCD_EMAIL_FROM_DESCRIPTION)
        String emailFrom = ""
        @JsonPropertyDescription(ARGOCD_EMAIL_TO_USER_DESCRIPTION)
        String emailToUser = ""
        @JsonPropertyDescription(ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
        String emailToAdmin = ""
    }

    static class MailSchema {
        // boolean active = false
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
        MailHelmSchema helm
        static class MailHelmSchema extends HelmConfig {
            @JsonPropertyDescription(HELM_CONFIG_IMAGE_DESCRIPTION)
            String image = ""
        }
    }

    static class MonitoringSchema {
        @JsonPropertyDescription(MONITORING_ENABLE_DESCRIPTION)
        boolean active = true
        @JsonPropertyDescription(GRAFANA_URL_DESCRIPTION)
        String grafanaUrl = ""
        @JsonPropertyDescription(GRAFANA_EMAIL_FROM_DESCRIPTION)
        String grafanaEmailFrom = ""
        @JsonPropertyDescription(GRAFANA_EMAIL_TO_DESCRIPTION)
        String grafanaEmailTo = ""

        @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
        MonitoringHelmSchema helm
        static class MonitoringHelmSchema extends HelmConfigWithValues {
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

    static class SecretsSchema {
        // boolean active = false
        @JsonPropertyDescription(ESO_DESCRIPTION)
        ESOSchema externalSecrets
        @JsonPropertyDescription(VAULT_DESCRIPTION)
        VaultSchema vault

        static class ESOSchema {
            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            ESOHelmSchema helm
            static class ESOHelmSchema extends HelmConfig {
                @JsonPropertyDescription(EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
                String image = ""
                @JsonPropertyDescription(EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
                String certControllerImage = ""
                @JsonPropertyDescription(EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
                String webhookImage = ""
            }
        }

        static class VaultSchema {
            @JsonPropertyDescription(VAULT_ENABLE_DESCRIPTION)
            String mode = ""
            @JsonPropertyDescription(VAULT_URL_DESCRIPTION)
            String url = ""

            @JsonPropertyDescription(HELM_CONFIG_DESCRIPTION)
            VaultHelmSchema helm
            static class VaultHelmSchema extends HelmConfig {
                @JsonPropertyDescription(VAULT_IMAGE_DESCRIPTION)
                String image = ""
            }
        }
    }

    static class IngressNginxSchema {
        @JsonPropertyDescription(INGRESS_NGINX_ENABLE_DESCRIPTION)
        boolean active = false

        HelmConfigWithValues helm
    }

    static class ExampleAppsSchema {
        @JsonPropertyDescription(PETCLINIC_DESCRIPTION)
        ExampleAppSchema petclinic
        @JsonPropertyDescription(NGINX_DESCRIPTION)
        ExampleAppSchema nginx

        static class ExampleAppSchema {
            @JsonPropertyDescription(BASE_DOMAIN_DESCRIPTION)
            String baseDomain = ""
        }
    }
}
