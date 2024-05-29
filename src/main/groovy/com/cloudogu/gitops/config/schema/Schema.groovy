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

     static class RegistrySchema {
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
    }

     static class JenkinsSchema {
         String url = ""
         String username = ""
         String password = ""
         String metricsUsername = ""
         String metricsPassword = ""
         String mavenCentralMirror = ""
    }

     static class ScmmSchema {
         String url = ""
         String username = ""
         String password = ""
    }

     static class ApplicationSchema {
         boolean remote = false
         boolean mirrorRepos = false
         boolean insecure = false
         boolean yes = false
         String username = ""
         String password = ""
         String namePrefix = ""
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
         ExampleAppsSchema exampleApps
         IngressNginxSchema ingressNginx
    }

     static class ArgoCDSchema {
//         boolean active = true // used in bash
         String url = ""
         String emailFrom = ""
         String emailToUser = ""
         String emailToAdmin = ""
    }

     static class MailSchema {
         boolean mailhog = false
         String mailhogUrl = ""
         String smtpAddress = ""
         Integer smtpPort = null
         String smtpUser = ""
         String smtpPassword = ""
    }

     static class MonitoringSchema {
         boolean active = true
         String grafanaUrl = ""
         MonitoringHelmSchema helm
         String grafanaEmailFrom = ""
         String grafanaEmailTo = ""

         static class MonitoringHelmSchema {
             String grafanaImage = ""
             String grafanaSidecarImage = ""
             String prometheusImage = ""
             String prometheusOperatorImage = ""
             String prometheusConfigReloaderImage = ""
        }
    }

     static class SecretsSchema {
         VaultSchema vault
         ESOSchema externalSecrets

         static class VaultSchema {
             String mode = ""
             String url = ""
             VaultHelmSchema helm

             static class VaultHelmSchema {
                 String image = ""
            }
        }

         static class ESOSchema {
             ESOHelmSchema helm

             static class ESOHelmSchema {
                 String image = ""
                 String certControllerImage = ""
                 String webhookImage = ""
            }
        }
    }

     static class ExampleAppsSchema {
         BaseDomainSchema petclinic
         BaseDomainSchema nginx

         static class BaseDomainSchema {
             String baseDomain = ""
        }
    }

    static class IngressNginxSchema {
        Boolean active = false
        IngressNginxHelmSchema helm

        static class IngressNginxHelmSchema {
            Map<String, Object> values
        }
    }
}
