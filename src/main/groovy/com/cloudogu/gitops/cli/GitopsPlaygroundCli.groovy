package com.cloudogu.gitops.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

@Command(
        name = 'gitops-playground-cli',
        description = 'CLI-tool to deploy gitops-playground.',
        mixinStandardHelpOptions = true,
        subcommands = JenkinsCli)
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
    private int internalRegistryPort

    // args group jenkins
    @Option(names = ['--jenkins-url'], description = 'The url of your external jenkins')
    private String jenkinsUrl
    @Option(names = ['--jenkins-username'], description = 'Mandatory when --jenkins-url is set')
    private String jenkinsUsername
    @Option(names = ['--jenkins-password'], description = 'Mandatory when --jenkins-url is set')
    private String jenkinsPassword

    // args group scm
    @Option(names = ['--scmm-url'], description = 'The host of your external scm-manager')
    private String scmmUrl
    @Option(names = ['--scmm-username'], description = 'Mandatory when --scmm-url is set')
    private String scmmUsername
    @Option(names = ['--scmm-password'], description = 'Mandatory when --scmm-url is set')
    private String scmmPassword

    // args group remote
    @Option(names = ['--remote'], description = 'Install on remote Cluster e.g. gcp')
    private boolean remote
    @Option(names = ['--insecure'], description = 'Sets insecure-mode in cURL which skips cert validation')
    private boolean insecure

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

    @Option(names = ['--skip-helm-update'], description = 'Skips adding and updating helm repos')
    private boolean skipHelmUpdate

    // args group metrics
    @Option(names = ['--metrics', '--monitoring'], description = 'Installs the Kube-Prometheus-Stack. This includes Prometheus, the Prometheus operator, Grafana and some extra resources')
    private boolean monitoring

    // args group metrics
    @Option(names = ['--vault'], description = 'Installs Hashicorp vault and the external secrets operator. Possible values: ${COMPLETION-CANDIDATES}')
    private VaultModes vault
    enum VaultModes { dev, prod }

    // args group debug
    @Option(names = ['-d', '--debug'], description = 'Debug output', scope = CommandLine.ScopeType.INHERIT)
    private boolean debug
    @Option(names = ['-x', '--trace'], description = 'Debug + Show each command executed (set -x)', scope = CommandLine.ScopeType.INHERIT)
    private boolean trace

    // args group configuration
    @Option(names = ['--username'], description = 'Set initial admin username')
    private String username
    @Option(names = ['--password'], description = 'Set initial admin passwords')
    private String password
    @Option(names = ['-y', '--yes'], description = 'Skip kubecontext confirmation')
    private boolean pipeYes

    // args group operator
    @Option(names = ['--fluxv2'], description = 'Install Flux V2')
    private boolean fluxv2
    @Option(names = ['--argocd'], description = 'Install ArgoCD ')
    private boolean argocd
    @Option(names = ['--argocd-url'], description = 'The URL where argocd is accessible. It has to be the full URL with http:// or https://')
    private String argocdUrl

    @Override
    void run() {
        def context = ApplicationContext.run().registerSingleton(new Configuration(getConfig()))
        Application app = context.getBean(Application)
        app.start()
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
        ApplicationConfigurator applicationConfigurator = ApplicationContext.run().getBean(ApplicationConfigurator)
        Map config = applicationConfigurator
                // Here we could implement loading from a config file, giving CLI params precedence
                //.setConfig(configFile.toFile().getText())
                .setConfig(parseOptionsIntoConfig())

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
                        password: jenkinsPassword
                ],
                scmm       : [
                        url     : scmmUrl,
                        username: scmmUsername,
                        password: scmmPassword
                ],
                application: [
                        remote        : remote,
                        insecure      : insecure,
                        skipHelmUpdate: skipHelmUpdate,
                        debug         : debug,
                        trace         : trace,
                        username      : username,
                        password      : password,
                        pipeYes       : pipeYes,
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
                        fluxv2 : fluxv2,
                        argocd : [
                                active    : argocd,
                                url       : argocdUrl
                        ],
                        monitoring : [
                                active    : monitoring,
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
                ]
        ]
    }
}
