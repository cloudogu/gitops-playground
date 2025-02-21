package com.cloudogu.gitops.config


import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j

@Slf4j
class ApplicationConfigurator {

    private NetworkingUtils networkingUtils
    private FileSystemUtils fileSystemUtils

    ApplicationConfigurator(NetworkingUtils networkingUtils = new NetworkingUtils(),
                            FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.networkingUtils = networkingUtils
        this.fileSystemUtils = fileSystemUtils
    }

    /**
     * Sets dynamic fields
     */
    Config initConfig(Config newConfig) {

        addAdditionalApplicationConfig(newConfig)

        addScmmConfig(newConfig)
        addJenkinsConfig(newConfig)

        addNamePrefix(newConfig)

        addRegistryConfig(newConfig)

        addFeatureConfig(newConfig)

        validateEnvConfigForArgoCDOperator(newConfig)

        evaluateBaseUrl(newConfig)

        setResourceInclusionsCluster(newConfig)

        return newConfig
    }

    private void addFeatureConfig(Config newConfig) {
        if (newConfig.features.secrets.vault.mode)
            newConfig.features.secrets.active = true
        if (newConfig.features.mail.smtpAddress || newConfig.features.mail.mailhog)
            newConfig.features.mail.active = true
        if (newConfig.features.mail.smtpAddress && newConfig.features.mail.mailhog) {
            newConfig.features.mail.mailhog = false
            log.warn("Enabled both external Mailserver and MailHog! Implicitly deactivating MailHog")
        }
        if (newConfig.features.ingressNginx.active && !newConfig.application.baseUrl) {
            log.warn("Ingress-controller is activated without baseUrl parameter. Services will not be accessible by hostnames. To avoid this use baseUrl with ingress. ")
        }
    }

    private void addNamePrefix(Config newConfig) {
        String namePrefix = newConfig.application.namePrefix
        if (namePrefix) {
            if (!namePrefix.endsWith('-')) {
                newConfig.application.namePrefix = "${namePrefix}-"
            }
            newConfig.application.namePrefixForEnvVars = "${(newConfig.application.namePrefix as String).toUpperCase().replace('-', '_')}"
        }
    }

    private void addRegistryConfig(Config newConfig) {
        if (newConfig.registry.proxyUrl) {
            newConfig.registry.twoRegistries = true
            if (!newConfig.registry.proxyUsername || !newConfig.registry.proxyPassword) {
                throw new RuntimeException("Proxy URL needs to be used with proxy-username and proxy-password")
            }
        }

        if (newConfig.registry.url) {
            newConfig.registry.internal = false
        } else {
            /* Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on 
               docker push in the example application's Jenkins Jobs.
               Both setting up HTTPS or allowing insecure registry via daemon.json makes the playground difficult to use.
               So, always use localhost.
               Allow overriding the port, in case multiple playground instance run on a single host in different 
               k3d clusters. */
            newConfig.registry.url = "localhost:${newConfig.registry.internalPort}"
        }

        if (newConfig.registry.createImagePullSecrets) {
            String username = newConfig.registry.readOnlyUsername ?: newConfig.registry.username
            String password = newConfig.registry.readOnlyPassword ?: newConfig.registry.password
            if (!username || !password) {
                throw new RuntimeException("createImagePullSecrets needs to be used with either registry username and password or the readOnly variants")
            }
        }
    }

    private void addAdditionalApplicationConfig(Config newConfig) {
        if (System.getenv("KUBERNETES_SERVICE_HOST")) {
            log.debug("installation is running in kubernetes.")
            newConfig.application.runningInsideK8s = true
        }
    }

    private void addScmmConfig(Config newConfig) {
        log.debug("Adding additional config for SCM-Manager")

        newConfig.scmm.gitOpsUsername = "${newConfig.application.namePrefix}gitops"

        if (newConfig.scmm.url) {
            log.debug("Setting external scmm config")
            newConfig.scmm.internal = false
            newConfig.scmm.urlForJenkins = newConfig.scmm.url
        } else if (newConfig.application.runningInsideK8s) {
            log.debug("Setting scmm url to k8s service, since installation is running inside k8s")
            newConfig.scmm.url = networkingUtils.createUrl("scmm-scm-manager.default.svc.cluster.local", "80", "/scm")
        } else {
            log.debug("Setting internal configs for local single node cluster with internal scmm")
            def port = fileSystemUtils.getLineFromFile(fileSystemUtils.getRootDir() + "/scm-manager/values.ftl.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            String clusterBindAddress = networkingUtils.findClusterBindAddress()
            newConfig.scmm.url = networkingUtils.createUrl(clusterBindAddress, port, "/scm")
        }

        String scmmUrl = newConfig.scmm.url
        log.debug("Getting host and protocol from scmmUrl: " + scmmUrl)
        newConfig.scmm.host = networkingUtils.getHost(scmmUrl)
        newConfig.scmm.protocol = networkingUtils.getProtocol(scmmUrl)

        // We probably could get rid of some of the complexity by refactoring url, host and ingress into a single var
        if (newConfig.application.baseUrl) {
            newConfig.scmm.ingress = new URL(injectSubdomain('scmm',
                    newConfig.application.baseUrl as String, newConfig.application.urlSeparatorHyphen as Boolean)).host
        }
    }

    private void addJenkinsConfig(Config newConfig) {
        log.debug("Adding additional config for Jenkins")
        if (newConfig.jenkins.url) {
            log.debug("Setting external jenkins config")
            newConfig.jenkins.internal = false
            newConfig.jenkins.urlForScmm = newConfig.jenkins.url
        } else if (newConfig.application.runningInsideK8s) {
            log.debug("Setting jenkins url to k8s service, since installation is running inside k8s")
            newConfig.jenkins.url = networkingUtils.createUrl("jenkins.default.svc.cluster.local", "80")
        } else {
            log.debug("Setting jenkins configs for local single node cluster with internal jenkins")
            def port = fileSystemUtils.getLineFromFile(fileSystemUtils.getRootDir() + "/jenkins/values.ftl.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            String clusterBindAddress = networkingUtils.findClusterBindAddress()
            newConfig.jenkins.url = networkingUtils.createUrl(clusterBindAddress, port)
        }

        if (newConfig.application.baseUrl) {
            newConfig.jenkins.ingress = new URL(injectSubdomain('jenkins',
                    newConfig.application.baseUrl, newConfig.application.urlSeparatorHyphen)).host
        }
    }

    private void evaluateBaseUrl(Config newConfig) {
        String baseUrl = newConfig.application.baseUrl
        if (!baseUrl) {
            return
        }
        log.debug("Base URL set, adapting to individual tools")
        def argocd = newConfig.features.argocd
        def mail = newConfig.features.mail
        def monitoring = newConfig.features.monitoring
        def vault = newConfig.features.secrets.vault
        boolean urlSeparatorHyphen = newConfig.application.urlSeparatorHyphen

        if (argocd.active && !argocd.url) {
            argocd.url = injectSubdomain('argocd', baseUrl, urlSeparatorHyphen)
            log.debug("Setting ArgoCD URL ${argocd.url}")
        }
        if (mail.mailhog && !mail.mailhogUrl) {
            mail.mailhogUrl = injectSubdomain('mailhog', baseUrl, urlSeparatorHyphen)
            log.debug("Setting Mail URL ${mail.mailhogUrl}")
        }
        if (monitoring.active && !monitoring.grafanaUrl) {
            monitoring.grafanaUrl = injectSubdomain('grafana', baseUrl, urlSeparatorHyphen)
            log.debug("Setting Monitoring URL ${monitoring.grafanaUrl}")
        }
        if (newConfig.features.secrets.active && !vault.url) {
            vault.url = injectSubdomain('vault', baseUrl, urlSeparatorHyphen)
            log.debug("Setting Vault URL ${vault.url}")
        }

        if (!newConfig.features.exampleApps.petclinic.baseDomain) {
            // This param only requires the host / domain
            newConfig.features.exampleApps.petclinic.baseDomain =
                    new URL(injectSubdomain('petclinic', baseUrl, urlSeparatorHyphen)).host
            log.debug("Setting Petclinic URL ${newConfig.features.exampleApps.petclinic.baseDomain}")
        }
        if (!newConfig.features.exampleApps.nginx.baseDomain) {
            // This param only requires the host / domain
            newConfig.features.exampleApps.nginx.baseDomain =
                    new URL(injectSubdomain('nginx', baseUrl, urlSeparatorHyphen)).host
            log.debug("Setting Nginx URL ${newConfig.features.exampleApps.nginx.baseDomain}")
        }
    }

    /**
     *
     * @param subdomain , e.g. argocd
     * @param baseUrl e.g. http://localhost:8080
     * @param urlSeparatorHyphen
     * @return e.g. http://argocd.localhost:8080
     */
    private String injectSubdomain(String subdomain, String baseUrl, boolean urlSeparatorHyphen) {
        URL url = new URL(baseUrl)
        String newUrl

        if (urlSeparatorHyphen) {
            newUrl = url.getProtocol() + "://" + subdomain + "-" + url.getHost()
        } else {
            newUrl = url.getProtocol() + "://" + subdomain + "." + url.getHost()
        }
        if (url.getPort() != -1) {
            newUrl += ":" + url.getPort()
        }
        newUrl += url.getPath()
        return newUrl
    }

    void validateConfig(Config configToSet) {
        if (configToSet.scmm.url && !configToSet.jenkins.url ||
                !configToSet.scmm.url && configToSet.jenkins.url) {
            throw new RuntimeException('When setting jenkins URL, scmm URL must also be set and the other way round')
        }
        if (configToSet.application.mirrorRepos && !configToSet.application.localHelmChartFolder) {
            // This should only happen when run outside the image, i.e. during development
            throw new RuntimeException("Missing config for localHelmChartFolder.\n" +
                    "Either run inside the official container image or setting env var " +
                    "LOCAL_HELM_CHART_FOLDER='charts' after running 'scripts/downloadHelmCharts.sh' from the repo")
        }
    }

    // Validate that the env list has proper maps with 'name' and 'value'
    private static void validateEnvConfigForArgoCDOperator(Config configToSet) {
        // Exit early if not in operator mode or if env list is empty
        if (!configToSet.features.argocd.operator || !configToSet.features.argocd.env) {
            log.debug("Skipping features.argocd.env validation: operator mode is disabled or env list is empty.")
            return
        }

        List<Map> env = configToSet.features.argocd.env as List<Map<String, String>>

        log.info("Validating env list in features.argocd.env with {} entries.", env.size())

        env.each { map ->
            if (!(map instanceof Map) || !map.containsKey('name') || !map.containsKey('value')) {
                throw new IllegalArgumentException("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: $map")
            }
        }

        log.info("Env list validation for features.argocd.env completed successfully.")
    }

    private void setResourceInclusionsCluster(Config configToSet) {
        // Return early if NOT deploying via operator
        if (configToSet.features.argocd.operator == false) {
            log.debug("ArgoCD operator is not enabled. Skipping features.argocd.resourceInclusionsCluster setup.")
            return
        }
        log.info("Starting setup of features.argocd.resourceInclusionsCluster for ArgoCD Operator")

        if (!isUrlSetAndValid(configToSet)) {
            // If features.argocd.resourceInclusionsClus<ter is not set, attempt to determine it via Kubernetes ENVs
            buildAndValidateURLFromEnvironment(configToSet)
        }


    }

    boolean isUrlSetAndValid(Config config) {
        String url = config.features.argocd.resourceInclusionsCluster

        // If features.argocd.resourceInclusionsCluster is set in the config, validate if it's a proper URL and return
        if (url) {
            try {
                // Attempt to create a URL object to validate it
                log.debug("Validating user-provided features.argocd.resourceInclusionsCluster URL: {}", url)
                new URL(url)
                log.info("Found valid URL in features.argocd.resourceInclusionsCluster: {}", url)
                return true
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid URL for 'features.argocd.resourceInclusionsCluster': $url. ", e)
            }
        }
    }
    /**
     * Build
     */
    void buildAndValidateURLFromEnvironment(Config config) {
        log.debug("Attempting to set features.argocd.resourceInclusionsCluster via Kubernetes ENV variables.")

        String host = System.getenv("KUBERNETES_SERVICE_HOST")
        String port = System.getenv("KUBERNETES_SERVICE_PORT")

        String errorMessage = "Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. " +
                "Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly. " +
                "Alternatively, try setting 'features.argocd.resourceInclusionsCluster' in the config to manually override."

        if (!host || !port) {
            throw new RuntimeException(errorMessage)
        }

        String internalClusterUrl = "https://${host}:${port}"
        log.debug("Constructed internal Kubernetes API Server URL: {}", internalClusterUrl)

        // Validate the constructed URL
        try {
            new URL(internalClusterUrl)
            config.features.argocd.resourceInclusionsCluster = internalClusterUrl
            log.info("Successfully set features.argocd.resourceInclusionsCluster via Kubernetes ENV to: {}", internalClusterUrl)
        } catch (MalformedURLException e) {
            throw new RuntimeException(errorMessage, e)
        }
    }
}
