//file:noinspection unused
package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

import static com.cloudogu.gitops.config.DescriptionConstants.*

/**
 * The schema for the configuration file.
 * It is used to validate the passed yaml file.
 *
 * Currently only contains variables that are used in groovy only.
 *
 * When changing values make sure to
 * * recreate file configuration.schema.json using JsonSchemaGenerator (copy output into file an format using IDE)
 * * modify GitOpsPlaygroundCli and ApplicationConfigurator as well.
 * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli
 * @see com.cloudogu.gitops.config.ApplicationConfigurator
 */
class Schema {
     RegistrySchema registry
//     JenkinsSchema jenkins // used in bash
//     ScmmSchema scmm // used in bash
//     ApplicationSchema application // used in bash
     ImagesSchema images
     FeaturesSchema features

    @JsonClassDescription(HELM_DESCRIPTION)
    static class HelmConfig {
        // common values
        @JsonPropertyDescription(HELM_CHART_DESCRIPTION)
        String chart = ""
        @JsonPropertyDescription(HELM_REPO_URL_DESCRIPTION)
        String repoURL = ""
        @JsonPropertyDescription(HELM_VERSION_DESCRIPTION)
        String version = ""
    }

    @JsonClassDescription(REGISTRY_DESCRIPTION)
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
        // "Pull" registry for 3rd party images, e.g. base images
        @JsonPropertyDescription(REGISTRY_PULL_URL_DESCRIPTION)
        String pullUrl = ""
        @JsonPropertyDescription(REGISTRY_PULL_USERNAME_DESCRIPTION)
        String pullUsername = ""
        @JsonPropertyDescription(REGISTRY_PULL_PASSWORD_DESCRIPTION)
        String pullPassword = ""
        // "Push" registry for writing application specific images
        @JsonPropertyDescription(REGISTRY_PUSH_URL_DESCRIPTION)
        String pushUrl = ""
        @JsonPropertyDescription(REGISTRY_PUSH_PATH_DESCRIPTION)
        String pushPath = ""
        @JsonPropertyDescription(REGISTRY_PUSH_USERNAME_DESCRIPTION)
        String pushUsername = ""
        @JsonPropertyDescription(REGISTRY_PUSH_PASSWORD_DESCRIPTION)
        String pushPassword = ""

        HelmConfig helm = new HelmConfig()
    }

     @JsonClassDescription(JENKINS_DESCRIPTION)
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

         HelmConfig helm = new HelmConfig()
    }
    @JsonClassDescription(SCMM_DESCRIPTION)
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

         HelmConfig helm = new HelmConfig()
     }

    @JsonClassDescription(APPLICATION_DESCRIPTION)
    static class ApplicationSchema {
        // group remote
        @JsonPropertyDescription(REMOTE_DESCRIPTION)
        boolean remote = false
        @JsonPropertyDescription(INSECURE_DESCRIPTION)
        boolean insecure = false

        String localHelmChartFolder = ""

        // args group configuration
        @JsonPropertyDescription(USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(PASSWORD_DESCRIPTION)
        String password = ""
        @JsonPropertyDescription(PIPE_YES_DESCRIPTION)
        boolean yes = false
        // boolean runningInsideK8s = ""
        // String clusterBindAddress = ""
        @JsonPropertyDescription(NAME_PREFIX_DESCRIPTION)
        String namePrefix = ""
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
    }

     static class FeaturesSchema {
         @JsonPropertyDescription(ARGOCD_DESCRIPTION)
         ArgoCDSchema argocd
         @JsonPropertyDescription(MAILHOG_DESCRIPTION)
         MailSchema mail
         @JsonPropertyDescription(MONITORING_DESCRIPTION)
         MonitoringSchema monitoring

         SecretsSchema secrets
         @JsonPropertyDescription(INGRESS_NGINX_DESCRIPTION)
         IngressNginxSchema ingressNginx

         ExampleAppsSchema exampleApps
    }

    @JsonClassDescription(ARGOCD_DESCRIPTION)
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

    @JsonClassDescription(MAILHOG_DESCRIPTION)
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
         String url = ""

         MailHelmSchema helm = new MailHelmSchema()
         static class MailHelmSchema extends HelmConfig {
             String image = ""
         }
     }

    @JsonClassDescription(MONITORING_DESCRIPTION)
     static class MonitoringSchema {
         @JsonPropertyDescription(MONITORING_ENABLE_DESCRIPTION)
         boolean active = true
         @JsonPropertyDescription(GRAFANA_URL_DESCRIPTION)
         String grafanaUrl = ""
         @JsonPropertyDescription(GRAFANA_EMAIL_FROM_DESCRIPTION)
         String grafanaEmailFrom = ""
         @JsonPropertyDescription(GRAFANA_EMAIL_TO_DESCRIPTION)
         String grafanaEmailTo = ""

         MonitoringHelmSchema helm = new MonitoringHelmSchema()
         static class MonitoringHelmSchema extends HelmConfig {
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
         ESOSchema externalSecrets
         @JsonPropertyDescription(VAULT_ENABLE_DESCRIPTION)
         VaultSchema vault

         @JsonClassDescription(ESO_DESCRIPTION)
         static class ESOSchema {
             ESOHelmSchema helm = new ESOHelmSchema()
             static class ESOHelmSchema extends HelmConfig {
                 @JsonPropertyDescription(EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
                 String image = ""
                 @JsonPropertyDescription(EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
                 String certControllerImage = ""
                 @JsonPropertyDescription(EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
                 String webhookImage = ""
             }
         }

         @JsonClassDescription(VAULT_DESCRIPTION)
         static class VaultSchema {
             String mode = ""
             @JsonPropertyDescription(VAULT_URL_DESCRIPTION)
             String url = ""

             VaultHelmSchema helm = new VaultHelmSchema()
             static class VaultHelmSchema extends HelmConfig {
                 @JsonPropertyDescription(VAULT_IMAGE_DESCRIPTION)
                 String image = ""
            }
        }
    }

    @JsonClassDescription(INGRESS_NGINX_DESCRIPTION)
    static class IngressNginxSchema {
        @JsonPropertyDescription(INGRESS_NGINX_ENABLE_DESCRIPTION)
        Boolean active = false

        IngressNginxHelmSchema helm = new IngressNginxHelmSchema()
        static class IngressNginxHelmSchema extends HelmConfig {
            Map<String, Object> values
        }
    }

    static class ExampleAppsSchema {
        BaseDomainSchema petclinic
        BaseDomainSchema nginx

        static class BaseDomainSchema {
            @JsonPropertyDescription(PETCLINIC_BASE_DOMAIN_DESCRIPTION)
            String baseDomain = ""
        }
    }
}
