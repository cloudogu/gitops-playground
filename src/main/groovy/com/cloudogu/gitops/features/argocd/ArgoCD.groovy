package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.ScmmRepo
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class ArgoCD extends Feature {

   static final String NGINX_HELM_JENKINS_VALUES_PATH = 'k8s/values-shared.yaml'
    
    private Map config
    private List<ScmmRepo> gitRepos = []

    protected File controlAppTmpDir
    protected File nginxHelmJenkinsTmpDir
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
        
        prepareControlApp()
        
        prepareApplicationNginxHelmJenkins()

        if (config.features['secrets']['active']) {
            k8sClient.createSecret('generic', 'vault-token', 'argocd-production', 
                    new Tuple2('token', config['application']['username']))
            k8sClient.createSecret('generic', 'vault-token', 'argocd-staging', 
                    new Tuple2('token', config['application']['username']))
        } 
        
        gitRepos.forEach( repo -> {
            repo.commitAndPush()
        })
    }

    private void prepareControlApp() {

        new ArgoCDNotifications(config, controlAppTmpDir.absolutePath).install()

        if (!config.features['secrets']['active']) {
            // Secrets folder in controlApp is not needed
            deleteDir controlAppTmpDir.absolutePath + '/secrets'
        }

        if (!config.features['monitoring']['active']) {
            // Monitoring folder in controlApp is not needed
            deleteDir controlAppTmpDir.absolutePath + '/monitoring'
        }

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in control app to use the external scmm url: ${externalScmmUrl}")
            fileSystemUtils.getAllFilesFromDirectoryWithEnding(controlAppTmpDir.absolutePath, ".yaml").forEach(file -> {
                fileSystemUtils.replaceFileContent(file.absolutePath,
                        "http://scmm-scm-manager.default.svc.cluster.local/scm", externalScmmUrl)
            })
        }
    }

    private void prepareApplicationNginxHelmJenkins() {

        def nginxHelmJenkinsValuesTmpFile = Path.of nginxHelmJenkinsTmpDir.absolutePath, NGINX_HELM_JENKINS_VALUES_PATH
        Map nginxHelmJenkinsValuesYaml = fileSystemUtils.readYaml(nginxHelmJenkinsValuesTmpFile)

        if (!config.features['secrets']['active']) {
            removeObjectFromList(nginxHelmJenkinsValuesYaml['extraVolumes'], 'name', 'secret')
            removeObjectFromList(nginxHelmJenkinsValuesYaml['extraVolumeMounts'], 'name', 'secret')

            // External Secrets are not needed in example 
            deleteFile nginxHelmJenkinsTmpDir.absolutePath + '/k8s/staging/external-secret.yaml'
            deleteFile nginxHelmJenkinsTmpDir.absolutePath + '/k8s/production/external-secret.yaml'
        }

        if (!config.application['remote']) {
            log.debug("Setting service.type to NodePort since it is not running in a remote cluster for nginx-helm-jenkins")
            MapUtils.deepMerge(
                    [ service: [
                            type: 'NodePort'
                    ]
                    ],nginxHelmJenkinsValuesYaml)
        }

        log.trace("nginx-helm-jenkins values yaml: ${nginxHelmJenkinsValuesYaml}")
        fileSystemUtils.writeYaml(nginxHelmJenkinsValuesYaml, nginxHelmJenkinsValuesTmpFile.toFile())
    }

    private void removeObjectFromList(Object list, String key, String value) {
        boolean successfullyRemoved = (list as List).removeIf(n -> n[key] == value)
        if (! successfullyRemoved) {
            log.warn("Failed to remove object from list. No object found that has property '${key}: ${value}'. List ${list}")
        }
    }

    private void deleteFile(String path) {
        boolean successfullyDeleted = new File(path).delete()
        if (!successfullyDeleted) {
            log.warn("Faild to delete file ${path}")
        }
    }

    private void deleteDir(String path) {
        boolean successfullyDeleted = new File(path).deleteDir()
        if (!successfullyDeleted) {
            log.warn("Faild to delete dir ${path}")
        }
    }

    protected ScmmRepo createRepo(String localSrcDir, String scmmRepoTarget, File absoluteLocalRepoTmpDir) {
        new ScmmRepo(config, localSrcDir, scmmRepoTarget, absoluteLocalRepoTmpDir.absolutePath)
    }
}
