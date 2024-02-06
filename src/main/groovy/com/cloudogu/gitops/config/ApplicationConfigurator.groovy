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
    public static final String DEFAULT_ADMIN_USER = 'admin'
    public static final String DEFAULT_ADMIN_PW = 'admin'
    /**
     * When changing values make sure to modify GitOpsPlaygroundCli and Schema as well
     * @see com.cloudogu.gitops.cli.GitopsPlaygroundCli
     * @see com.cloudogu.gitops.config.schema.Schema
     */
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
                    urlForScmm: "http://jenkins", // Set dynamically
                    metricsUsername: 'metrics',
                    metricsPassword: 'metrics',
            ],
            scmm       : [
                    internal: true, // Set dynamically
                    url     : '',
                    username: DEFAULT_ADMIN_USER,
                    password: DEFAULT_ADMIN_PW,
                    urlForJenkins : 'http://scmm-scm-manager/scm', // set dynamically
                    host : '', // Set dynamically
                    protocol : '' // Set dynamically
            ],
            application: [
                    remote        : false,
                    insecure      : false,
                    username      : DEFAULT_ADMIN_USER,
                    password      : DEFAULT_ADMIN_PW,
                    yes           : false,
                    runningInsideK8s : false, // Set dynamically
                    clusterBindAddress : '', // Set dynamically
                    namePrefix    : '',
                    namePrefixForEnvVars    : '', // Set dynamically
                    baseUrl: null,
            ],
            images     : [
                    // When updating please also adapt in Dockerfile, vars.tf, apply.sh and init-cluster.sh
                    kubectl    : "lachlanevenson/k8s-kubectl:v1.25.4",
                    // cloudogu/helm also contains kubeval and helm kubeval plugin. Using the same image makes builds faster
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
            ]
            ,
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
                                    version: '42.0.3',
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
                                    url: '',
                                    helm: [
                                            chart  : 'vault',
                                            repoURL: 'https://helm.releases.hashicorp.com',
                                            version: '0.22.1',
                                            image: '',
                                    ]
                            ]
                    ],
                    ingress: [
                            active: false, // Set dynamically
                            helm  : [
                                    chart: 'ingress-nginx',
                                    repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                    version: '4.8.0'
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
    Map config
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
    ApplicationConfigurator setConfig(Map configToSet) {
        Map newConfig = deepCopy(config)
        deepMerge(configToSet, newConfig)

        addAdditionalApplicationConfig(newConfig)
        setScmmConfig(newConfig)
        addJenkinsConfig(newConfig)

        if (newConfig.registry['url'])
            newConfig.registry["internal"] = false
        if (newConfig['features']['secrets']['vault']['mode'])
            newConfig['features']['secrets']['active'] = true
        if (newConfig['features']['mail']['smtpAddress'] || newConfig['features']['mail']['mailhog'])
            newConfig['features']['mail']['active'] = true
        if (newConfig['features']['mail']['smtpAddress'] && newConfig['features']['mail']['mailhog']) {
            newConfig['features']['mail']['mailhog'] = false
            log.warn("Enabled both external Mailserver and MailHog! Implicitly deactivating MailHog")
        }
        if (newConfig['application']['baseUrl'] && !newConfig['features']['ingress']['active']) {
            log.warn("Ingress-controller is activated without baseUrl parameter. Services will not be accessible by hostnames. To avoid this use baseUrl with ingress. ")
        }

        evaluateBaseUrl(newConfig)
        
        config = makeDeeplyImmutable(newConfig)

        return this
    }

    ApplicationConfigurator setConfig(File configFile) {
        def map = new YamlSlurper().parse(configFile)
        if (!(map instanceof Map)) {
            throw new RuntimeException("Could not parse YAML as map: $map")
        }
        schemaValidator.validate(new ObjectMapper().convertValue(map, JsonNode))

        return setConfig(map as Map)
    }

    ApplicationConfigurator setConfig(String configFile) {
        def map = new YamlSlurper().parseText(configFile)
        if (!(map instanceof Map)) {
            throw new RuntimeException("Could not parse YAML as map: $map")
        }
        schemaValidator.validate(new ObjectMapper().convertValue(map, JsonNode))

        return setConfig(map as Map)
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
        } else if (newConfig.application["runningInsideK8s"]) {
            log.debug("Setting jenkins url to k8s service, since installation is running inside k8s")
            newConfig.jenkins["url"] = networkingUtils.createUrl("jenkins.default.svc.cluster.local", "80")
        } else {
            log.debug("Setting internal jenkins configs")
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
            
            if (argocd['active'] && !argocd['url']) {
                argocd['url'] = injectSubdomain('argocd', baseUrl)
                log.debug("Setting URL ${argocd['url']}")
            }
            if (mail['mailhog'] && !mail['mailhogUrl']) {
                mail['mailhogUrl'] = injectSubdomain('mailhog', baseUrl)
                log.debug("Setting URL ${mail['mailhogUrl']}")
            }
            if (monitoring['active'] && !monitoring['grafanaUrl']) {
                monitoring['grafanaUrl'] = injectSubdomain('grafana', baseUrl)
                log.debug("Setting URL ${monitoring['grafanaUrl']}")
            }
            if ( newConfig.features['secrets']['active'] && !vault['url']) {
                vault['url'] = injectSubdomain('vault', baseUrl)
                log.debug("Setting URL ${vault['url']}")
            }
            
            if (!newConfig.features['exampleApps']['petclinic']['baseDomain']) {
                // This param only requires the host / domain
                newConfig.features['exampleApps']['petclinic']['baseDomain'] = new URL(injectSubdomain('petclinic', baseUrl)).host
                log.debug("Setting URL ${newConfig.features['exampleApps']['petclinic']['baseDomain']}")
            }
            if (!newConfig.features['exampleApps']['nginx']['baseDomain']) {
                // This param only requires the host / domain
                newConfig.features['exampleApps']['nginx']['baseDomain'] = new URL(injectSubdomain('nginx', baseUrl)).host
                log.debug("Setting URL ${newConfig.features['exampleApps']['nginx']['baseDomain']}")
            }
        }
    }

    /**
     * 
     * @param subdomain, e.g. argocd
     * @param baseUrl e.g. http://localhost:8080
     * @return e.g. http://argocd.localhost:8080
     */
    String injectSubdomain(String subdomain, String baseUrl) {
        URL url = new URL(baseUrl)
        String newUrl = url.getProtocol() + "://" + subdomain + "." + url.getHost()
        if (url.getPort() != -1) {
            newUrl += ":" + url.getPort()
        }
        newUrl += url.getPath()
        return newUrl
    }
}
