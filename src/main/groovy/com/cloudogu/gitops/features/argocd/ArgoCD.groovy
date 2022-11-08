package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.ScmmRepo
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class ArgoCD extends Feature {

    private static final String NGINX_HELM_JENKINS_VALUES_PATH = 'k8s/values-shared.yaml'
    
    private Map config
    private List<ScmmRepo> gitRepos = []

    protected File controlAppTmpDir
    private File nginxHelmJenkinsTmpDir
    protected K8sClient k8sClient = new K8sClient()
    private FileSystemUtils fileSystemUtils = new FileSystemUtils()

    ArgoCD(Map config) {
        this.config = config

        controlAppTmpDir = File.createTempDir('gitops-playground-control-app')
        controlAppTmpDir.deleteOnExit()
        gitRepos += createRepo('argocd/control-app', 'argocd/control-app', controlAppTmpDir)
        
        nginxHelmJenkinsTmpDir = File.createTempDir('gitops-playground-nginx-helm-jenkins')
        nginxHelmJenkinsTmpDir.deleteOnExit()
        gitRepos += createRepo('applications/nginx/argocd/helm-jenkins', 'argocd/nginx-helm-jenkins',
                nginxHelmJenkinsTmpDir)
    }

    @Override
    boolean isEnabled() {
        config.features['argocd']['active']
    }

    @Override
    void enable() {
        gitRepos.forEach( repo -> {
            repo.cloneRepo()
        })
        
        def nginxHelmJenkinsValuesTmpFile = Path.of nginxHelmJenkinsTmpDir.absolutePath, NGINX_HELM_JENKINS_VALUES_PATH
        Map nginxHelmJenkinsValuesYaml = fileSystemUtils.readYaml(nginxHelmJenkinsValuesTmpFile)

        new ArgoCDNotifications(config, controlAppTmpDir.absolutePath).install()

        if (config.features['secrets']['active']) {
            k8sClient.createSecret('generic', 'vault-token', 'argocd-production', 
                    new Tuple2('token', config['application']['username']))
            k8sClient.createSecret('generic', 'vault-token', 'argocd-staging', 
                    new Tuple2('token', config['application']['username']))
        } else {
            //TODO remove secret from nginx-helm-jenkins example app values.nginxHelmJenkinsValuesYaml
            // nginxHelmJenkinsValuesYaml['extraVolumes']
            
            // TODO remove external-secrets from /helm-jenkins
            
            // Secrets folder in controlApp is not needed
            new File(controlAppTmpDir.absolutePath + '/' + 'secrets').deleteDir()
        }
        
        if (!config.features['monitoring']['active']) {
            // Monitoring folder in controlApp is not needed
            new File(controlAppTmpDir.absolutePath + '/' + 'monitoring').deleteDir()
        }
        
        if (!config.application['remote']) {
            log.debug("Setting service.type to NodePort since it is not running in a remote cluster for nginx-helm-jenkins")
            MapUtils.deepMerge(
                    [ service: [
                            type: 'NodePort'
                            ]
                    ],nginxHelmJenkinsValuesYaml)
        }

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in control app to use the external scmm url: ${externalScmmUrl}")
            fileSystemUtils.getAllFilesFromDirectoryWithEnding(controlAppTmpDir.absolutePath, ".yaml").forEach(file -> {
                fileSystemUtils.replaceFileContent(file.absolutePath, 
                        "http://scmm-scm-manager.default.svc.cluster.local/scm", externalScmmUrl)
            })
        }

        log.trace("nginx-helm-jenkins values yaml: ${nginxHelmJenkinsValuesYaml}")
        fileSystemUtils.writeYaml(nginxHelmJenkinsValuesYaml, nginxHelmJenkinsValuesTmpFile.toFile())
        
        gitRepos.forEach( repo -> {
            repo.commitAndPush()
        })
    }

    protected ScmmRepo createRepo(String localSrcDir, String scmmRepoTarget, File absoluteLocalRepoTmpDir) {
        new ScmmRepo(config, localSrcDir, scmmRepoTarget, absoluteLocalRepoTmpDir.absolutePath)
    }
}
