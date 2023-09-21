//file:noinspection unused
package com.cloudogu.gitops.config.schema

/**
 * The schema for the configuration file.
 * It is used to validate the passed yaml file.
 *
 * Currently only contains variables that are used in groovy only.
 *
 * When changing values make sure to modify GitOpsPlaygroundCli and ApplicationConfigurator as well
 * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli
 * @see com.cloudogu.gitops.config.ApplicationConfigurator
 */
class Schema {
//    private RegistrySchema registry // used in bash
//    private JenkinsSchema jenkins // used in bash
//    private ScmmSchema scmm // used in bash
//    private ApplicationSchema application // used in bash
    private ImagesSchema images
    private FeaturesSchema features

    private static class RegistrySchema {
        private String url = ""
        private String path = ""
        private String username = ""
        private String password = ""
        private int internalPort
    }

    private static class JenkinsSchema {
        private String url = ""
        private String username = ""
        private String password = ""
        private String metricsUsername = ""
        private String metricsPassword = ""
    }

    private static class ScmmSchema {
        private String url = ""
        private String username = ""
        private String password = ""
    }

    private static class ApplicationSchema {
        private boolean remote = false
        private boolean insecure = false
        private boolean skipHelmUpdate = false
        private boolean yes = false
        private String username = ""
        private String password = ""
        private String namePrefix = ""
    }

    private static class ImagesSchema {
        private String kubectl = ""
        private String helm = ""
        private String kubeval = ""
        private String helmKubeval = ""
        private String yamllint = ""
        private String nginx = ""
    }

    private static class FeaturesSchema {
        private ArgoCDSchema argocd
        private MailSchema mail
        private MonitoringSchema monitoring
        private SecretsSchema secrets
        private ExampleAppsSchema exampleApps
    }

    private static class ArgoCDSchema {
//        private boolean active = true // used in bash
        private String url = ""
    }

    private static class MailSchema {
        private String url = ""
    }

    private static class MonitoringSchema {
        private boolean active = true
        private String grafanaUrl = ""
        private MonitoringHelmSchema helm

        private static class MonitoringHelmSchema {
            private String grafanaImage = ""
            private String grafanaSidecarImage = ""
            private String prometheusImage = ""
            private String prometheusOperatorImage = ""
            private String prometheusConfigReloaderImage = ""
        }
    }

    private static class SecretsSchema {
        private VaultSchema vault
        private ESOSchema externalSecrets

        private static class VaultSchema {
            private String mode = ""
            private String url = ""
            private VaultHelmSchema helm

            private static class VaultHelmSchema {
                private String image = ""
            }
        }

        private static class ESOSchema {
            private ESOHelmSchema helm

            private static class ESOHelmSchema {
                private String image = ""
                private String certControllerImage = ""
                private String webhookImage = ""
            }
        }
    }

    private static class ExampleAppsSchema {
        private BaseDomainSchema petclinic
        private BaseDomainSchema nginx

        private static class BaseDomainSchema {
            private String baseDomain = ""
        }
    }
}
