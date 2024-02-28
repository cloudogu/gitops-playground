package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.ConfigToConfigFileConverter
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.utils.K8sClient
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson 
/**
 * Provides the entrypoint to the application as well as all config parameters.
 * When changing parameters, make sure to update the Schema for the config file as well
 *
 * @see com.cloudogu.gitops.config.schema.Schema
 */
@Command(
        name = 'apply.sh',
        description = 'CLI-tool to deploy gitops-playground.',
        mixinStandardHelpOptions = true)

@Slf4j
class GitopsPlaygroundCli  implements Runnable {
    // args group registry
    @Option(names = ['--registry-url'], description = 'The url of your external registry')
    private String registryUrl
    @Option(names = ['--registry-path'], description = 'Optional when --registry-url is set')
    private String registryPath
    @Option(names = ['--registry-username'], description = 'Optional when --registry-url is set')
    private String registryUsername
    @Option(names = ['--registry-password'], description = 'Optional when --registry-url is set')
    private String registryPassword
    @Option(names = ['--internal-registry-port'], description = 'Port of registry registry. Ignored when registry-url is set')
    private Integer internalRegistryPort

    // args group jenkins
    @Option(names = ['--jenkins-url'], description = 'The url of your external jenkins')
    private String jenkinsUrl
    @Option(names = ['--jenkins-username'], description = 'Mandatory when --jenkins-url is set')
    private String jenkinsUsername
    @Option(names = ['--jenkins-password'], description = 'Mandatory when --jenkins-url is set')
    private String jenkinsPassword
    @Option(names = ['--jenkins-metrics-username'], description = 'Mandatory when --jenkins-url is set and monitoring enabled')
    private String jenkinsMetricsUsername
    @Option(names = ['--jenkins-metrics-password'], description = 'Mandatory when --jenkins-url is set and monitoring enabled')
    private String jenkinsMetricsPassword

    // args group scm
    @Option(names = ['--scmm-url'], description = 'The host of your external scm-manager')
    private String scmmUrl
    @Option(names = ['--scmm-username'], description = 'Mandatory when --scmm-url is set')
    private String scmmUsername
    @Option(names = ['--scmm-password'], description = 'Mandatory when --scmm-url is set')
    private String scmmPassword

    // args group remote
    @Option(names = ['--remote'], description = 'Install on remote Cluster e.g. gcp')
    private Boolean remote
    @Option(names = ['--insecure'], description = 'Sets insecure-mode in cURL which skips cert validation')
    private Boolean insecure

    // args group tool configuration
    @Option(names = ['--kubectl-image'], description = 'Sets image for kubectl')
    private String kubectlImage
    @Option(names = ['--helm-image'], description = 'Sets image for helm')
    private String helmImage
    @Option(names = ['--kubeval-image'], description = 'Sets image for kubeval')
    private String kubevalImage
    @Option(names = ['--helmkubeval-image'], description = 'Sets image for helmkubeval')
    private String helmKubevalImage
    @Option(names = ['--yamllint-image'], description = 'Sets image for yamllint')
    private String yamllintImage
    @Option(names = ['--grafana-image'], description = 'Sets image for grafana')
    private String grafanaImage
    @Option(names = ['--grafana-sidecar-image'], description = 'Sets image for grafana\'s sidecar')
    private String grafanaSidecarImage
    @Option(names = ['--prometheus-image'], description = 'Sets image for prometheus')
    private String prometheusImage
    @Option(names = ['--prometheus-operator-image'], description = 'Sets image for prometheus-operator')
    private String prometheusOperatorImage
    @Option(names = ['--prometheus-config-reloader-image'], description = 'Sets image for prometheus-operator\'s config-reloader')
    private String prometheusConfigReloaderImage
    @Option(names = ['--external-secrets-image'], description = 'Sets image for external secrets operator')
    private String externalSecretsOperatorImage
    @Option(names = ['--external-secrets-certcontroller-image'], description = 'Sets image for external secrets operator\'s controller')
    private String externalSecretsOperatorCertControllerImage
    @Option(names = ['--external-secrets-webhook-image'], description = 'Sets image for external secrets operator\'s webhook')
    private String externalSecretsOperatorWebhookImage
    @Option(names = ['--vault-image'], description = 'Sets image for vault')
    private String vaultImage
    @Option(names = ['--nginx-image'], description = 'Sets image for nginx used in various applications')
    private String nginxImage
    @Option(names = ['--base-url'], description = 'the external base url (TLD) for all tools, e.g. https://example.com or http://localhost:8080. The individual -url params for argocd, grafana, vault and mailhog take precedence.')
    private String baseUrl

    // args group metrics
    @Option(names = ['--metrics', '--monitoring'], description = 'Installs the Kube-Prometheus-Stack. This includes Prometheus, the Prometheus operator, Grafana and some extra resources')
    private Boolean monitoring
    @Option(names = ['--grafana-url'], description = 'Sets url for grafana')
    private String grafanaUrl
    @Option(names = ['--grafana-email-from'], description = 'Notifications, define grafana alerts sender email address')
    private String grafanaEmailFrom
    @Option(names = ['--grafana-email-to'], description = 'Notifications, define grafana alerts recipient email address')
    private String grafanaEmailTo

    // args group vault / secrets
    @Option(names = ['--vault'], description = 'Installs Hashicorp vault and the external secrets operator. Possible values: ${COMPLETION-CANDIDATES}')
    private VaultModes vault
    enum VaultModes { dev, prod }
    @Option(names = ['--vault-url'], description = 'Sets url for vault ui')
    private String vaultUrl

    @Option(names = ['--mailhog-url'], description = 'Sets url for MailHog')
    private String mailhogUrl
    @Option(names = ['--mailhog', '--mail'], description = 'Installs MailHog as Mail server.', scope = CommandLine.ScopeType.INHERIT)
    private Boolean mailhog

    // condition check dependent parameters of external Mailserver
    @Option(names = ['--smtp-address'], description = 'Sets smtp port of external Mailserver')
    private String smtpAddress
    @Option(names = ['--smtp-port'], description = 'Sets smtp port of external Mailserver')
    private Integer smtpPort
    @Option(names = ['--smtp-user'], description = 'Sets smtp username for external Mailserver')
    private String smtpUser
    @Option(names = ['--smtp-password'], description = 'Sets smtp password of external Mailserver')
    private String smtpPassword

    // args group debug
    @Option(names = ['-d', '--debug'], description = 'Debug output', scope = CommandLine.ScopeType.INHERIT)
    private Boolean debug
    @Option(names = ['-x', '--trace'], description = 'Debug + Show each command executed (set -x)', scope = CommandLine.ScopeType.INHERIT)
    private Boolean trace

    // args group configuration
    @Option(names = ['--username'], description = 'Set initial admin username')
    private String username
    @Option(names = ['--password'], description = 'Set initial admin passwords')
    private String password
    @Option(names = ['-y', '--yes'], description = 'Skip kubecontext confirmation')
    private Boolean pipeYes
    @Option(names = ['--name-prefix'], description = 'Set name-prefix for repos, jobs, namespaces')
    private String namePrefix
    @Option(names = ['--destroy'], description = 'Unroll playground')
    private Boolean destroy
    @Option(names = ['--config-file'], description = 'Configuration using a config file')
    private String configFile
    @Option(names = ['--config-map'], description = 'Kubernetes configuration map. Should contain a key `config.yaml`.')
    private String configMap
    @Option(names = ['--output-config-file'], description = 'Output current config as config file as much as possible')
    private Boolean outputConfigFile

    // args group ArgoCD operator
    @Option(names = ['--argocd'], description = 'Install ArgoCD ')
    private Boolean argocd
    @Option(names = ['--argocd-url'], description = 'The URL where argocd is accessible. It has to be the full URL with http:// or https://')
    private String argocdUrl
    @Option(names = ['--argocd-email-from'], description = 'Notifications, define Argo CD sender email address')
    private String emailFrom
    @Option(names = ['--argocd-email-to-user'], description = 'Notifications, define Argo CD user / app-team recipient email address')
    private String emailToUser
    @Option(names = ['--argocd-email-to-admin'], description = 'Notifications, define Argo CD admin recipient email address')
    private String emailToAdmin

    // args group example apps
    @Option(names = ['--petclinic-base-domain'], description = 'The domain under which a subdomain for all petclinic will be used.')
    private String petclinicBaseDomain
    @Option(names = ['--nginx-base-domain'], description = 'The domain under which a subdomain for all nginx applications will be used.')
    private String nginxBaseDomain

    // args Ingress-Class
    @Option(names = ['--ingress-nginx'], description = 'Sets and enables Nginx Ingress Controller')
    private Boolean ingressNginx



    @Override
    void run() {
        def context = ApplicationContext.run().registerSingleton(new Configuration(getConfig()))

        if (destroy) {
            Destroyer destroyer = context.getBean(Destroyer)
            destroyer.destroy()
        } else if (outputConfigFile) {
            def configFileConverter = context.getBean(ConfigToConfigFileConverter)
            println(configFileConverter.convert(getConfig()))
        } else {
            Application app = context.getBean(Application)
            app.start()
        }
    }

    void setLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.cloudogu.gitops")
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

    private Map getConfig() {
        if (configFile && configMap) {
            log.error("Cannot provide --config-file and --config-map at the same time.")
            System.exit(1)
        }

        def appContext = ApplicationContext.run()
        ApplicationConfigurator applicationConfigurator = appContext.getBean(ApplicationConfigurator)
        if (configFile) {
            applicationConfigurator.setConfig(new File(configFile))
        } else if (configMap) {
            def k8sClient = appContext.getBean(K8sClient)
            def configValues = k8sClient.getConfigMap(configMap, 'config.yaml')

            applicationConfigurator.setConfig(configValues)
        }

        applicationConfigurator.setConfig(parseOptionsIntoConfig())

        Map config = applicationConfigurator.getConfig()

        log.debug("Actual config: ${prettyPrint(toJson(config))}")

        return config
    }

    private Map parseOptionsIntoConfig() {

        return [
                registry   : [
                        url         : registryUrl,
                        path        : registryPath,
                        username    : registryUsername,
                        password    : registryPassword,
                        internalPort: internalRegistryPort
                ],
                jenkins    : [
                        url     : jenkinsUrl,
                        username: jenkinsUsername,
                        password: jenkinsPassword,
                        metricsUsername: jenkinsMetricsUsername,
                        metricsPassword: jenkinsMetricsPassword,
                ],
                scmm       : [
                        url     : scmmUrl,
                        username: scmmUsername,
                        password: scmmPassword
                ],
                application: [
                        remote        : remote,
                        insecure      : insecure,
                        debug         : debug,
                        trace         : trace,
                        username      : username,
                        password      : password,
                        pipeYes       : pipeYes,
                        namePrefix    : namePrefix,
                        baseUrl : baseUrl,
                ],
                images     : [
                        kubectl    : kubectlImage,
                        helm       : helmImage,
                        kubeval    : kubevalImage,
                        helmKubeval: helmKubevalImage,
                        yamllint   : yamllintImage,
                        nginx      : nginxImage,
                ],
                features    : [
                        argocd : [
                                active    : argocd,
                                url       : argocdUrl,
                                emailFrom    : emailFrom,
                                emailToUser  : emailToUser,
                                emailToAdmin : emailToAdmin
                        ],
                        mail: [
                                mailhog: mailhog,
                                mailhogUrl : mailhogUrl,
                                smtpAddress : smtpAddress,
                                smtpPort : smtpPort,
                                smtpUser : smtpUser,
                                smtpPassword : smtpPassword
                        ],
                        exampleApps: [
                                petclinic: [
                                        baseDomain: petclinicBaseDomain,
                                ],
                                nginx    : [
                                        baseDomain: nginxBaseDomain,
                                ],
                        ],
                        monitoring : [
                                active    : monitoring,
                                grafanaUrl: grafanaUrl,
                                grafanaEmailFrom : grafanaEmailFrom,
                                grafanaEmailTo   : grafanaEmailTo,
                                helm      : [
                                        grafanaImage: grafanaImage,
                                        grafanaSidecarImage: grafanaSidecarImage,
                                        prometheusImage: prometheusImage,
                                        prometheusOperatorImage: prometheusOperatorImage,
                                        prometheusConfigReloaderImage: prometheusConfigReloaderImage,
                                ]
                        ],
                        secrets : [
                                vault : [
                                        mode : vault,
                                        url: vaultUrl,
                                        helm: [
                                                image: vaultImage
                                        ]
                                ],
                                externalSecrets: [
                                        helm: [
                                                image              : externalSecretsOperatorImage,
                                                certControllerImage: externalSecretsOperatorCertControllerImage,
                                                webhookImage       : externalSecretsOperatorWebhookImage
                                        ]
                                ]
                        ],
                        ingressNginx: [
                               active: ingressNginx
                        ],
                ]
        ]
    }
}
