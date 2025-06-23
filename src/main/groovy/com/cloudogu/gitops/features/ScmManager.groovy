package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.Group
import org.gitlab4j.api.models.Project

import java.util.function.Supplier
import java.util.logging.Level

@Slf4j
@Singleton
@Order(60)
class ScmManager extends Feature {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"

    String namespace = "${config.application.namePrefix}scm-manager"

    private Config config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private GitLabApi gitlabApi
    private K8sClient k8sClient
    private NetworkingUtils networkingUtils

    ScmManager(
            Config config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer,
            K8sClient k8sClient,
            NetworkingUtils networkingUtils
    ) {
        this.config = config
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.deployer = deployer
        this.gitlabApi = new GitLabApi(config.scmm.url, config.scmm.password)
        this.gitlabApi.enableRequestResponseLogging(Level.ALL)
        this.k8sClient = k8sClient
        this.networkingUtils = networkingUtils
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {

        if (config.scmm.internal) {

            String releaseName = 'scmm'

            k8sClient.createNamespace(namespace)

            def helmConfig = config.scmm.helm

            def templatedMap = templateToMap(HELM_VALUES_PATH, [
                    host    : config.scmm.ingress,
                    remote  : config.application.remote,
                    username: config.scmm.username,
                    password: config.scmm.password,
                    helm    : config.scmm.helm,
                    releaseName: releaseName
            ])

            def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
            def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

            deployer.deployFeature(
                    helmConfig.repoURL,
                    'scm-manager',
                    helmConfig.chart,
                    helmConfig.version,
                    namespace,
                    'scmm',
                    tempValuesPath
            )
            
            // Update scmm.url after it is deployed (and ports are known)
            // Defined here: https://github.com/scm-manager/scm-manager/blob/3.2.1/scm-packaging/helm/src/main/chart/templates/_helpers.tpl#L14-L25
            String contentPath = "/scm"
            
            if (config.application.runningInsideK8s) {
                log.debug("Setting scmm url to k8s service, since installation is running inside k8s")
                config.scmm.url = networkingUtils.createUrl("${releaseName}.${namespace}.svc.cluster.local", "80", contentPath)
            } else {
                log.debug("Setting internal configs for local single node cluster with internal scmm. Waiting for NodePort...")
                def port = k8sClient.waitForNodePort(releaseName, namespace)
                String clusterBindAddress = networkingUtils.findClusterBindAddress()
                config.scmm.url = networkingUtils.createUrl(clusterBindAddress, port, contentPath)
            }
        }

        // NOTE: This code is experimental and not intended for production use.
        // Please use with caution and ensure proper testing before deployment.

        if (config.scmm.provider == "gitlab") {
            configureGitlab()
        }

        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/scm-manager/init-scmm.sh", [

                GIT_COMMITTER_NAME           : config.application.gitName,
                GIT_COMMITTER_EMAIL          : config.application.gitEmail,
                GIT_AUTHOR_NAME              : config.application.gitName,
                GIT_AUTHOR_EMAIL             : config.application.gitEmail,
                GITOPS_USERNAME              : config.scmm.gitOpsUsername,
                TRACE                        : config.application.trace,
                SCMM_URL                     : config.scmm.url,
                SCMM_USERNAME                : config.scmm.username,
                SCMM_PASSWORD                : config.scmm.password,
                JENKINS_URL                  : config.jenkins.url,
                INTERNAL_SCMM                : config.scmm.internal,
                JENKINS_URL_FOR_SCMM         : config.jenkins.urlForScmm,
                SCMM_URL_FOR_JENKINS         : config.scmm.urlForJenkins,
                // Used indirectly in utils.sh 😬
                REMOTE_CLUSTER               : config.application.remote,
                INSTALL_ARGOCD               : config.features.argocd.active,
                SPRING_BOOT_HELM_CHART_COMMIT: config.repositories.springBootHelmChart.ref,
                SPRING_BOOT_HELM_CHART_REPO  : config.repositories.springBootHelmChart.url,
                GITOPS_BUILD_LIB_REPO        : config.repositories.gitopsBuildLib.url,
                CES_BUILD_LIB_REPO           : config.repositories.cesBuildLib.url,
                NAME_PREFIX                  : config.application.namePrefix,
                INSECURE                     : config.application.insecure,
                SCM_ROOT_PATH                : config.scmm.rootPath,
                SCM_PROVIDER                 : config.scmm.provider,
                CONTENT_EXAMPLES             : config.content.examples,
                SKIP_RESTART                 : config.scmm.skipRestart,
                SKIP_PLUGINS                 : config.scmm.skipPlugins
        ])
    }

    void configureGitlab() {
        log.info("Gitlab init")

        createGroups()
    }


    void createGroups() {
        log.info("Creating Gitlab Groups")
        def mainGroupName = "${config.application.namePrefix}scm".toString()
        Group mainSCMGroup = this.gitlabApi.groupApi.getGroup(mainGroupName)
        if (!mainSCMGroup) {
            def tempGroup = new Group()
                    .withName(mainGroupName)
                    .withPath(mainGroupName.toLowerCase())
                    .withParentId(null)

            mainSCMGroup = this.gitlabApi.groupApi.addGroup(tempGroup)
        }


        String argoCDGroupName = 'argocd'
        Optional<Group> argoCDGroup = getGroup("${mainGroupName}/${argoCDGroupName}")
        if (argoCDGroup.isEmpty()) {
            def tempGroup = new Group()
                    .withName(argoCDGroupName)
                    .withPath(argoCDGroupName.toLowerCase())
                    .withParentId(mainSCMGroup.id)

            argoCDGroup = addGroup(tempGroup)
        }

        argoCDGroup.ifPresent(this.&createArgoCDRepos)

        String dependencysGroupName = '3rd-party-dependencies'
        Optional<Group> dependencysGroup = getGroup("${mainGroupName}/${dependencysGroupName}")
        if (dependencysGroup.isEmpty()) {
            def tempGroup = new Group()
                    .withName(dependencysGroupName)
                    .withPath(dependencysGroupName.toLowerCase())
                    .withParentId(mainSCMGroup.id)

            addGroup(tempGroup)
        }

        String exercisesGroupName = 'exercises'
        Optional<Group> exercisesGroup = getGroup("${mainGroupName}/${exercisesGroupName}")
        if (exercisesGroup.isEmpty()) {
            def tempGroup = new Group()
                    .withName(exercisesGroupName)
                    .withPath(exercisesGroupName.toLowerCase())
                    .withParentId(mainSCMGroup.id)

            exercisesGroup = addGroup(tempGroup)
        }

        exercisesGroup.ifPresent(this.&createExercisesRepos)
    }

    void createExercisesRepos(Group exercisesGroup) {
        log.info("Creating GitlabRepos for ${exercisesGroup}")
        createRepo("petclinic-helm", "petclinic-helm", exercisesGroup)
        createRepo("nginx-validation", "nginx-validation", exercisesGroup)
        createRepo("broken-application", "broken-application", exercisesGroup)
    }

    void createArgoCDRepos(Group argoCDGroup) {
        log.info("Creating GitlabRepos for ${argoCDGroup}")
        createRepo("cluster-resources", "GitOps repo for basic cluster-resources", argoCDGroup)
        createRepo("petclinic-helm", "Java app with custom helm chart", argoCDGroup)
        createRepo("petclinic-plain", "Java app with plain k8s resources", argoCDGroup)
        createRepo("nginx-helm-jenkins", "3rd Party app (NGINX) with helm, templated in Jenkins (gitops-build-lib)", argoCDGroup)
        createRepo("argocd", "GitOps repo for administration of ArgoCD", argoCDGroup)
        createRepo("example-apps", "GitOps repo for examples of end-user applications", argoCDGroup)

    }


    void removeBranchProtection(Project project) {
        try {
            this.gitlabApi.getProtectedBranchesApi().unprotectBranch(project.getId(), project.getDefaultBranch())
            log.info("Unprotected default branch: " + project.getDefaultBranch())
        } catch (Exception ex) {
            log.error("Failed Unprotecting branch for repo ${project}")
        }
    }


    void createRepo(String name, String description, Group parentGroup) {

        Optional<Project> project = getProject("${parentGroup.getFullPath()}/${name}".toString())
        if (project.isEmpty()) {
            Project projectSpec = new Project()
                    .withName(name)
                    .withDescription(description)
                    .withIssuesEnabled(true)
                    .withMergeRequestsEnabled(true)
                    .withWikiEnabled(true)
                    .withSnippetsEnabled(true)
                    .withPublic(false)
                    .withNamespaceId(parentGroup.getId())
                    .withInitializeWithReadme(true)

            log.info("Project ${projectSpec} created!")
            project = Optional.ofNullable(this.gitlabApi.projectApi.createProject(projectSpec))
        }
        removeBranchProtection(project.get())
    }

    //to bundle the 3 functions down below
    private <T> Optional<T> executeGitlabApiCall(Supplier<T> apiCall) {
        try {
            return Optional.ofNullable(apiCall.get())
        } catch (Exception e) {
            return Optional.empty()
        }
    }

    private Optional<Group> getGroup(String groupName) {
        try {
            return Optional.ofNullable(this.gitlabApi.groupApi.getGroup(groupName))
        } catch (Exception e) {
            return Optional.empty()
        }
    }

    private Optional<Group> addGroup(Group group) {
        try {
            return Optional.ofNullable(this.gitlabApi.groupApi.addGroup(group))
        } catch (Exception e) {
            return Optional.empty()
        }
    }

    private Optional<Project> getProject(String projectPath) {
        try {
            return Optional.ofNullable(this.gitlabApi.projectApi.getProject(projectPath))
        } catch (Exception e) {
            return Optional.empty()
        }
    }
}