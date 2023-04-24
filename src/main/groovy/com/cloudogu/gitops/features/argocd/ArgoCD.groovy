package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
class ArgoCD extends Feature {

    static final String HELM_VALUES_PATH = 'argocd/values.yaml'
    static final String CHART_YAML_PATH = 'argocd/Chart.yaml'
    static final String NGINX_HELM_JENKINS_VALUES_PATH = 'k8s/values-shared.yaml'
    static final String SCMM_URL_INTERNAL = "http://scmm-scm-manager.default.svc.cluster.local/scm"

    private Map config
    private List<ScmmRepo> gitRepos = []

    private String password
    
    protected File argocdRepoTmpDir
    private ScmmRepo argocdRepo
    protected File controlAppTmpDir
    protected File nginxHelmJenkinsTmpDir
    
    protected K8sClient k8sClient = new K8sClient()
    protected HelmClient helmClient = new HelmClient()

    private FileSystemUtils fileSystemUtils = new FileSystemUtils()

    ArgoCD(Map config) {
        this.config = config
        
        this.password = config.application["password"]
        
        argocdRepoTmpDir = File.createTempDir('gitops-playground-argocd-repo')
        argocdRepoTmpDir.deleteOnExit()
        argocdRepo = createRepo('argocd/argocd', 'argocd/argocd', argocdRepoTmpDir)
        
        controlAppTmpDir = File.createTempDir('gitops-playground-control-app')
        controlAppTmpDir.deleteOnExit()
        gitRepos += createRepo('argocd/control-app', 'argocd/control-app', controlAppTmpDir)
        
        nginxHelmJenkinsTmpDir = File.createTempDir('gitops-playground-nginx-helm-jenkins')
        nginxHelmJenkinsTmpDir.deleteOnExit()
        gitRepos += createRepo('applications/argocd/nginx/helm-jenkins', 'argocd/nginx-helm-jenkins',
                nginxHelmJenkinsTmpDir)
        
        gitRepos += createRepo('exercises/nginx-validation', 'exercises/nginx-validation', File.createTempDir())
        gitRepos += createRepo('exercises/broken-application', 'exercises/broken-application', File.createTempDir())
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

        // TODO create gitops repo and init with ArgoCD Applications

        prepareApplicationNginxHelmJenkins()

        gitRepos.forEach( repo -> {
            repo.commitAndPush()
        })

        installArgoCd()
    }

    private void prepareControlApp() {

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
            replaceFileContentInYamls(controlAppTmpDir, SCMM_URL_INTERNAL, externalScmmUrl)
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

    void installArgoCd() {
        
        prepareArgoCdRepo()
        
        log.debug("Creating repo credential secret that is used by argocd to access repos in SCM-Manager")
        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo 
        def repoTemplateSecretName = 'argocd-repo-creds-scmm'
        String scmmUrlForArgoCD = config.scmm["internal"] ? SCMM_URL_INTERNAL : ScmmRepo.createScmmUrl(config)
        k8sClient.createSecret('generic', repoTemplateSecretName, 'argocd',
                new Tuple2('url', scmmUrlForArgoCD),
                new Tuple2('username', 'gitops'),
                new Tuple2('password', password)
        )
        k8sClient.label('secret', repoTemplateSecretName,'argocd',
                new Tuple2(' argocd.argoproj.io/secret-type', 'repo-creds'))

        // Install umbrella chart from folder
        String umbrellaChartPath = Path.of(argocdRepoTmpDir.absolutePath, 'argocd/')
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(
                Path.of(argocdRepoTmpDir.absolutePath, CHART_YAML_PATH))['dependencies'] 
        helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
        helmClient.dependencyBuild(umbrellaChartPath)
        helmClient.upgrade('argocd', umbrellaChartPath, [namespace: 'argocd'])
         
        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of values.yaml, because we don't want it to show in git repo 
        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        k8sClient.patch('secret', 'argocd-secret', 'argocd', 
                [stringData: ['admin.password': bcryptArgoCDPassword ] ])

        // Bootstrap root application
        k8sClient.applyYaml(Path.of(argocdRepoTmpDir.absolutePath, 'projects/argo-project.yaml').toString())
        k8sClient.applyYaml(Path.of(argocdRepoTmpDir.absolutePath, 'applications/root-app.yaml').toString())

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', 'argocd', 
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))
    }

    protected void prepareArgoCdRepo() {
        def tmpHelmValues = Path.of(argocdRepoTmpDir.absolutePath, HELM_VALUES_PATH)
        def tmpHelmValuesFolder = tmpHelmValues.parent.toString()
        def tmpHelmValuesFile = tmpHelmValues.fileName.toString()

        argocdRepo.cloneRepo()

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in argocd repo to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(argocdRepoTmpDir, SCMM_URL_INTERNAL, externalScmmUrl)
        }

        if (!config.application["remote"]) {
            log.debug("Setting argocd service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpHelmValuesFolder, tmpHelmValuesFile,
                    "LoadBalancer", "NodePort")
        }
        argocdRepo.commitAndPush()
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

    void replaceFileContentInYamls(File folder, String from, String to) {
        fileSystemUtils.getAllFilesFromDirectoryWithEnding(folder.absolutePath, ".yaml").forEach(file -> {
            fileSystemUtils.replaceFileContent(file.absolutePath, from, to)
        })
    }
}
