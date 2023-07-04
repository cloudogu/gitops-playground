package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.utils.*
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
class ArgoCD extends Feature {
    static final String HELM_VALUES_PATH = 'argocd/values.yaml'
    static final String CHART_YAML_PATH = 'argocd/Chart.yaml'
    static final String NGINX_HELM_JENKINS_VALUES_PATH = 'k8s/values-shared.yaml'
    static final String NGINX_HELM_DEPENDENCY_VALUES_PATH = 'apps/nginx-helm-umbrella/values.yaml'
    static final String NGINX_VALIDATION_VALUES_PATH = 'k8s/values-shared.yaml'
    static final String BROKEN_APPLICATION_RESOURCES_PATH = 'broken-application.yaml'
    static final String SCMM_URL_INTERNAL = "http://scmm-scm-manager.default.svc.cluster.local/scm"
    static final List<Tuple2> PETCLINIC_REPOS = [
            new Tuple2('applications/argocd/petclinic/plain-k8s', 'argocd/petclinic-plain'),
            new Tuple2('applications/argocd/petclinic/helm', 'argocd/petclinic-helm'),
            new Tuple2('exercises/petclinic-helm', 'exercises/petclinic-helm')
    ]
    
    private Map config
    private List<RepoInitializationAction> gitRepos = []

    private String password
    
    protected File argocdRepoTmpDir
    private RepoInitializationAction argocdRepoInitializationAction
    protected File clusterResourcesTmpDir
    protected File exampleAppsTmpDir
    protected File nginxHelmJenkinsTmpDir
    protected File remotePetClinicRepoTmpDir
    protected File nginxValidationTmpDir
    protected File brokenApplicationTmpDir
    protected List<Tuple2<String, File>> petClinicLocalFoldersAndTmpDirs = []
    
    protected K8sClient k8sClient = new K8sClient()
    protected HelmClient helmClient = new HelmClient()

    private FileSystemUtils fileSystemUtils = new FileSystemUtils()

    ArgoCD(Map config) {
        this.config = config
        
        this.password = config.application["password"]
        
        argocdRepoTmpDir = File.createTempDir('gitops-playground-argocd-repo')
        argocdRepoTmpDir.deleteOnExit()
        argocdRepoInitializationAction = createRepoInitializationAction('argocd/argocd', 'argocd/argocd', argocdRepoTmpDir)
        
        clusterResourcesTmpDir = File.createTempDir('gitops-playground-cluster-resources')
        clusterResourcesTmpDir.deleteOnExit()
        gitRepos += createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources', clusterResourcesTmpDir)

        exampleAppsTmpDir = File.createTempDir('gitops-playground-example-apps')
        exampleAppsTmpDir.deleteOnExit()
        gitRepos += createRepoInitializationAction('argocd/example-apps', 'argocd/example-apps', exampleAppsTmpDir)
        
        nginxHelmJenkinsTmpDir = File.createTempDir('gitops-playground-nginx-helm-jenkins')
        nginxHelmJenkinsTmpDir.deleteOnExit()
        gitRepos += createRepoInitializationAction('applications/argocd/nginx/helm-jenkins', 'argocd/nginx-helm-jenkins',
                nginxHelmJenkinsTmpDir)

        nginxValidationTmpDir = File.createTempDir()
        nginxValidationTmpDir.deleteOnExit()
        gitRepos += createRepoInitializationAction('exercises/nginx-validation', 'exercises/nginx-validation', nginxValidationTmpDir)

        brokenApplicationTmpDir = File.createTempDir()
        brokenApplicationTmpDir.deleteOnExit()
        gitRepos += createRepoInitializationAction('exercises/broken-application', 'exercises/broken-application', brokenApplicationTmpDir)

        remotePetClinicRepoTmpDir = File.createTempDir('gitops-playground-petclinic')
        for (Tuple2 repo : PETCLINIC_REPOS) {
            def petClinicTempDir = File.createTempDir(repo.v2.toString().replace('/', '-'))
            petClinicTempDir.deleteOnExit()
            petClinicLocalFoldersAndTmpDirs.add(new Tuple2(repo.v1.toString(), petClinicTempDir))
            gitRepos += createRepoInitializationAction(remotePetClinicRepoTmpDir.absolutePath, repo.v2.toString(), petClinicTempDir)
        }
    }
    
    @Override
    boolean isEnabled() {
        config.features['argocd']['active']
    }

    @Override
    void enable() {
        cloneRemotePetclinicRepo()
        
        gitRepos.forEach( repoInitializationAction -> {
            repoInitializationAction.initLocalRepo()
        })
        
        prepareGitOpsRepos()

        prepareApplicationNginxHelmJenkins()
        
        preparePetClinicRepos()

        prepareExerciseNginxValidationRepo()
        prepareExerciseBrokenApplicationRepo()

        gitRepos.forEach( repoInitializationAction -> {
            repoInitializationAction.repo.commitAndPush("Initial Commit")
        })

        installArgoCd()
    }

    private void cloneRemotePetclinicRepo() {
        log.debug("Cloning petclinic base repo, revision ${config.repositories['springPetclinic']['ref']}," +
                " from ${config.repositories['springPetclinic']['url']}")
        Git git = gitClone()
                .setURI(config.repositories['springPetclinic']['url'].toString())
                .setDirectory(remotePetClinicRepoTmpDir)
                .call()
        git.checkout().setName(config.repositories['springPetclinic']['ref'].toString()).call()
        log.debug('Finished cloning petclinic base repo')
    }

    /**
     * Overwrite for testing purposes
     */
    protected CloneCommand gitClone() {
        Git.cloneRepository()
    }

    private void prepareGitOpsRepos() {

        if (!config.features['secrets']['active']) {
            log.debug("Deleting unnecessary secrets folder from cluster resources: ${clusterResourcesTmpDir}")
            deleteDir clusterResourcesTmpDir.absolutePath + '/misc/secrets'
        }

        if (!config.features['monitoring']['active']) {
            log.debug("Deleting unnecessary monitoring folder from cluster resources: ${clusterResourcesTmpDir}")
            deleteDir clusterResourcesTmpDir.absolutePath + '/misc/monitoring'
        }

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in gitops repos to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(clusterResourcesTmpDir, SCMM_URL_INTERNAL, externalScmmUrl)
            replaceFileContentInYamls(exampleAppsTmpDir, SCMM_URL_INTERNAL, externalScmmUrl)
        }

        fileSystemUtils.copyDirectory("${fileSystemUtils.rootDir}/applications/argocd/nginx/helm-umbrella",
                Path.of(exampleAppsTmpDir.absolutePath, 'apps/nginx-helm-umbrella/').toString())
        if (!config.application["remote"]) {
            //  Set NodePort service, to avoid "Pending" services and "Processing" state in argo on local cluster
            log.debug("Setting service.type to NodePort since it is not running in a remote cluster for nginx-helm-umbrella")
            def nginxHelmValuesTmpFile = Path.of exampleAppsTmpDir.absolutePath, NGINX_HELM_DEPENDENCY_VALUES_PATH
            Map nginxHelmValuesYaml = fileSystemUtils.readYaml(nginxHelmValuesTmpFile)
            MapUtils.deepMerge([
                    nginx: [
                            service: [
                                    type: 'NodePort'
                            ]
                    ]
            ], nginxHelmValuesYaml)
            log.trace("nginx-helm-umbrella values yaml: ${nginxHelmValuesYaml}")
            fileSystemUtils.writeYaml(nginxHelmValuesYaml, nginxHelmValuesTmpFile.toFile())
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
            MapUtils.deepMerge([
                    service: [
                            type: 'NodePort'
                    ]
            ], nginxHelmJenkinsValuesYaml)
        }

        if (config['images']['nginx']) {
            log.debug("Setting custom nginx image as requested for nginx-helm-jenkins")
            def image = DockerImageParser.parse(config['images']['nginx'] as String)
            MapUtils.deepMerge([
                    image: [
                            registry: image.registry,
                            repository: image.repository,
                            tag: image.tag
                    ]
            ], nginxHelmJenkinsValuesYaml)
        }

        log.trace("nginx-helm-jenkins values yaml: ${nginxHelmJenkinsValuesYaml}")
        fileSystemUtils.writeYaml(nginxHelmJenkinsValuesYaml, nginxHelmJenkinsValuesTmpFile.toFile())
    }

    private void preparePetClinicRepos() {
        for (Tuple2<String, File> repo : petClinicLocalFoldersAndTmpDirs) {
            
            log.debug("Copying playground files for petclinic repo: ${repo.v1}")
            fileSystemUtils.copyDirectory("${fileSystemUtils.rootDir}/${repo.v1}", repo.v2.absolutePath)
            
            log.debug("Replacing gitops-build-lib images for petclinic repo: ${repo.v1}")
            for (Map.Entry image : config.images as Map) {
                fileSystemUtils.replaceFileContent(new File(repo.v2, 'Jenkinsfile').toString(),
                        "${image.key}: .*", "${image.key}: '${image.value}',")
            }

            if (!config.application["remote"]) {
                log.debug("Setting argocd service.type to NodePort since it is not running in a remote cluster, for petclinic repo: ${repo.v1}")
                replaceFileContentInYamls(repo.v2, 'type: LoadBalancer', 'type: NodePort')
            }
        }
    }

    private void prepareExerciseNginxValidationRepo() {
        if (null == config['images']['nginx']) {
            return
        }

        def image = DockerImageParser.parse(config['images']['nginx'] as String)
        def valuesSharedTmpFile = Path.of nginxValidationTmpDir.absolutePath, NGINX_VALIDATION_VALUES_PATH

        def yaml = YAMLMapper.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build()
                .writeValueAsString([
                        image: [
                                registry  : image.registry,
                                repository: image.repository,
                                tag       : image.tag,
                        ]
                ])
        // This file contains broken yaml, therefore we cannot parse it
        valuesSharedTmpFile.setText(valuesSharedTmpFile.text + yaml)
    }

    private void prepareExerciseBrokenApplicationRepo() {
        if (null == config['images']['nginx']) {
            return
        }

        def image = DockerImageParser.parse(config['images']['nginx'] as String)
        def kubernetesResourcesPath = Path.of brokenApplicationTmpDir.absolutePath, BROKEN_APPLICATION_RESOURCES_PATH

        fileSystemUtils.replaceFileContent(kubernetesResourcesPath.toString(), 'bitnami/nginx:1.25.1', "${image.getRegistryAndRepositoryAsString()}:$image.tag")
    }

    private void removeObjectFromList(Object list, String key, String value) {
        boolean successfullyRemoved = (list as List).removeIf(n -> n[key] == value)
        if (! successfullyRemoved) {
            log.warn("Failed to remove object from list. No object found that has property '${key}: ${value}'. List ${list}")
        }
    }

    private void installArgoCd() {
        
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
        k8sClient.applyYaml(Path.of(argocdRepoTmpDir.absolutePath, 'projects/argocd.yaml').toString())
        k8sClient.applyYaml(Path.of(argocdRepoTmpDir.absolutePath, 'applications/bootstrap.yaml').toString())

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', 'argocd', 
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))
    }

    protected void prepareArgoCdRepo() {
        def tmpHelmValues = Path.of(argocdRepoTmpDir.absolutePath, HELM_VALUES_PATH)

        argocdRepoInitializationAction.initLocalRepo()

        if (!config.scmm["internal"]) {
            String externalScmmUrl = ScmmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in argocd repo to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(argocdRepoTmpDir, SCMM_URL_INTERNAL, externalScmmUrl)
        }

        if (!config.application["remote"]) {
            log.debug("Setting argocd service.type to NodePort since it is not running in a remote cluster")
            fileSystemUtils.replaceFileContent(tmpHelmValues.toString(), "LoadBalancer", "NodePort")
        }

        if (config.features["argocd"]["url"]) {
            log.debug("Setting argocd url for notifications")
            fileSystemUtils.replaceFileContent(tmpHelmValues.toString(), 
                    "argocdUrl: https://localhost:9092", "argocdUrl: ${config.features["argocd"]["url"]}")
        }
        
        argocdRepoInitializationAction.repo.commitAndPush("Initial Commit")
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

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmmRepoTarget, File absoluteLocalRepoTmpDir) {
        new RepoInitializationAction(new ScmmRepo(config, scmmRepoTarget, absoluteLocalRepoTmpDir.absolutePath), localSrcDir)
    }

    private void replaceFileContentInYamls(File folder, String from, String to) {
        fileSystemUtils.getAllFilesFromDirectoryWithEnding(folder.absolutePath, ".yaml").forEach(file -> {
            fileSystemUtils.replaceFileContent(file.absolutePath, from, to)
        })
    }

    static class RepoInitializationAction {
        private ScmmRepo repo
        private String copyFromDirectory

        RepoInitializationAction(ScmmRepo repo, String copyFromDirectory) {
            this.repo = repo
            this.copyFromDirectory = copyFromDirectory
        }

        /**
         * Clone repo from SCM and initialize it with default basic files. Afterwards we can edit these files.
         */
        void initLocalRepo() {
            repo.cloneRepo()
            repo.copyDirectoryContents(copyFromDirectory)
        }
    }
}
