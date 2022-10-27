package com.cloudogu.gitops

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.slf4j.LoggerFactory

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

@Slf4j
class ApplicationConfigurator {

    public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:3.5.4-1"
    public static final String DEFAULT_ADMIN_USER = 'admin'
    public static final String DEFAULT_ADMIN_PW = 'admin'
    private static final Map DEFAULT_VALUES = makeDeeplyImmutable([
            registry   : [
                    internal: true, // Set dynamically
                    url         : '',
                    path        : '',
                    username    : '',
                    password    : '',
                    internalPort: ''
            ],
            jenkins    : [
                    internal: true, // Set dynamically
                    url     : '',
                    username: DEFAULT_ADMIN_USER,
                    password: DEFAULT_ADMIN_PW,
                    urlForScmm: "http://jenkins"
            ],
            scmm       : [
                    internal: true, // Set dynamically
                    url     : '',
                    username: DEFAULT_ADMIN_USER,
                    password: DEFAULT_ADMIN_PW,
                    urlForJenkins : 'http://scmm-scm-manager/scm',
                    host : '', // Set dynamically
                    protocol : '' // Set dynamically
            ],
            application: [
                    remote        : false,
                    insecure      : false,
                    skipHelmUpdate: false,
                    debug         : false,
                    trace         : false,
                    username      : DEFAULT_ADMIN_USER,
                    password      : DEFAULT_ADMIN_PW,
                    yes           : false,
                    runningInsideK8s : false, // Set dynamically
                    clusterBindAddress : '' // Set dynamically
            ],
            images     : [
                    kubectl    : "lachlanevenson/k8s-kubectl:v1.21.2",
                    helm       : HELM_IMAGE,
                    kubeval    : HELM_IMAGE,
                    helmKubeval: HELM_IMAGE,
                    yamllint   : "cytopia/yamllint:1.25-0.7"
            ],
            repositories : [
                    springBootHelmChart: "https://github.com/cloudogu/spring-boot-helm-chart.git",
                    springPetclinic    : "https://github.com/cloudogu/spring-petclinic.git",
                    gitopsBuildLib     : "https://github.com/cloudogu/gitops-build-lib.git",
                    cesBuildLib        : "https://github.com/cloudogu/ces-build-lib.git"
            ],
            features   : [
                    fluxv1    : true,
                    fluxv2    : true,
                    argocd    : [
                            active    : true,
                            configOnly: false,
                            url       : ''
                    ],
                    mail      : [
                            active: true,
                            helm  : [
                                    chart  : 'mailhog',
                                    repoURL: 'https://codecentric.github.io/helm-charts',
                                    version: '5.0.1'
                            ]
                    ],
                    monitoring: [
                            active: false,
                            helm  : [
                                    chart  : 'kube-prometheus-stack',
                                    repoURL: 'https://prometheus-community.github.io/helm-charts',
                                    version: '19.2.2'
                            ]
                    ],
                    secrets   : [
                            active         : false, // Set dynamically
                            externalSecrets: [
                                    helm: [
                                            chart  : 'external-secrets',
                                            repoURL: 'https://charts.external-secrets.io',
                                            version: '0.6.0'
                                    ]
                            ],
                            vault          : [
                                    mode: ''
                            ]
                    ],
            ]
    ])
    Map config
    private NetworkingUtils networkingUtils
    private FileSystemUtils fileSystemUtils

    ApplicationConfigurator(NetworkingUtils networkingUtils = new NetworkingUtils(), FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.config = DEFAULT_VALUES
        this.networkingUtils = networkingUtils
        this.fileSystemUtils = fileSystemUtils
    }

    /**
     * Sets config internally and als returns it, fluent interface
     */
    Map setConfig(Map configToSet) {
        Map newConfig = deepCopy(config)
        deepMerge(configToSet, newConfig)

        setLogLevel(newConfig)
        addAdditionalApplicationConfig(newConfig)
        setScmmConfig(newConfig)
        addJenkinsConfig(newConfig)

        if (newConfig.registry['url'])
            newConfig.registry["internal"] = false
        if (newConfig['features']['secrets']['vault']['mode'])
            newConfig['features']['secrets']['active'] = true
        
        log.debug(prettyPrint(toJson(config)))
        config = makeDeeplyImmutable(newConfig)
        return config
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

    private void setScmmConfig(Map newConfig) {
        log.debug("Adding additional config for SCM-Manager")

        if (newConfig.scmm["url"]) {
            log.debug("Setting external scmm config")
            newConfig.scmm["internal"] = false
            newConfig.scmm["urlForJenkins"] = newConfig.scmm["url"]
        } else if (newConfig.application["runningInsideK8s"]) {
            log.debug("Setting scmm url to k8s service, since installation is running inside k8s")
            newConfig.scmm["url"] = networkingUtils.createUrl("scmm-scm-manager.default.svc.cluster.local", "80", "/scm")
        } else {
            log.debug("Setting internal scmm configs")
            def port = fileSystemUtils.getLineFromFile(fileSystemUtils.getRootDir() + "/scm-manager/values.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            String cba = newConfig.application["clusterBindAddress"]
            newConfig.scmm["url"] = networkingUtils.createUrl(cba, port, "/scm")
        }

        String scmmUrl = newConfig.scmm["url"]
        log.debug("Getting host and protocol from scmmUrl: " + scmmUrl)
        newConfig.scmm["host"] = networkingUtils.getHost(scmmUrl)
        newConfig.scmm["protocol"] = networkingUtils.getProtocol(scmmUrl)
    }

    private void addJenkinsConfig(Map newConfig) {
        log.debug("Adding additional config for Jenkins")
        if (newConfig.jenkins["url"]) {
            log.debug("Setting external jenkins config")
            newConfig.jenkins["internal"] = false
            newConfig.jenkins["urlForScmm"] = newConfig.jenkins["url"] 
        }
        // TODO missing external config (see setScmmConfig())
    }

    private void setLogLevel(Map newConfig) {
        boolean trace = newConfig.application["trace"]
        boolean debug = newConfig.application["debug"]
        Logger logger = (Logger) LoggerFactory.getLogger("com.cloudogu.gitops");
        if (trace) {
            log.info("Setting loglevel to trace")
            logger.setLevel(Level.TRACE)
        } else if (debug) {
            log.info("Setting loglevel to debug")
            logger.setLevel(Level.DEBUG);
        } else {
            logger.setLevel(Level.INFO)
        }
    }

    Map deepCopy(Map input) {
        // Lazy mans deep map copy 😬
        String json = toJson(input)
        return (Map) new JsonSlurper().parseText(json)
    }

    Map deepMerge(Map src, Map target) {
        src.forEach(
                (key, value) -> { if (value != null) target.merge(key, value, (oldVal, newVal) -> {
                        if (oldVal instanceof Map) {
                            if (!newVal instanceof Map) {
                                throw new RuntimeException("Can't merge config, different types, map vs other: Map ${oldVal}; Other ${newVal}")
                            }
                            return deepMerge(newVal as Map, oldVal)
                        } else {
                            return newVal
                        }
                    })
                })
        return target
    }

    static Map makeDeeplyImmutable(Map map) {
        map.forEach((key, value) -> {
            if (value instanceof Map) {
                map[key] = Collections.unmodifiableMap(value)
            }
        })
        return Collections.unmodifiableMap(map)
    }
}
