package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import com.cloudogu.gitops.dependencyinjection.RetrofitFactory
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.scmm.api.GitLabGroup
import com.cloudogu.gitops.scmm.api.GitLabMember
import com.cloudogu.gitops.scmm.api.GitLabProject
import com.cloudogu.gitops.scmm.api.GitlabApi
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Provider
import jakarta.inject.Singleton
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response

@Slf4j
@Singleton
@Order(60)
class ScmManager extends Feature {

    static final String HELM_VALUES_PATH = "scm-manager/values.ftl.yaml"

    String namespace = 'default'

    private Config config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private GitlabApi gitlabApi

    ScmManager(
            Config config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            // For now we deploy imperatively using helm to avoid order problems. In future we could deploy via argocd.
            HelmStrategy deployer,
            HttpClientFactory httpClientFactory,
            RetrofitFactory retrofitFactory
    ) {
        this.config = config
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.deployer = deployer

        // Initialize GitLab API using RetrofitFactory
        def insecureSslContextProvider = new Provider<HttpClientFactory.InsecureSslContext>() {
            @Override
            HttpClientFactory.InsecureSslContext get() {
                return httpClientFactory.insecureSslContext()
            }
        }
        def httpClientGitLab = retrofitFactory.gitlabOkHttpClient(httpClientFactory.createLoggingInterceptor(), config, insecureSslContextProvider)
        def retrofitGitLab = retrofitFactory.gitlabRetrofit(config, httpClientGitLab)
        this.gitlabApi = retrofitFactory.gitLabApi(retrofitGitLab)
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {

        if (config.scmm.internal) {
            def helmConfig = config.scmm.helm

            def templatedMap = templateToMap(HELM_VALUES_PATH, [
                    host    : config.scmm.ingress,
                    remote  : config.application.remote,
                    username: config.scmm.username,
                    password: config.scmm.password,
                    helm    : config.scmm.helm
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
        }

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
        ])
    }

    void configureGitlab() {
        log.info("Configuring GitLab...")

        def mainGroup = new GitLabGroup("${config.application.namePrefix}scm", null, null)
        mainGroup.setId(28) //TODO Get ID

        def argoCDGroup = getOrCreateGroup(new GitLabGroup("${config.application.namePrefix}scm", null, mainGroup))
        def dependenciesGroup = getOrCreateGroup(new GitLabGroup("3rd-party-dependencies", null, mainGroup))
        def excerisesGroup = getOrCreateGroup(new GitLabGroup("exercises", null, mainGroup))

        argoCDGroup ? createArgoRepos(argoCDGroup) : ""
        excerisesGroup ? createExercices(excerisesGroup) : ""
        dependenciesGroup ? "": "" //will be created over the init-scm script

    }

    void createExercices(GitLabGroup excersisesGroup) {
        def excersisesGroupId = excersisesGroup.id
        createProject("petclinic-helm", "Exercise for Petclinic Helm", excersisesGroupId)
        createProject("nginx-validation", "Exercise nginx-validation", excersisesGroupId)
        createProject("broken-application", "Exercise for broken-application", excersisesGroupId)
    }

    void createArgoRepos(GitLabGroup argoCDGroup) {
        def argoGroupID = argoCDGroup.id
        log.info("GitLab group 'argocd' created or fetched successfully with ID: {}", argoGroupID)
        createProject("nginx-helm-jenkins", "3rd Party app (NGINX) with helm, templated in Jenkins (gitops-build-lib)", argoGroupID)
        createProject("petclinic-plain", "Java app with plain k8s resources", argoGroupID)
        createProject("petclinic-helm", "Java app with custom helm chart", argoGroupID)
        createProject("argocd", "GitOps repo for administration of ArgoCD", argoGroupID)
        createProject("cluster-resources", "GitOps repo for basic cluster-resources", argoGroupID)
        createProject("example-apps", "GitOps repo for examples of end-user applications", argoGroupID)

    }
// Groups
    /**
     * Checks if a GitLab group exists, and if not, creates it.
     *
     * @param groupName the name of the group to check or create
     * @param parentId the ID of the parent group (if any)
     * @return the ID of the existing group if it exists, or the ID of the newly created group
     * -1 means error
     * 0 Group is missing
     *
     */
    GitLabGroup getOrCreateGroup(GitLabGroup group) {
        Integer groupID = getGroupIdIfExists(group, group.parent.name)
        return group ? group.setId(groupID) : createGroup(group, group.parent)
    }

    Integer getGroupIdIfExists(GitLabGroup groupName,String parentName) {
        def groupPath = "${parentName}/${groupName}".toString()
        Call<ResponseBody> call = gitlabApi.getGroupByName(groupPath)
        try {
            Response<ResponseBody> response = call.execute()
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string()
                return extractID(responseBody)

            }
        } catch (Exception e) {
            log.error("Error checking if group exists: {}", e.message)

        }
        return null
    }

    GitLabGroup createGroup(GitLabGroup group, GitLabGroup parent) {
        group.setParent(parent)
        group.setPath(group.getName().toLowerCase().replaceAll(" ", "-"))

        try {
            Call<ResponseBody> call = gitlabApi.createGroup(group)
            Response<ResponseBody> response = call.execute()
            if (response.isSuccessful() && response.body() != null) {
                log.info("GitLab group created: {}", group.name)
                return group.setId(extractID(response.body().string()))
            } else {
                log.error("Failed to create GitLab group: {} - {}", response.code(), response.errorBody()?.string())
            }
        } catch (Exception e) {
            log.error("Error creating GitLab group: {}", e.message)
        }
        return null
    }

//Project

    int createProject(String projectName, String projectDescription, int groupId) {
        def project = new GitLabProject(projectName, groupId)
        project.setDescription(projectDescription)
        Call<ResponseBody> call = gitlabApi.createProject(project)

        try {
            Response<ResponseBody> response = call.execute()
            if (response.isSuccessful() && response.body() != null) {
                log.info("GitLab project created: {}", projectName)
                return extractID(response.body().string())
            } else {
                log.error("Failed to create GitLab project: {} - {}", response.code(), response.errorBody()?.string())
            }
        } catch (Exception e) {
            log.error("Error creating GitLab project: {}", e.message)
        }
        return -1
    }

    void addMember(int projectId, int userId, int accessLevel) {
        def member = new GitLabMember(userId, accessLevel)
        Call<ResponseBody> call = gitlabApi.addProjectMember(projectId, member)

        try {
            Response<ResponseBody> response = call.execute()
            if (response.isSuccessful()) {
                log.info("User {} added to project {} with access level {}", userId, projectId, accessLevel)
            } else {
                log.error("Failed to add user {} to project {}: {} - {}", userId, projectId, response.code(), response.errorBody()?.string())
            }
        } catch (Exception e) {
            log.error("Error adding user to GitLab project: {}", e.message)
        }
    }


    private int extractID(String jsonResponse) {
        def parsed = new JsonSlurper().parseText(jsonResponse) as Map
        return (parsed?.get('id') as Integer) ?: -1
    }
}
