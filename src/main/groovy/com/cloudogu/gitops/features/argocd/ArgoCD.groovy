package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.GitClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class ArgoCD extends Feature {

    private static final String NGINX_HELM_JENKINS_VALUES_PATH = 'k8s/values-shared.yaml'
    
    private Map config
    private GitClient git

    private File controlAppTmpDir
    private File nginxHelmJenkinsTmpDir
    private K8sClient k8sClient
    private FileSystemUtils fileSystemUtils

    ArgoCD(Map config, GitClient gitClient = new GitClient(config), FileSystemUtils fileSystemUtils = new FileSystemUtils(),
           K8sClient k8sClient = new K8sClient()) {
        this.config = config
        this.git = gitClient
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient

        controlAppTmpDir = File.createTempDir('gitops-playground-control-app')
        controlAppTmpDir.deleteOnExit()
        nginxHelmJenkinsTmpDir = File.createTempDir('gitops-playground-nginx-helm-jenkins')
        nginxHelmJenkinsTmpDir.deleteOnExit()
    }

    @Override
    boolean isEnabled() {
        config.features['argocd']['active']
    }

    @Override
    void enable() {
        git.clone('argocd/control-app', 'argocd/control-app', controlAppTmpDir.absolutePath)
        git.clone('applications/nginx/argocd/helm-jenkins', 'argocd/nginx-helm-jenkins', 
                nginxHelmJenkinsTmpDir.absolutePath)

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
        
        // TODO git client has local folder stored in state API needs to change :(
        git.commitAndPush('argocd/control-app')

        log.trace("nginx-helm-jenkins values yaml: ${nginxHelmJenkinsValuesYaml}")
        fileSystemUtils.writeYaml(nginxHelmJenkinsValuesYaml, nginxHelmJenkinsValuesTmpFile.toFile())
        git.commitAndPush('argocd/nginx-helm-jenkins')
    }
}
