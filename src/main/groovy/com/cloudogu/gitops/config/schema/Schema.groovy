//file:noinspection unused
package com.cloudogu.gitops.config.schema

import com.cloudogu.gitops.config.DescriptionConstants
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

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

    @JsonClassDescription(DescriptionConstants.HELM_DESCRIPTION)
    static class HelmConfig {
        // common values
        @JsonPropertyDescription(DescriptionConstants.HELM_CHART_DESCRIPTION)
        String chart = ""
        @JsonPropertyDescription(DescriptionConstants.HELM_REPO_URL_DESCRIPTION)
        String repoURL = ""
        @JsonPropertyDescription(DescriptionConstants.HELM_VERSION_DESCRIPTION)
        String version = ""
    }

    @JsonClassDescription(DescriptionConstants.REGISTRY_DESCRIPTION)
    static class RegistrySchema {
        // boolean internal = true
        // boolean twoRegistries = false
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_INTERNAL_PORT_DESCRIPTION)
        int internalPort
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_URL_DESCRIPTION)
        String url = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PATH_DESCRIPTION)
        String path = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PASSWORD_DESCRIPTION)
        String password = ""
        // Alternative: Use different registries, e.g. in air-gapped envs
        // "Pull" registry for 3rd party images, e.g. base images
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PULL_URL_DESCRIPTION)
        String pullUrl = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PULL_USERNAME_DESCRIPTION)
        String pullUsername = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PULL_PASSWORD_DESCRIPTION)
        String pullPassword = ""
        // "Push" registry for writing application specific images
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PUSH_URL_DESCRIPTION)
        String pushUrl = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PUSH_PATH_DESCRIPTION)
        String pushPath = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PUSH_USERNAME_DESCRIPTION)
        String pushUsername = ""
        @JsonPropertyDescription(DescriptionConstants.REGISTRY_PUSH_PASSWORD_DESCRIPTION)
        String pushPassword = ""

        HelmConfig helm = new HelmConfig()
    }

     @JsonClassDescription(DescriptionConstants.JENKINS_DESCRIPTION)
     static class JenkinsSchema {
         // boolean internal = true
         @JsonPropertyDescription(DescriptionConstants.JENKINS_URL_DESCRIPTION)
         String url = ""
         @JsonPropertyDescription(DescriptionConstants.JENKINS_USERNAME_DESCRIPTION)
         String username = ""
         @JsonPropertyDescription(DescriptionConstants.JENKINS_PASSWORD_DESCRIPTION)
         String password = ""
         // String urlForScmm = ""
         @JsonPropertyDescription(DescriptionConstants.JENKINS_METRICS_USERNAME_DESCRIPTION)
         String metricsUsername = ""
         @JsonPropertyDescription(DescriptionConstants.JENKINS_METRICS_PASSWORD_DESCRIPTION)
         String metricsPassword = ""
         @JsonPropertyDescription(DescriptionConstants.MAVEN_CENTRAL_MIRROR_DESCRIPTION)
         String mavenCentralMirror = ""

         HelmConfig helm = new HelmConfig()
    }
    @JsonClassDescription(DescriptionConstants.SCMM_DESCRIPTION)
     static class ScmmSchema {
         // boolean internal = true
         @JsonPropertyDescription(DescriptionConstants.SCMM_URL_DESCRIPTION)
         String url = ""
         @JsonPropertyDescription(DescriptionConstants.SCMM_USERNAME_DESCRIPTION)
         String username = ""
         @JsonPropertyDescription(DescriptionConstants.SCMM_PASSWORD_DESCRIPTION)
         String password = ""

         // String gitOpsUsername = ""
         // String urlForJenkins = ""
         // String host = ""
         // String protocol = ""
         // String ingress = ""

         HelmConfig helm = new HelmConfig()
     }

    @JsonClassDescription(DescriptionConstants.APPLICATION_DESCRIPTION)
    static class ApplicationSchema {
        // group remote
        @JsonPropertyDescription(DescriptionConstants.REMOTE_DESCRIPTION)
        boolean remote = false
        @JsonPropertyDescription(DescriptionConstants.INSECURE_DESCRIPTION)
        boolean insecure = false

        String localHelmChartFolder = ""

        // args group configuration
        @JsonPropertyDescription(DescriptionConstants.USERNAME_DESCRIPTION)
        String username = ""
        @JsonPropertyDescription(DescriptionConstants.PASSWORD_DESCRIPTION)
        String password = ""
        @JsonPropertyDescription(DescriptionConstants.PIPE_YES_DESCRIPTION)
        boolean yes = false
        // boolean runningInsideK8s = ""
        // String clusterBindAddress = ""
        @JsonPropertyDescription(DescriptionConstants.NAME_PREFIX_DESCRIPTION)
        String namePrefix = ""
        @JsonPropertyDescription(DescriptionConstants.POD_RESOURCES_DESCRIPTION)
        boolean podResources = false

        // String namePrefixForEnvVars = ""
        @JsonPropertyDescription(DescriptionConstants.GIT_NAME_DESCRIPTION)
        String gitName = ''
        @JsonPropertyDescription(DescriptionConstants.GIT_EMAIL_DESCRIPTION)
        String gitEmail = ''

        @JsonPropertyDescription(DescriptionConstants.BASE_URL_DESCRIPTION)
        String baseUrl = ""
        @JsonPropertyDescription(DescriptionConstants.URL_SEPARATOR_HYPHEN_DESCRIPTION)
        boolean urlSeparatorHyphen = false
        @JsonPropertyDescription(DescriptionConstants.MIRROR_REPOS_DESCRIPTION)
        boolean mirrorRepos = false
        @JsonPropertyDescription(DescriptionConstants.SKIP_CRDS_DESCRIPTION)
        boolean skipCrds = false
    }

    static class ImagesSchema {
        @JsonPropertyDescription(DescriptionConstants.KUBECTL_IMAGE_DESCRIPTION)
         String kubectl = ""
        @JsonPropertyDescription(DescriptionConstants.HELM_IMAGE_DESCRIPTION)
         String helm = ""
        @JsonPropertyDescription(DescriptionConstants.KUBEVAL_IMAGE_DESCRIPTION)
         String kubeval = ""
        @JsonPropertyDescription(DescriptionConstants.HELMKUBEVAL_IMAGE_DESCRIPTION)
         String helmKubeval = ""
        @JsonPropertyDescription(DescriptionConstants.YAMLLINT_IMAGE_DESCRIPTION)
         String yamllint = ""
        @JsonPropertyDescription(DescriptionConstants.NGINX_IMAGE_DESCRIPTION)
         String nginx = ""
        @JsonPropertyDescription(DescriptionConstants.PETCLINIC_IMAGE_DESCRIPTION)
         String petclinic = ""
    }

     static class FeaturesSchema {
         @JsonPropertyDescription(DescriptionConstants.ARGOCD_DESCRIPTION)
         ArgoCDSchema argocd
         @JsonPropertyDescription(DescriptionConstants.MAILHOG_DESCRIPTION)
         MailSchema mail
         @JsonPropertyDescription(DescriptionConstants.MONITORING_DESCRIPTION)
         MonitoringSchema monitoring

         SecretsSchema secrets
         @JsonPropertyDescription(DescriptionConstants.INGRESS_NGINX_DESCRIPTION)
         IngressNginxSchema ingressNginx

         ExampleAppsSchema exampleApps
    }

    @JsonClassDescription(DescriptionConstants.ARGOCD_DESCRIPTION)
     static class ArgoCDSchema {
         @JsonPropertyDescription(DescriptionConstants.ARGOCD_ENABLE_DESCRIPTION)
         boolean active = false
         @JsonPropertyDescription(DescriptionConstants.ARGOCD_URL_DESCRIPTION)
         String url = ""
         @JsonPropertyDescription(DescriptionConstants.ARGOCD_EMAIL_FROM_DESCRIPTION)
         String emailFrom = ""
         @JsonPropertyDescription(DescriptionConstants.ARGOCD_EMAIL_TO_USER_DESCRIPTION)
         String emailToUser = ""
         @JsonPropertyDescription(DescriptionConstants.ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
         String emailToAdmin = ""
    }

    @JsonClassDescription(DescriptionConstants.MAILHOG_DESCRIPTION)
     static class MailSchema {
         // boolean active = false
         @JsonPropertyDescription(DescriptionConstants.MAILHOG_ENABLE_DESCRIPTION)
         boolean mailhog = false
         @JsonPropertyDescription(DescriptionConstants.MAILHOG_URL_DESCRIPTION)
         String mailhogUrl = ""
         @JsonPropertyDescription(DescriptionConstants.SMTP_ADDRESS_DESCRIPTION)
         String smtpAddress = ""
         @JsonPropertyDescription(DescriptionConstants.SMTP_PORT_DESCRIPTION)
         Integer smtpPort = null
         @JsonPropertyDescription(DescriptionConstants.SMTP_USER_DESCRIPTION)
         String smtpUser = ""
         @JsonPropertyDescription(DescriptionConstants.SMTP_PASSWORD_DESCRIPTION)
         String smtpPassword = ""
         String url = ""

         MailHelmSchema helm = new MailHelmSchema()
         static class MailHelmSchema extends HelmConfig {
             String image = ""
         }
     }

    @JsonClassDescription(DescriptionConstants.MONITORING_DESCRIPTION)
     static class MonitoringSchema {
         @JsonPropertyDescription(DescriptionConstants.MONITORING_ENABLE_DESCRIPTION)
         boolean active = true
         @JsonPropertyDescription(DescriptionConstants.GRAFANA_URL_DESCRIPTION)
         String grafanaUrl = ""
         @JsonPropertyDescription(DescriptionConstants.GRAFANA_EMAIL_FROM_DESCRIPTION)
         String grafanaEmailFrom = ""
         @JsonPropertyDescription(DescriptionConstants.GRAFANA_EMAIL_TO_DESCRIPTION)
         String grafanaEmailTo = ""

         MonitoringHelmSchema helm = new MonitoringHelmSchema()
         static class MonitoringHelmSchema extends HelmConfig {
             @JsonPropertyDescription(DescriptionConstants.GRAFANA_IMAGE_DESCRIPTION)
             String grafanaImage = ""
             @JsonPropertyDescription(DescriptionConstants.GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
             String grafanaSidecarImage = ""
             @JsonPropertyDescription(DescriptionConstants.PROMETHEUS_IMAGE_DESCRIPTION)
             String prometheusImage = ""
             @JsonPropertyDescription(DescriptionConstants.PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
             String prometheusOperatorImage = ""
             @JsonPropertyDescription(DescriptionConstants.PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
             String prometheusConfigReloaderImage = ""
        }
    }

     static class SecretsSchema {
         // boolean active = false
         ESOSchema externalSecrets
         @JsonPropertyDescription(DescriptionConstants.VAULT_ENABLE_DESCRIPTION)
         VaultSchema vault

         @JsonClassDescription(DescriptionConstants.ESO_DESCRIPTION)
         static class ESOSchema {
             ESOHelmSchema helm = new ESOHelmSchema()
             static class ESOHelmSchema extends HelmConfig {
                 @JsonPropertyDescription(DescriptionConstants.EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
                 String image = ""
                 @JsonPropertyDescription(DescriptionConstants.EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
                 String certControllerImage = ""
                 @JsonPropertyDescription(DescriptionConstants.EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
                 String webhookImage = ""
             }
         }

         @JsonClassDescription(DescriptionConstants.VAULT_DESCRIPTION)
         static class VaultSchema {
             String mode = ""
             @JsonPropertyDescription(DescriptionConstants.VAULT_URL_DESCRIPTION)
             String url = ""

             VaultHelmSchema helm = new VaultHelmSchema()
             static class VaultHelmSchema extends HelmConfig {
                 @JsonPropertyDescription(DescriptionConstants.VAULT_IMAGE_DESCRIPTION)
                 String image = ""
            }
        }
    }

    @JsonClassDescription(DescriptionConstants.INGRESS_NGINX_DESCRIPTION)
    static class IngressNginxSchema {
        @JsonPropertyDescription(DescriptionConstants.INGRESS_NGINX_ENABLE_DESCRIPTION)
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
            @JsonPropertyDescription(DescriptionConstants.PETCLINIC_BASE_DOMAIN_DESCRIPTION)
            String baseDomain = ""
        }
    }
}
