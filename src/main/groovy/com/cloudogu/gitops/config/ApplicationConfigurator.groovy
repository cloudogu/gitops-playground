package com.cloudogu.gitops.config

import com.cloudogu.gitops.config.schema.JsonSchemaValidator
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import jakarta.inject.Singleton

import static com.cloudogu.gitops.utils.MapUtils.*

@Slf4j
@Singleton
class ApplicationConfigurator {

    public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:3.10.3-1"
    // When updating please also adapt in Dockerfile, vars.tf and init-cluster.sh
    public static final String K8S_VERSION = "1.29"
    public static final String DEFAULT_ADMIN_USER = 'admin'
    public static final String DEFAULT_ADMIN_PW = 'admin'
    public static final String DEFAULT_REGISTRY_PORT = '30000'
    /**
     * When changing values make sure to modify GitOpsPlaygroundCli and Schema as well
     * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli
     * @see com.cloudogu.gitops.config.schema.Schema
     */
    // This is deliberately non-static, so as to allow getenv() to work with GraalVM static images
    private final Map DEFAULT_VALUES = makeDeeplyImmutable([
            registry   : [
                    internal: true, // Set dynamically
                    twoRegistries: false, // Set dynamically
                    internalPort: DEFAULT_REGISTRY_PORT,
                    // Single registry
                    url         : '',
                    path        : '',
                    username    : '',
                    password    : '',
                    // Alternative: Use different registries, e.g. in air-gapped envs 
                    // "Pull" registry for 3rd party images
                    pullUrl         : '',
                    pullUsername    : '',
                    pullPassword    : '',
                    // "Push" registry for writing application specific images
                    pushUrl         : '',
                    pushPath        : '',
                    pushUsername    : '',
                    pushPassword    : '',
                    helm  : [
                            chart  : 'docker-registry',
                            repoURL: 'https://charts.helm.sh/stable',
                            version: '1.9.4'
                    ]
            ],
            jenkins    : [
                    internal: true, // Set dynamically
                    url     : '',
                    username: DEFAULT_ADMIN_USER,
                    password: DEFAULT_ADMIN_PW,
                    /* This is the URL configured in SCMM inside the Jenkins Plugin, e.g. at http://scmm.localhost/scm/admin/settings/jenkins
                      We use the K8s service as default name here, because it is the only option:
                      "jenkins.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9090) will not work on Windows and MacOS.
                      
                      For production we overwrite this when config.jenkins["url"] is set. 
                      See addJenkinsConfig() and the comment at scmm.urlForJenkins */
                    urlForScmm: "http://jenkins", // Set dynamically
                    metricsUsername: 'metrics',
                    metricsPassword: 'metrics',
                    helm  : [
                            //chart  : 'jenkins',
                            //repoURL: 'https://charts.jenkins.io',
                            /* When Upgrading helm chart, also upgrade controller.tag in jenkins/values.yaml
                            In addition:
                             - Upgrade bash image in values.yaml and gid-grepper
                             - Also upgrade plugins. See docs/developers.md
                             */
                            version: '5.0.17'
                    ],
                    mavenCentralMirror: '',
            ],
            scmm       : [
                    internal: true, // Set dynamically
                    url     : '',
                    username: DEFAULT_ADMIN_USER,
                    password: DEFAULT_ADMIN_PW,
                    gitOpsUsername : '', // Set dynamically
                    /* This corresponds to the "Base URL" in SCMM Settings.
                       We use the K8s service as default name here, to make the build on push feature (webhooks from SCMM to Jenkins that trigger builds) work in k3d.
                       The webhook contains repository URLs that start with the "Base URL" Setting of SCMM.
                       Jenkins checks these repo URLs and triggers all builds that match repo URLs. 
                       In k3d, we have to define the repos in Jenkins using the K8s Service name, because they are the only option.
                       "scmm.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9091) will not work on Windows and MacOS.
                       So, we have to use the matching URL in SCMM as well.
                       
                       For production we overwrite this when config.scmm["url"] is set. 
                       See addScmmConfig() */
                    urlForJenkins : 'http://scmm-scm-manager/scm', // set dynamically
                    host : '', // Set dynamically
                    protocol : '', // Set dynamically
                    ingress : '', // Set dynamically
                    helm  : [
                            chart  : 'scm-manager',
                            repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                            version: '3.1.0'
                    ]
            ],
            application: [
                    remote        : false,
                    mirrorRepos     : false,
                    // Take from env because the Dockerfile provides a local copy of the repo for air-gapped mode
                    localHelmChartFolder: System.getenv('LOCAL_HELM_CHART_FOLDER'),
                    insecure      : false,
                    username      : DEFAULT_ADMIN_USER,
                    password      : DEFAULT_ADMIN_PW,
                    yes           : false,
                    runningInsideK8s : false, // Set dynamically
                    clusterBindAddress : '', // Set dynamically
                    namePrefix    : '',
                    namePrefixForEnvVars    : '', // Set dynamically
                    baseUrl: null,
                    gitName: 'Cloudogu',
                    gitEmail: 'hello@cloudogu.com',
                    urlSeparatorHyphen: false
            ],
            images     : [
                    kubectl    : "bitnami/kubectl:$K8S_VERSION",
                    // cloudogu/helm also contains kubeval and helm kubeval plugin. Using the same image makes builds faster
                    helm       : HELM_IMAGE,
                    kubeval    : HELM_IMAGE,
                    helmKubeval: HELM_IMAGE,
                    yamllint   : "cytopia/yamllint:1.25-0.7",
                    nginx      : null,
                    petclinic   : 'eclipse-temurin:11-jre-alpine'
            ],
            repositories : [
                    springBootHelmChart: [
                            // Take from env or use default because the Dockerfile provides a local copy of the repo
                            url: System.getenv('SPRING_BOOT_HELM_CHART_REPO') ?: 'https://github.com/cloudogu/spring-boot-helm-chart.git',
                            ref: '0.3.2'
                    ],
                    springPetclinic: [
                            url: System.getenv('SPRING_PETCLINIC_REPO') ?: 'https://github.com/cloudogu/spring-petclinic.git',
                            ref: '32c8653'
                    ],
                    gitopsBuildLib: [
                            url: System.getenv('GITOPS_BUILD_LIB_REPO') ?: 'https://github.com/cloudogu/gitops-build-lib.git',
                    ],
                    cesBuildLib: [
                            url: System.getenv('CES_BUILD_LIB_REPO') ?: 'https://github.com/cloudogu/ces-build-lib.git',
                    ]
            ],
            features   : [
                    argocd    : [
                            active    : false,
                            url       : '',
                            emailFrom : 'argocd@example.org',
                            emailToUser : 'app-team@example.org',
                            emailToAdmin : 'infra@example.org'
                    ],
                    mail   : [
                            active: false, // set dynamically
                            mailhog : false,
                            mailhogUrl : '',
                            smtpAddress: '',
                            smtpPort : '',
                            smtpUser : '',
                            smtpPassword : '', 
                            url: '',
                            helm  : [
                                    chart  : 'mailhog',
                                    repoURL: 'https://codecentric.github.io/helm-charts',
                                    version: '5.0.1',
                                    image: 'ghcr.io/cloudogu/mailhog:v1.0.1'
                            ]
                    ],
                    monitoring: [
                            active: false,
                            grafanaUrl: '',
                            grafanaEmailFrom : 'grafana@example.org',
                            grafanaEmailTo : 'infra@example.org',
                            helm  : [
                                    chart  : 'kube-prometheus-stack',
                                    repoURL: 'https://prometheus-community.github.io/helm-charts',
                                    /* When updating this make sure to also test if air-gapped mode still works */
                                    version: '58.2.1',
                                    grafanaImage: '',
                                    grafanaSidecarImage: '',
                                    prometheusImage: '',
                                    prometheusOperatorImage: '',
                                    prometheusConfigReloaderImage: '',
                            ]
                    ],
                    secrets   : [
                            active         : false, // Set dynamically
                            externalSecrets: [
                                    helm: [
                                            chart  : 'external-secrets',
                                            repoURL: 'https://charts.external-secrets.io',
                                            version: '0.9.16',
                                            image  : '',
                                            certControllerImage: '',
                                            webhookImage: ''
                                    ]
                            ],
                            vault          : [
                                    mode: '',
                                    url: '',
                                    helm: [
                                            chart  : 'vault',
                                            repoURL: 'https://helm.releases.hashicorp.com',
                                            version: '0.25.0',
                                            image: '',
                                    ]
                            ]
                    ],
                    ingressNginx: [
                            active: false,
                            helm  : [
                                    chart: 'ingress-nginx',
                                    repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                    version: '4.9.1',
                                    values: [:]
                             ],
                    ],
                    exampleApps: [
                            petclinic: [
                                    baseDomain: '',
                            ],
                            nginx    : [
                                    baseDomain: '',
                            ],
                    ]
            ]
    ])
    
    private Map config
    private NetworkingUtils networkingUtils
    private FileSystemUtils fileSystemUtils
    private JsonSchemaValidator schemaValidator

    ApplicationConfigurator(NetworkingUtils networkingUtils, FileSystemUtils fileSystemUtils, JsonSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator
        this.config = DEFAULT_VALUES
        this.networkingUtils = networkingUtils
        this.fileSystemUtils = fileSystemUtils
    }

    /**
     * Sets config internally and als returns it, fluent interface
     */
    Map setConfig(Map configToSet, boolean skipInternalConfig = false) {
        Map newConfig = deepCopy(config)
        deepMerge(configToSet, newConfig)

        validate(newConfig)
        
        if (skipInternalConfig) {
            config = makeDeeplyImmutable(newConfig)
            return config
        }
        
        addAdditionalApplicationConfig(newConfig)
        

        addScmmConfig(newConfig)
        addJenkinsConfig(newConfig)


        String namePrefix = newConfig.application['namePrefix']
        if (namePrefix) {
            if (!namePrefix.endsWith('-')) {
                newConfig.application['namePrefix'] = "${namePrefix}-"
            }
            newConfig.application['namePrefixForEnvVars'] ="${(newConfig.application['namePrefix'] as String).toUpperCase().replace('-', '_')}"
        }

        addRegistryConfig(newConfig)
        
        if (newConfig['features']['secrets']['vault']['mode'])
            newConfig['features']['secrets']['active'] = true
        if (newConfig['features']['mail']['smtpAddress'] || newConfig['features']['mail']['mailhog'])
            newConfig['features']['mail']['active'] = true
        if (newConfig['features']['mail']['smtpAddress'] && newConfig['features']['mail']['mailhog']) {
            newConfig['features']['mail']['mailhog'] = false
            log.warn("Enabled both external Mailserver and MailHog! Implicitly deactivating MailHog")
        }
        if (newConfig['features']['ingressNginx']['active'] && !newConfig['application']['baseUrl']) {
            log.warn("Ingress-controller is activated without baseUrl parameter. Services will not be accessible by hostnames. To avoid this use baseUrl with ingress. ")
        }

        evaluateBaseUrl(newConfig)
        
        config = makeDeeplyImmutable(newConfig)

        return config
    }

    private void addRegistryConfig(Map newConfig) {
        if (newConfig.registry['pullUrl'] && newConfig.registry['pushUrl']) {
            newConfig.registry['twoRegistries'] = true
        } else if (newConfig.registry['pullUrl'] && !newConfig.registry['pushUrl'] ||
                newConfig.registry['pushUrl'] && !newConfig.registry['pullUrl']) {
            throw new RuntimeException("Always set pull AND push URL. pullUrl=${newConfig.registry['pullUrl']}, pushUrl=${newConfig.registry['pushUrl']}")
        }

        if (newConfig.registry['url'] && newConfig.registry['twoRegistries']) {
            log.warn("Set both registry.url and registry.pullUrl/registry.pushUrl! Implicitly ignoring registry.url")
        }

        if (newConfig.registry['url'] || newConfig.registry['twoRegistries']) {
            newConfig.registry['internal'] = false
        } else {
            /* Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on 
               docker push in the example application's Jenkins Jobs.
               Both setting up HTTPS or allowing insecure registry via daemon.json makes the playground difficult to use.
               So, always use localhost.
               Allow overriding the port, in case multiple playground instance run on a single host in different 
               k3d clusters. */
            newConfig.registry['url'] = "localhost:${newConfig.registry['internalPort']}"
        }
    }

    Map setConfig(File configFile, boolean skipInternalConfig = false) {
        def map = new YamlSlurper().parse(configFile)
        if (!(map instanceof Map)) {
            throw new RuntimeException("Could not parse YAML as map: $map")
        }
        schemaValidator.validate(new ObjectMapper().convertValue(map, JsonNode))

        return setConfig(map as Map, skipInternalConfig)
    }

    Map setConfig(String configFile, boolean skipInternalConfig = false) {
        def map = new YamlSlurper().parseText(configFile)
        if (!(map instanceof Map)) {
            throw new RuntimeException("Could not parse YAML as map: $map")
        }
        schemaValidator.validate(new ObjectMapper().convertValue(map, JsonNode))

        return setConfig(map as Map, skipInternalConfig)
    }

    private void addAdditionalApplicationConfig(Map newConfig) {
        if (System.getenv("KUBERNETES_SERVICE_HOST")) {
            log.debug("installation is running in kubernetes.")
            newConfig.application["runningInsideK8s"] = true
        }
        String clusterBindAddress = networkingUtils.findClusterBindAddress()
        log.debug("Setting cluster bind Address: " + clusterBindAddress)
        newConfig.application["clusterBindAddress"] = clusterBindAddress
    }

    private void addScmmConfig(Map newConfig) {
        log.debug("Adding additional config for SCM-Manager")

        newConfig.scmm['gitOpsUsername'] = "${this.config.application['namePrefix']}gitops"

        if (newConfig.scmm["url"]) {
            log.debug("Setting external scmm config")
            newConfig.scmm["internal"] = false
            newConfig.scmm["urlForJenkins"] = newConfig.scmm["url"]
        } else if (newConfig.application["runningInsideK8s"]) {
            log.debug("Setting scmm url to k8s service, since installation is running inside k8s")
            newConfig.scmm["url"] = networkingUtils.createUrl("scmm-scm-manager.default.svc.cluster.local", "80", "/scm")
        } else {
            log.debug("Setting internal configs for local single node cluster with internal scmm")
            def port = fileSystemUtils.getLineFromFile(fileSystemUtils.getRootDir() + "/scm-manager/values.ftl.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            String cba = newConfig.application["clusterBindAddress"]
            newConfig.scmm["url"] = networkingUtils.createUrl(cba, port, "/scm")
        }

        String scmmUrl = newConfig.scmm["url"]
        log.debug("Getting host and protocol from scmmUrl: " + scmmUrl)
        newConfig.scmm["host"] = networkingUtils.getHost(scmmUrl)
        newConfig.scmm["protocol"] = networkingUtils.getProtocol(scmmUrl)

        // We probably could get rid of some of the complexity by refactoring url, host and ingress into a single var
        if (newConfig.application['baseUrl']) {
            newConfig.scmm['ingress'] = new URL(injectSubdomain('scmm',
                    newConfig.application['baseUrl'] as String, newConfig.application['urlSeparatorHyphen'] as Boolean)).host
        }
    }

    private void addJenkinsConfig(Map newConfig) {
        log.debug("Adding additional config for Jenkins")
        if (newConfig.jenkins["url"]) {
            log.debug("Setting external jenkins config")
            newConfig.jenkins["internal"] = false
            newConfig.jenkins["urlForScmm"] = newConfig.jenkins["url"] 
        } else if (newConfig.application["runningInsideK8s"]) {
            log.debug("Setting jenkins url to k8s service, since installation is running inside k8s")
            newConfig.jenkins["url"] = networkingUtils.createUrl("jenkins.default.svc.cluster.local", "80")
        } else {
            log.debug("Setting jenkins configs for local single node cluster with internal jenkins")
            def port = fileSystemUtils.getLineFromFile(fileSystemUtils.getRootDir() + "/jenkins/values.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            String cba = newConfig.application["clusterBindAddress"]
            newConfig.jenkins["url"] = networkingUtils.createUrl(cba, port)
        }
    }

    private void evaluateBaseUrl(Map newConfig) {
        String baseUrl = newConfig.application['baseUrl']
        if (baseUrl) {
            log.debug("Base URL set, adapting to individual tools")
            def argocd = newConfig.features['argocd']
            def mail = newConfig.features['mail']
            def monitoring = newConfig.features['monitoring']
            def vault = newConfig.features['secrets']['vault']
            boolean urlSeparatorHyphen = newConfig.application['urlSeparatorHyphen']

            if (argocd['active'] && !argocd['url']) {
                argocd['url'] = injectSubdomain('argocd', baseUrl, urlSeparatorHyphen)
                log.debug("Setting URL ${argocd['url']}")
            }
            if (mail['mailhog'] && !mail['mailhogUrl']) {
                mail['mailhogUrl'] = injectSubdomain('mailhog', baseUrl, urlSeparatorHyphen)
                log.debug("Setting URL ${mail['mailhogUrl']}")
            }
            if (monitoring['active'] && !monitoring['grafanaUrl']) {
                monitoring['grafanaUrl'] = injectSubdomain('grafana', baseUrl, urlSeparatorHyphen)
                log.debug("Setting URL ${monitoring['grafanaUrl']}")
            }
            if ( newConfig.features['secrets']['active'] && !vault['url']) {
                vault['url'] = injectSubdomain('vault', baseUrl, urlSeparatorHyphen)
                log.debug("Setting URL ${vault['url']}")
            }
            
            if (!newConfig.features['exampleApps']['petclinic']['baseDomain']) {
                // This param only requires the host / domain
                newConfig.features['exampleApps']['petclinic']['baseDomain'] =
                        new URL(injectSubdomain('petclinic', baseUrl, urlSeparatorHyphen)).host
                log.debug("Setting URL ${newConfig.features['exampleApps']['petclinic']['baseDomain']}")
            }
            if (!newConfig.features['exampleApps']['nginx']['baseDomain']) {
                // This param only requires the host / domain
                newConfig.features['exampleApps']['nginx']['baseDomain'] =
                        new URL(injectSubdomain('nginx', baseUrl, urlSeparatorHyphen)).host
                log.debug("Setting URL ${newConfig.features['exampleApps']['nginx']['baseDomain']}")
            }
        }
    }

    /**
     * 
     * @param subdomain, e.g. argocd
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

    private void validate(Map configToSet) {
        if (configToSet.scmm["url"] && !configToSet.jenkins["url"] ||
                !configToSet.scmm["url"] && configToSet.jenkins["url"]) {
            throw new RuntimeException('When setting jenkins URL, scmm URL must also be set and the other way round')
        }
        if (configToSet.application['mirrorRepos'] && !configToSet.application['localHelmChartFolder']) {
            // This should only happen when run outside the image, i.e. during development
            throw new RuntimeException("Missing config for localHelmChartFolder.\n" +
                    "Either run inside the official container image or setting env var " +
                    "LOCAL_HELM_CHART_FOLDER='charts' after running 'scripts/downloadHelmCharts.sh' from the repo")
        }

    }
}
