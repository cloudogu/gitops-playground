//file:noinspection unused
package com.cloudogu.gitops.config.schema 
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

    static class HelmConfig {
        // common values
        String chart = ""
        String repoURL = ""
        String version = ""
    }

     static class RegistrySchema {
         // boolean internal = true
         // boolean twoRegistries = false
         int internalPort
         String url = ""
         String path = ""
         String username = ""
         String password = ""
         // Alternative: Use different registries, e.g. in air-gapped envs 
         // "Pull" registry for 3rd party images, e.g. base images
         String pullUrl = ""
         String pullUsername = ""
         String pullPassword = ""
         // "Push" registry for writing application specific images
         String pushUrl = ""
         String pushPath = ""
         String pushUsername = ""
         String pushPassword = ""
         HelmConfig helm = new HelmConfig()
     }

     static class JenkinsSchema {
         // boolean internal = true
         String url = ""
         String username = ""
         String password = ""
         // String urlForScmm = ""
         String metricsUsername = ""
         String metricsPassword = ""
         HelmConfig helm = new HelmConfig()
         String mavenCentralMirror = ""
    }

     static class ScmmSchema {
         // boolean internal = true
         String url = ""
         String username = ""
         String password = ""
         // String gitOpsUsername = ""
         // String urlForJenkins = ""
         // String host = ""
         // String protocol = ""
         // String ingress = ""
         HelmConfig helm = new HelmConfig()
     }

     static class ApplicationSchema {
         boolean remote = false
         boolean mirrorRepos = false
         String localHelmChartFolder = ""
         boolean insecure = false
         String username = ""
         String password = ""
         boolean yes = false
         // boolean runningInsideK8s = ""
         // String clusterBindAddress = ""
         String namePrefix = ""
         boolean podResources = false
         // String namePrefixForEnvVars = ""
         String baseUrl = ""
         String gitName = ""
         String gitEmail = ""
         boolean urlSeparatorHyphen = false
    }

     static class ImagesSchema {
         String kubectl = ""
         String helm = ""
         String kubeval = ""
         String helmKubeval = ""
         String yamllint = ""
         String nginx = ""
         String petclinic = ""
    }

     static class FeaturesSchema {
         ArgoCDSchema argocd
         MailSchema mail
         MonitoringSchema monitoring
         SecretsSchema secrets
         IngressNginxSchema ingressNginx
         ExampleAppsSchema exampleApps
    }

     static class ArgoCDSchema {
         boolean active = false
         String url = ""
         String emailFrom = ""
         String emailToUser = ""
         String emailToAdmin = ""
    }

     static class MailSchema {
         // boolean active = false
         boolean mailhog = false
         String mailhogUrl = ""
         String smtpAddress = ""
         Integer smtpPort = null
         String smtpUser = ""
         String smtpPassword = ""
         String url = ""

         MailHelmSchema helm = new MailHelmSchema()
         static class MailHelmSchema extends HelmConfig {
             String image = ""
         }
     }

     static class MonitoringSchema {
         boolean active = true
         String grafanaUrl = ""
         String grafanaEmailFrom = ""
         String grafanaEmailTo = ""

         MonitoringHelmSchema helm = new MonitoringHelmSchema()
         static class MonitoringHelmSchema extends HelmConfig {
             String grafanaImage = ""
             String grafanaSidecarImage = ""
             String prometheusImage = ""
             String prometheusOperatorImage = ""
             String prometheusConfigReloaderImage = ""
        }
    }

     static class SecretsSchema {
         // boolean active = false
         ESOSchema externalSecrets
         VaultSchema vault

         static class ESOSchema {
             ESOHelmSchema helm = new ESOHelmSchema()
             static class ESOHelmSchema extends HelmConfig {
                 String image = ""
                 String certControllerImage = ""
                 String webhookImage = ""
             }
         }

         static class VaultSchema {
             String mode = ""
             String url = ""

             VaultHelmSchema helm = new VaultHelmSchema()
             static class VaultHelmSchema extends HelmConfig {
                 String image = ""
            }
        }
    }

    static class IngressNginxSchema {
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
            String baseDomain = ""
        }
    }
}
