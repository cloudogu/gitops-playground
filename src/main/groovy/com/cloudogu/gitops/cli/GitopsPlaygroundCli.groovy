package com.cloudogu.gitops.cli

import com.cloudogu.gitops.core.Application
import com.cloudogu.gitops.core.ApplicationConfigurator
import com.cloudogu.gitops.core.modules.ModuleRepository
import groovy.util.logging.Slf4j
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(name = 'gitops-playground-cli', description = 'CLI-tool to deploy gitops-playground.',
        mixinStandardHelpOptions = true)
@Slf4j
class GitopsPlaygroundCli implements Runnable {

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
    @Option(names = ['--skip-helm-update'], description = 'Skips adding and updating helm repos')
    private boolean skipHelmUpdate
    @Option(names = ['--argocd-config-only'], description = 'Skips installing argo-cd. Applies ConfigMap and Application manifests to bootstrap existing argo-cd')
    private boolean argocdConfigOnly

    // args group metrics
    @Option(names = ['--metrics'], description = 'Installs the Kube-Prometheus-Stack for ArgoCD. This includes Prometheus, the Prometheus operator, Grafana and some extra resources')
    private boolean metrics

    // args group debug
    @Option(names = ['-d', '--debug'], description = 'Debug output')
    private boolean debug
    @Option(names = ['-x', '--trace'], description = 'Debug + Show each command executed (set -x)')
    private boolean trace

    // args group configuration
    @Option(names = ['--username'], description = 'Set initial admin username')
    private String username
    @Option(names = ['--password'], description = 'Set initial admin passwords')
    private String password
    @Option(names = ['-y', '--yes'], description = 'Skip kubecontext confirmation')
    private boolean pipeYes

    // args group operator
    @Option(names = ['--fluxv1'], description = 'Install the Flux V1 module')
    private boolean fluxv1
    @Option(names = ['--fluxv2'], description = 'Install the Flux V2 module')
    private boolean fluxv2
    @Option(names = ['--argocd'], description = 'Install the ArgoCD module')
    private boolean argocd
    @Option(names = ['--argocd-url'], description = 'The URL where argocd is accessible. It has to be the full URL with http:// or https://')
    private String argocdUrl

    @Override
    void run() {
        ApplicationConfigurator applicationConfigurator = new ApplicationConfigurator(parseConfig())
        Map config = applicationConfigurator.populateConfig()

        Application app = new Application(new ModuleRepository(config))
        app.start()
    }

    private Map parseConfig() {
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
                        yamllint   : yamllintImage
                ],
                modules    : [
                        fluxv1 : fluxv1,
                        fluxv2 : fluxv2,
                        argocd : [
                                active    : argocd,
                                configOnly: argocdConfigOnly,
                                url       : argocdUrl
                        ],
                        metrics: metrics
                ]
        ]
    }

}

