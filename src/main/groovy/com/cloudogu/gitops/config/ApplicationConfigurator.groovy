package com.cloudogu.gitops.config

import com.cloudogu.gitops.git.config.ScmTenantSchema
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j

import static com.cloudogu.gitops.config.Config.ContentRepoType
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema

@Slf4j
class ApplicationConfigurator {

    private FileSystemUtils fileSystemUtils

    ApplicationConfigurator(FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.fileSystemUtils = fileSystemUtils
    }

    /**
     * Sets dynamic fields and validates params
     */
    Config initConfig(Config newConfig) {

        addAdditionalApplicationConfig(newConfig)
        addNamePrefix(newConfig)

        addScmConfig(newConfig)

        addRegistryConfig(newConfig)

        addJenkinsConfig(newConfig)

        addFeatureConfig(newConfig)

        validateEnvConfigForArgoCDOperator(newConfig)

        evaluateBaseUrl(newConfig)

        setResourceInclusionsCluster(newConfig)

        setMultiTenantModeConfig(newConfig)

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
        if (newConfig.content.examples) {
            if (!newConfig.registry.active) {
                throw new RuntimeException("content.examples requires either registry.active or registry.url")
            }
            String prefix = newConfig.application.namePrefix
            newConfig.content.namespaces += [prefix + "example-apps-staging", prefix + "example-apps-production"]
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
        // Process image pull secrets first, they might even be relevant if no registry is set
        if (newConfig.registry.createImagePullSecrets) {
            String username = newConfig.registry.readOnlyUsername ?: newConfig.registry.username
            String password = newConfig.registry.readOnlyPassword ?: newConfig.registry.password
            if (!username || !password) {
                throw new RuntimeException("createImagePullSecrets needs to be used with either registry username and password or the readOnly variants")
            }
        }

        if (newConfig.registry.url) {
            newConfig.registry.internal = false
            newConfig.registry.active = true
        } else if (newConfig.registry.active) {
            /* Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on 
               docker push in the example application's Jenkins Jobs.
               Both setting up HTTPS or allowing insecure registry via daemon.json makes the playground difficult to use.
               So, always use localhost.
               Allow overriding the port, in case multiple playground instance run on a single host in different 
               k3d clusters. */
            newConfig.registry.internal = true
            newConfig.registry.url = "localhost:${newConfig.registry.internalPort}"
        } else {
            // Registry not active, no need to set the following values
            return
        }

        if (newConfig.registry.proxyUrl) {
            newConfig.registry.twoRegistries = true
            if (!newConfig.registry.proxyUsername || !newConfig.registry.proxyPassword) {
                throw new RuntimeException("Proxy URL needs to be used with proxy-username and proxy-password")
            }
        }
    }

    private void addAdditionalApplicationConfig(Config newConfig) {
        if (System.getenv("KUBERNETES_SERVICE_HOST")) {
            log.debug("installation is running in kubernetes.")
            newConfig.application.runningInsideK8s = true
        }
    }

     private void addScmConfig(Config newConfig) {
        log.debug("Adding additional config for SCM")

         if(ScmTenantSchema.ScmmCentralConfig)
        //TODO
        /*if(newConfig.scm.gitlabConfig.url && newConfig.scm.gitlabConfig.password){
            newConfig.scm.provider= ScmTenantSchema.ScmProviderType.GITLAB
        }else if(newConfig.scm.scmmConfig.url){
            throw new RuntimeException(
        }*/

        newConfig.scm.scmmConfig.gitOpsUsername = "${newConfig.application.namePrefix}gitops"

        if (newConfig.scm.scmmConfig.url) {
            log.debug("Setting external scmm config")
            newConfig.scm.scmmConfig.internal = false
            newConfig.scm.scmmConfig.urlForJenkins = newConfig.scm.scmmConfig.url
        } else {
            log.debug("Setting configs for internal SCMHandler-Manager")
            // We use the K8s service as default name here, because it is the only option:
            // "scmm.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9091) 
            // will not work on Windows and MacOS.
            newConfig.scm.scmmConfig.urlForJenkins =
                    "http://scmm.${newConfig.application.namePrefix}scm-manager.svc.cluster.local/scm"

            // More internal fields are set lazily in ScmManger.groovy (after SCMM is deployed and ports are known) 
        }

        // We probably could get rid of some of the complexity by refactoring url, host and ingress into a single var
        if (newConfig.application.baseUrl) {
            newConfig.scm.scmmConfig.ingress = new URL(injectSubdomain("${newConfig.application.namePrefix}scmm",
                    newConfig.application.baseUrl as String, newConfig.application.urlSeparatorHyphen as Boolean)).host
        }
        // When specific user/pw are not set, set them to global values
        if (newConfig.scm.scmmConfig.password === Config.DEFAULT_ADMIN_PW) {
            newConfig.scm.scmmConfig.password = newConfig.application.password
        }
        if (newConfig.scm.scmmConfig.username === Config.DEFAULT_ADMIN_USER) {
            newConfig.scm.scmmConfig.username = newConfig.application.username
        }


    }

    private void addJenkinsConfig(Config newConfig) {
        log.debug("Adding additional config for Jenkins")
        if (newConfig.jenkins.url) {
            log.debug("Setting external jenkins config")
            newConfig.jenkins.active = true
            newConfig.jenkins.internal = false
            newConfig.jenkins.urlForScmm = newConfig.jenkins.url
        } else if (newConfig.jenkins.active) {
            log.debug("Setting configs for internal jenkins")
            // We use the K8s service as default name here, because it is the only option:
            // "jenkins.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9090)
            // will not work on Windows and MacOS.
            newConfig.jenkins.urlForScmm = "http://jenkins.${newConfig.application.namePrefix}jenkins.svc.cluster.local"

            // More internal fields are set lazily in Jenkins.groovy (after Jenkins is deployed and ports are known)
        } else {
            // Jenkins not active, no need to set the following values
            return
        }

        if (newConfig.application.baseUrl) {
            newConfig.jenkins.ingress = new URL(injectSubdomain("${newConfig.application.namePrefix}jenkins",
                    newConfig.application.baseUrl, newConfig.application.urlSeparatorHyphen)).host
        }
        // When specific user/pw are not set, set them to global values
        if (newConfig.jenkins.username === Config.DEFAULT_ADMIN_USER) {
            newConfig.jenkins.username = newConfig.application.username
        }
        if (newConfig.jenkins.password === Config.DEFAULT_ADMIN_PW) {
            newConfig.jenkins.password = newConfig.application.password
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
            argocd.url = injectSubdomain("${newConfig.application.namePrefix}argocd", baseUrl, urlSeparatorHyphen)
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

    void setMultiTenantModeConfig(Config newConfig) {
        if (newConfig.multiTenant.useDedicatedInstance) {
            if (!newConfig.application.namePrefix) {
                throw new RuntimeException('To enable Central Multi-Tenant mode, you must define a name prefix to distinguish between instances.')
            }

            if (!newConfig.multiTenant.username || !newConfig.multiTenant.password) {
                throw new RuntimeException('To use Central Multi Tenant mode define the username and password for the central SCMHandler instance.')
            }

            if (!newConfig.features.argocd.operator) {
                newConfig.features.argocd.operator = true
            }

            // Removes trailing slash from the input URL to avoid duplicated slashes in further URL handling
            if (newConfig.multiTenant.centralScmUrl) {
                String urlString = newConfig.multiTenant.centralScmUrl.toString()
                if (urlString.endsWith("/")) {
                    urlString = urlString[0..-2]
                }
                newConfig.multiTenant.centralScmUrl= urlString
            }

            //Disabling IngressNginx in DedicatedInstances Mode for now.
            //Ingress has to be handled by Cluster, not by this tenant.
            //Ingress has to be handled manually for local dev.
            //See /scripts/local/ for local dev.
            newConfig.features.ingressNginx.active = false
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

    /**
     * Make sure that config does not contain contradictory values.
     * Throws RuntimeException which meaningful message, if invalid.
     */
    void validateConfig(Config configToSet) {
        validateScmmAndJenkinsAreBothSet(configToSet)
        validateMirrorReposHelmChartFolderSet(configToSet)
        validateContent(configToSet)
    }

    private void validateMirrorReposHelmChartFolderSet(Config configToSet) {
        if (configToSet.application.mirrorRepos && !configToSet.application.localHelmChartFolder) {
            // This should only happen when run outside the image, i.e. during development
            throw new RuntimeException("Missing config for localHelmChartFolder.\n" +
                    "Either run inside the official container image or setting env var " +
                    "LOCAL_HELM_CHART_FOLDER='charts' after running 'scripts/downloadHelmCharts.sh' from the repo")
        }
    }

    static void validateContent(Config config) {
        config.content.repos.each { repo ->
            
            if (!repo.url) {
                throw new RuntimeException("content.repos requires a url parameter.")
            }
            if (repo.target) {
                if (repo.target.count('/') == 0) {
                    throw new RuntimeException("content.target needs / to separate namespace/group from repo name. Repo: ${repo.url}")
                }
            }

            switch (repo.type) {
                case ContentRepoType.COPY:
                    if (!repo.target) {
                        throw new RuntimeException("content.repos.type ${ContentRepoType.COPY} requires content.repos.target to be set. Repo: ${repo.url}")
                    }
                    break
                case ContentRepoType.FOLDER_BASED:
                    if (repo.target) {
                        throw new RuntimeException("content.repos.type ${ContentRepoType.FOLDER_BASED} does not support target parameter. Repo: ${repo.url}")
                    }
                    if (repo.targetRef) {
                        throw new RuntimeException("content.repos.type ${ContentRepoType.FOLDER_BASED} does not support targetRef parameter. Repo: ${repo.url}")
                    }
                    break
                case ContentRepoType.MIRROR:
                    if (!repo.target) {
                        throw new RuntimeException("content.repos.type ${ContentRepoType.MIRROR} requires content.repos.target to be set. Repo: ${repo.url}")
                    }
                    if (repo.path != ContentRepositorySchema.DEFAULT_PATH) {
                        throw new RuntimeException("content.repos.type ${ContentRepoType.MIRROR} does not support path. Current path: ${repo.path}. Repo: ${repo.url}")
                    }
                    if (repo.templating) {
                        throw new RuntimeException("content.repos.type ${ContentRepoType.MIRROR} does not support templating. Repo: ${repo.url}")
                    }
                    break
            }
        }
    }

    private void validateScmmAndJenkinsAreBothSet(Config configToSet) {
        if (configToSet.jenkins.active &&
                (configToSet.scmm.url && !configToSet.jenkins.url ||
                        !configToSet.scmm.url && configToSet.jenkins.url)) {
            throw new RuntimeException('When setting jenkins URL, scmm URL must also be set and the other way round')
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
        if (!configToSet.features.argocd.operator) {
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