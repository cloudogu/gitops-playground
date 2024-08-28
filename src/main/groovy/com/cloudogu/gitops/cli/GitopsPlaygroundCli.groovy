package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
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
import static com.cloudogu.gitops.config.ConfigConstants.*

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson 
/**
 * Provides the entrypoint to the application as well as all config parameters.
 * When changing parameters, make sure to update the Schema for the config file as well
 *
 * @see com.cloudogu.gitops.config.schema.Schema
 */
@Command(
        name = 'apply-ng',
        description = 'CLI-tool to deploy gitops-playground.',
        mixinStandardHelpOptions = true)

@Slf4j
class GitopsPlaygroundCli  implements Runnable {
    // args group registry
    @Option(names = ['--internal-registry-port'], description = REGISTRY_INTERNAL_PORT_DESCRIPTION)
    private Integer internalRegistryPort
    @Option(names = ['--registry-url'], description = REGISTRY_URL_DESCRIPTION)
    private String registryUrl
    @Option(names = ['--registry-path'], description = REGISTRY_PATH_DESCRIPTION)
    private String registryPath
    @Option(names = ['--registry-username'], description = REGISTRY_USERNAME_DESCRIPTION)
    private String registryUsername
    @Option(names = ['--registry-password'], description = REGISTRY_PASSWORD_DESCRIPTION)
    private String registryPassword
    @Option(names = ['--registry-pull-url'], description = REGISTRY_PULL_URL_DESCRIPTION)
    private String registryPullUrl
    @Option(names = ['--registry-pull-username'], description = REGISTRY_PULL_USERNAME_DESCRIPTION)
    private String registryPullUsername
    @Option(names = ['--registry-pull-password'], description = REGISTRY_PULL_PASSWORD_DESCRIPTION)
    private String registryPullPassword
    @Option(names = ['--registry-push-url'], description = REGISTRY_PUSH_URL_DESCRIPTION)
    private String registryPushUrl
    @Option(names = ['--registry-push-path'], description = REGISTRY_PUSH_PATH_DESCRIPTION)
    private String registryPushPath
    @Option(names = ['--registry-push-username'], description = REGISTRY_PUSH_USERNAME_DESCRIPTION)
    private String registryPushUsername
    @Option(names = ['--registry-push-password'], description = REGISTRY_PUSH_PASSWORD_DESCRIPTION)
    private String registryPushPassword

    // args group jenkins
    @Option(names = ['--jenkins-url'], description = JENKINS_URL_DESCRIPTION)
    private String jenkinsUrl
    @Option(names = ['--jenkins-username'], description = JENKINS_USERNAME_DESCRIPTION)
    private String jenkinsUsername
    @Option(names = ['--jenkins-password'], description = JENKINS_PASSWORD_DESCRIPTION)
    private String jenkinsPassword
    @Option(names = ['--jenkins-metrics-username'], description = JENKINS_METRICS_USERNAME_DESCRIPTION)
    private String jenkinsMetricsUsername
    @Option(names = ['--jenkins-metrics-password'], description = JENKINS_METRICS_PASSWORD_DESCRIPTION)
    private String jenkinsMetricsPassword
    @Option(names = ['--maven-central-mirror'], description = MAVEN_CENTRAL_MIRROR_DESCRIPTION)
    private String mavenCentralMirror

    // args group scm
    @Option(names = ['--scmm-url'], description = SCMM_URL_DESCRIPTION)
    private String scmmUrl
    @Option(names = ['--scmm-username'], description = SCMM_USERNAME_DESCRIPTION)
    private String scmmUsername
    @Option(names = ['--scmm-password'], description = SCMM_PASSWORD_DESCRIPTION)
    private String scmmPassword

    // args group remote
    @Option(names = ['--remote'], description = REMOTE_DESCRIPTION)
    private Boolean remote
    @Option(names = ['--insecure'], description = INSECURE_DESCRIPTION)
    private Boolean insecure
    @Option(names = ['--openshift'], description = OPENSHIFT_DESCRIPTION)
    private Boolean openshift

    // args group tool configuration
    @Option(names = ['--git-name'], description = GIT_NAME_DESCRIPTION)
    private String gitName
    @Option(names = ['--git-email'], description = GIT_EMAIL_DESCRIPTION)
    private String gitEmail
    @Option(names = ['--kubectl-image'], description = KUBECTL_IMAGE_DESCRIPTION)
    private String kubectlImage
    @Option(names = ['--helm-image'], description = HELM_IMAGE_DESCRIPTION)
    private String helmImage
    @Option(names = ['--kubeval-image'], description = KUBEVAL_IMAGE_DESCRIPTION)
    private String kubevalImage
    @Option(names = ['--helmkubeval-image'], description = HELMKUBEVAL_IMAGE_DESCRIPTION)
    private String helmKubevalImage
    @Option(names = ['--yamllint-image'], description = YAMLLINT_IMAGE_DESCRIPTION)
    private String yamllintImage
    @Option(names = ['--grafana-image'], description = GRAFANA_IMAGE_DESCRIPTION)
    private String grafanaImage
    @Option(names = ['--grafana-sidecar-image'], description = GRAFANA_SIDECAR_IMAGE_DESCRIPTION)
    private String grafanaSidecarImage
    @Option(names = ['--prometheus-image'], description = PROMETHEUS_IMAGE_DESCRIPTION)
    private String prometheusImage
    @Option(names = ['--prometheus-operator-image'], description = PROMETHEUS_OPERATOR_IMAGE_DESCRIPTION)
    private String prometheusOperatorImage
    @Option(names = ['--prometheus-config-reloader-image'], description = PROMETHEUS_CONFIG_RELOADER_IMAGE_DESCRIPTION)
    private String prometheusConfigReloaderImage
    @Option(names = ['--external-secrets-image'], description = EXTERNAL_SECRETS_IMAGE_DESCRIPTION)
    private String externalSecretsOperatorImage
    @Option(names = ['--external-secrets-certcontroller-image'], description = EXTERNAL_SECRETS_CERT_CONTROLLER_IMAGE_DESCRIPTION)
    private String externalSecretsOperatorCertControllerImage
    @Option(names = ['--external-secrets-webhook-image'], description = EXTERNAL_SECRETS_WEBHOOK_IMAGE_DESCRIPTION)
    private String externalSecretsOperatorWebhookImage
    @Option(names = ['--vault-image'], description = VAULT_IMAGE_DESCRIPTION)
    private String vaultImage
    @Option(names = ['--nginx-image'], description = NGINX_IMAGE_DESCRIPTION)
    private String nginxImage
    @Option(names = ['--petclinic-image'], description = PETCLINIC_IMAGE_DESCRIPTION)
    private String petClinicImage
    @Option(names = ['--base-url'], description = BASE_URL_DESCRIPTION)
    private String baseUrl
    @Option(names = ['--url-separator-hyphen'], description = URL_SEPARATOR_HYPHEN_DESCRIPTION)
    private Boolean urlSeparatorHyphen
    @Option(names = ['--mirror-repos'], description = MIRROR_REPOS_DESCRIPTION)
    private Boolean mirrorRepos
    @Option(names = ['--skip-crds'], description = SKIP_CRDS_DESCRIPTION)
    private Boolean skipCrds
    @Option(names = ['--namespace-isolation'], description = NAMESPACE_ISOLATION_DESCRIPTION)
    private Boolean namespaceIsolation

    // args group metrics
    @Option(names = ['--metrics', '--monitoring'], description = MONITORING_ENABLE_DESCRIPTION)
    private Boolean monitoring
    @Option(names = ['--grafana-url'], description = GRAFANA_URL_DESCRIPTION)
    private String grafanaUrl
    @Option(names = ['--grafana-email-from'], description = GRAFANA_EMAIL_FROM_DESCRIPTION)
    private String grafanaEmailFrom
    @Option(names = ['--grafana-email-to'], description = GRAFANA_EMAIL_TO_DESCRIPTION)
    private String grafanaEmailTo

    // args group vault / secrets
    @Option(names = ['--vault'], description = VAULT_ENABLE_DESCRIPTION)
    private VaultModes vault
    enum VaultModes { dev, prod }
    @Option(names = ['--vault-url'], description = VAULT_URL_DESCRIPTION)
    private String vaultUrl

    @Option(names = ['--mailhog-url'], description = MAILHOG_URL_DESCRIPTION)
    private String mailhogUrl
    @Option(names = ['--mailhog', '--mail'], description = MAILHOG_ENABLE_DESCRIPTION, scope = CommandLine.ScopeType.INHERIT)
    private Boolean mailhog

    // condition check dependent parameters of external Mailserver
    @Option(names = ['--smtp-address'], description = SMTP_ADDRESS_DESCRIPTION)
    private String smtpAddress
    @Option(names = ['--smtp-port'], description = SMTP_PORT_DESCRIPTION)
    private Integer smtpPort
    @Option(names = ['--smtp-user'], description = SMTP_USER_DESCRIPTION)
    private String smtpUser
    @Option(names = ['--smtp-password'], description = SMTP_PASSWORD_DESCRIPTION)
    private String smtpPassword

    // args group debug
    @Option(names = ['-d', '--debug'], description = DEBUG_DESCRIPTION, scope = CommandLine.ScopeType.INHERIT)
    Boolean debug
    @Option(names = ['-x', '--trace'], description = TRACE_DESCRIPTION, scope = CommandLine.ScopeType.INHERIT)
    Boolean trace

    // args group configuration
    @Option(names = ['--username'], description = USERNAME_DESCRIPTION)
    private String username
    @Option(names = ['--password'], description = PASSWORD_DESCRIPTION)
    private String password
    @Option(names = ['-y', '--yes'], description = PIPE_YES_DESCRIPTION)
    Boolean yes
    @Option(names = ['--name-prefix'], description = NAME_PREFIX_DESCRIPTION)
    private String namePrefix
    @Option(names = ['--destroy'], description = DESTROY_DESCRIPTION)
    Boolean destroy
    @Option(names = ['--config-file'], description = CONFIG_FILE_DESCRIPTION)
    String configFile
    @Option(names = ['--config-map'], description = CONFIG_MAP_DESCRIPTION)
    String configMap
    @Option(names = ['--output-config-file'], description = OUTPUT_CONFIG_FILE_DESCRIPTION)
    Boolean outputConfigFile
    @Option(names = ['--pod-resources'], description = POD_RESOURCES_DESCRIPTION)
    Boolean podResources

    // args group ArgoCD operator
    @Option(names = ['--argocd'], description = ARGOCD_ENABLE_DESCRIPTION)
    private Boolean argocd
    @Option(names = ['--argocd-url'], description = ARGOCD_URL_DESCRIPTION)
    private String argocdUrl
    @Option(names = ['--argocd-email-from'], description = ARGOCD_EMAIL_FROM_DESCRIPTION)
    private String emailFrom
    @Option(names = ['--argocd-email-to-user'], description = ARGOCD_EMAIL_TO_USER_DESCRIPTION)
    private String emailToUser
    @Option(names = ['--argocd-email-to-admin'], description = ARGOCD_EMAIL_TO_ADMIN_DESCRIPTION)
    private String emailToAdmin

    // args group example apps
    @Option(names = ['--petclinic-base-domain'], description = EXAMPLE_APPS_DESCRIPTION)
    private String petclinicBaseDomain
    @Option(names = ['--nginx-base-domain'], description = EXAMPLE_APPS_DESCRIPTION)
    private String nginxBaseDomain

    // args Ingress-Class
    @Option(names = ['--ingress-nginx'], description = INGRESS_NGINX_ENABLE_DESCRIPTION)
    private Boolean ingressNginx


    @Override
    void run() {
        setLogging()
        
        def context = createApplicationContext()
        
        if (outputConfigFile) {
            println(context.getBean(ConfigToConfigFileConverter)
                    .convert(getConfig(context, true)))
            return
        }
        
        def config = getConfig(context, false)
        register(context, new Configuration(config))
        
        K8sClient k8sClient = context.getBean(K8sClient)

        if (config['application']['destroy']) {
            confirmOrExit "Destroying gitops playground in kubernetes cluster '${k8sClient.currentContext}'.", config
            
            Destroyer destroyer = context.getBean(Destroyer)
            destroyer.destroy()
        } else {
            confirmOrExit "Applying gitops playground to kubernetes cluster '${k8sClient.currentContext}'.", config

            Application app = context.getBean(Application)
            app.start()

            printWelcomeScreen()
        }
    }

    protected void register(ApplicationContext context, Configuration configuration) {
        context.registerSingleton(configuration)
    }

    private void confirmOrExit(String message, Map config) {
        if (config['application']['yes']) {
            return
        }
        
        log.info("\n${message}\nContinue? y/n [n]")
                
        def input = System.in.newReader().readLine()
        
        if (input != 'y') {
            System.exit(1) 
        }
    }
    
    protected ApplicationContext createApplicationContext() {
        ApplicationContext.run()
    }

    void setLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.cloudogu.gitops")
        if (trace) {
            log.info("Setting loglevel to trace")
            logger.setLevel(Level.TRACE)
        } else if (debug) {
            log.info("Setting loglevel to debug")
            logger.setLevel(Level.DEBUG)
        } else {
            setSimpleLogPattern()
        }
    }

    /**
     * Changes log pattern to a simpler one, to reduce noise for normal users
     */
    void setSimpleLogPattern() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()
        def rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        def defaultPattern = ((rootLogger.getAppender('STDOUT') as ConsoleAppender)
                .getEncoder() as PatternLayoutEncoder).pattern

        // Avoid duplicate output by existing appender
        rootLogger.detachAppender('STDOUT')
        PatternLayoutEncoder encoder = new PatternLayoutEncoder()
        // Remove less relevant details from log pattern
        encoder.setPattern(defaultPattern 
                .replaceAll(" \\S*%thread\\S* ", " ")
                .replaceAll(" \\S*%logger\\S* ", " "))
        encoder.setContext(loggerContext)
        encoder.start()
        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>()
        appender.setName('STDOUT')
        appender.setContext(loggerContext)
        appender.setEncoder(encoder)
        appender.start()
        rootLogger.addAppender(appender)
    }

    private Map getConfig(ApplicationContext appContext, boolean skipInternalConfig) {
        if (configFile && configMap) {
            throw new RuntimeException("Cannot provide --config-file and --config-map at the same time.")
        }

        ApplicationConfigurator applicationConfigurator = appContext.getBean(ApplicationConfigurator)
        if (configFile) {
            applicationConfigurator.setConfig(new File(configFile), true)
        } else if (configMap) {
            def k8sClient = appContext.getBean(K8sClient)
            def configValues = k8sClient.getConfigMap(configMap, 'config.yaml')

            applicationConfigurator.setConfig(configValues, true)
        }

        Map config = applicationConfigurator.setConfig(parseOptionsIntoConfig(), skipInternalConfig)

        log.debug("Actual config: ${prettyPrint(toJson(config))}")

        return config
    }

    void printWelcomeScreen() {
        log.info '''\n
  |----------------------------------------------------------------------------------------------|
  |                       Welcome to the GitOps playground by Cloudogu!
  |----------------------------------------------------------------------------------------------|
  |
  | Please find the URLs of the individual applications in our README:
  | https://github.com/cloudogu/gitops-playground/blob/main/README.md#table-of-contents
  |
  | A good starting point might also be the services or ingresses inside your cluster:  
  | kubectl get svc -A
  | Or (depending on your config)
  | kubectl get ing -A
  |
  | Please be aware, Jenkins and Argo CD may take some time to build and deploy all apps.
  |----------------------------------------------------------------------------------------------|
'''
    }

    private Map parseOptionsIntoConfig() {

        return [
                registry   : [
                        internalPort: internalRegistryPort,
                        url         : registryUrl,
                        path        : registryPath,
                        username    : registryUsername,
                        password    : registryPassword,
                        pullUrl         : registryPullUrl,
                        pullUsername    : registryPullUsername,
                        pullPassword    : registryPullPassword,
                        pushUrl         : registryPushUrl,
                        pushPath        : registryPushPath,
                        pushUsername    : registryPushUsername,
                        pushPassword    : registryPushPassword,
                ],
                jenkins    : [
                        url     : jenkinsUrl,
                        username: jenkinsUsername,
                        password: jenkinsPassword,
                        metricsUsername: jenkinsMetricsUsername,
                        metricsPassword: jenkinsMetricsPassword,
                        mavenCentralMirror: mavenCentralMirror,
                ],
                scmm       : [
                        url     : scmmUrl,
                        username: scmmUsername,
                        password: scmmPassword
                ],
                application: [
                        openshift     : openshift,
                        remote        : remote,
                        mirrorRepos     : mirrorRepos, 
                        destroy : destroy,
                        insecure      : insecure,
                        debug         : debug,
                        trace         : trace,
                        username      : username,
                        password      : password,
                        yes       : yes,
                        namePrefix    : namePrefix,
                        podResources : podResources,
                        baseUrl : baseUrl,
                        gitName: gitName,
                        gitEmail: gitEmail,
                        urlSeparatorHyphen : urlSeparatorHyphen,
                        skipCrds : skipCrds,
                        namespaceIsolation: namespaceIsolation
                ],
                images     : [
                        kubectl    : kubectlImage,
                        helm       : helmImage,
                        kubeval    : kubevalImage,
                        helmKubeval: helmKubevalImage,
                        yamllint   : yamllintImage,
                        nginx      : nginxImage,
                        petclinic  : petClinicImage,
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
