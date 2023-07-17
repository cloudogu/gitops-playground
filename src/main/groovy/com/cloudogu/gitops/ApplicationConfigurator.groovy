package com.cloudogu.gitops

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j
import org.slf4j.LoggerFactory

import static com.cloudogu.gitops.utils.MapUtils.*

@Slf4j
class ApplicationConfigurator {

    public static final String HELM_IMAGE = "ghcr.io/cloudogu/helm:3.10.3-1"
    public static final String DEFAULT_ADMIN_USER = 'admin'
    public static final String DEFAULT_ADMIN_PW = 'admin'
    // This is deliberately non-static, so as to allow getenv() to work with GraalVM static images 
    private final Map DEFAULT_VALUES = makeDeeplyImmutable([
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
                    username      : DEFAULT_ADMIN_USER,
                    password      : DEFAULT_ADMIN_PW,
                    yes           : false,
                    runningInsideK8s : false, // Set dynamically
                    clusterBindAddress : '' // Set dynamically
            ],
            images     : [
                    kubectl    : "lachlanevenson/k8s-kubectl:v1.25.4",
                    helm       : HELM_IMAGE,
                    kubeval    : HELM_IMAGE,
                    helmKubeval: HELM_IMAGE,
                    yamllint   : "cytopia/yamllint:1.25-0.7",
                    nginx      : null,
            ],
            repositories : [
                    springBootHelmChart: [
                            // Take from env or use default because the Dockerfile provides a local copy of the repo
                            url: System.getenv('SPRING_BOOT_HELM_CHART_REPO') ?: 'https://github.com/cloudogu/spring-boot-helm-chart.git',
                            ref: '0.3.0'
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
            ]
            ,
            features   : [
                    fluxv2    : true,
                    argocd    : [
                            active    : true,
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
                                    version: '19.2.2',
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
                                            version: '0.6.1',
                                            image  : '',
                                            certControllerImage: '',
                                            webhookImage: ''
                                    ]
                            ],
                            vault          : [
                                    mode: '',
                                    helm: [
                                            chart  : 'vault',
                                            repoURL: 'https://helm.releases.hashicorp.com',
                                            version: '0.22.1',
                                            image: '',
                                    ]
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

        addAdditionalApplicationConfig(newConfig)
        setScmmConfig(newConfig)
        addJenkinsConfig(newConfig)

        if (newConfig.registry['url'])
            newConfig.registry["internal"] = false
        if (newConfig['features']['secrets']['vault']['mode'])
            newConfig['features']['secrets']['active'] = true
        
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
}
