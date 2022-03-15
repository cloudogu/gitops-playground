package com.cloudogu.gitops.cli

import com.cloudogu.gitops.core.ApplicationConfigurator
import com.cloudogu.gitops.core.Application
import com.cloudogu.gitops.core.modules.ModuleRepository
import io.micronaut.configuration.picocli.PicocliRunner
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(name = 'gitops-playground-cli', description = 'CLI-tool to deploy gitops-playground.',
        mixinStandardHelpOptions = true)
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
    @Option(names = ['--mailhog-username'], description = 'Sets the username for mailhogs web ui.')
    private String mailhogUsername
    @Option(names = ['--mailhog-password'], description = 'Sets the password for mailhogs web ui.')
    private String mailhogPassword

    // args group debug
    @Option(names = ['-d', '--debug'], description = 'Debug output')
    private boolean debug
    @Option(names = ['-x', '--trace'], description = 'Debug + Show each command executed (set -x)')
    private boolean trace

    // args group gop-configuration
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

    static void main(String[] args) throws Exception {
        // log levels can be set via picocli.trace sys env - defaults to 'WARN'
        if (args.contains("--trace"))
            System.setProperty("picocli.trace", "DEBUG")
        else if (args.contains("--debug"))
            System.setProperty("picocli.trace", "INFO")

        PicocliRunner.run(GitopsPlaygroundCli.class, args)
    }

    @Override
    void run() {
        // TODO: executing commands should be done using picocli commandline interface
        // @see: https://picocli.info/#execute

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
                ],
                mailhog: [
                        username: mailhogUsername,
                        password: mailhogPassword
                ]
        ]
    }

//    @Override
//    String toString() {
//        return "GitopsPlaygroundCli {" +
//                "\n registryUrl='" + registryUrl + '\'' +
//                ",\n registryPath='" + registryPath + '\'' +
//                ",\n registryUsername='" + registryUsername + '\'' +
//                ",\n registryPassword='" + registryPassword + '\'' +
//                ",\n internalRegistryPort=" + internalRegistryPort +
//                ",\n jenkinsUrl='" + jenkinsUrl + '\'' +
//                ",\n jenkinsUsername='" + jenkinsUsername + '\'' +
//                ",\n jenkinsPassword='" + jenkinsPassword + '\'' +
//                ",\n scmmUrl='" + scmmUrl + '\'' +
//                ",\n scmmUsername='" + scmmUsername + '\'' +
//                ",\n scmmPassword='" + scmmPassword + '\'' +
//                ",\n remote=" + remote +
//                ",\n insecure=" + insecure +
//                ",\n kubectlImage='" + kubectlImage + '\'' +
//                ",\n helmImage='" + helmImage + '\'' +
//                ",\n kubevalImage='" + kubevalImage + '\'' +
//                ",\n helmKubevalImage='" + helmKubevalImage + '\'' +
//                ",\n yamllintImage='" + yamllintImage + '\'' +
//                ",\n skipHelmUpdate=" + skipHelmUpdate +
//                ",\n argocdConfigOnly=" + argocdConfigOnly +
//                ",\n debug=" + debug +
//                ",\n trace=" + trace +
//                ",\n username='" + username + '\'' +
//                ",\n password='" + password + '\'' +
//                ",\n pipeYes=" + pipeYes +
//                ",\n fluxv1=" + fluxv1 +
//                ",\n fluxv2=" + fluxv2 +
//                ",\n argocd=" + argocd +
//                ",\n argocdUrl='" + argocdUrl + '\'' +
//                ",\n metrics='" + metrics + '\'' +
//                '\n}';
//    }
}

