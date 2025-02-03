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
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import groovy.util.logging.Slf4j
import jakarta.inject.Provider

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

        if(config.scmm.provider == "gitlab"){
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

    // Should look like configureScmManager in init-scmm.sh
    void configureGitlab() {
        log.info("Configuring GitLab...")

        def groupName = "${config.application.namePrefix}argocd" // Namespace equivalent
        def projectName = "argocd1" //Repo Name
        def projectDescription = "GitOps repo for administration of ArgoCD"
        def userId = 100
        def accessLevel = GitLabMember.AccessLevel.DEVELOPER  // WRITE access equivalent

        // Step 1: Create a GitLab group (if needed)
        int groupId = createGroup("argocd",28)
        if (groupId == -1) {
            log.error("Failed to create or fetch GitLab group: {}", groupName)
            return
        }

        // Step 1: Create a GitLab group (if needed)
        int groupId = createGroup("3rd-party-dependencies",28)
        if (groupId == -1) {
            log.error("Failed to create or fetch GitLab group: {}", groupName)
            return
        }

        // Step 2: Create the project (repository) inside the group
        int projectId = createProject(projectName, projectDescription, groupId)
        if (projectId == -1) {
            log.error("Failed to create GitLab project: {}", projectName)
            return
        }
        addMember(projectId, userId, accessLevel)

        log.info("GitLab configuration completed successfully.")
    }



    int createGroup(String groupName, Integer parentId) {
        def group = new GitLabGroup(groupName, groupName.toLowerCase().replaceAll(" ", "-"), parentId)
        Call<ResponseBody> call = gitlabApi.createGroup(group)

        try {
            Response<ResponseBody> response = call.execute()
            if (response.isSuccessful() && response.body() != null) {
                log.info("GitLab group created: {}", groupName)
                return extractGroupId(response.body().string())
            } else {
                log.error("Failed to create GitLab group: {} - {}", response.code(), response.errorBody()?.string())
            }
        } catch (Exception e) {
            log.error("Error creating GitLab group: {}", e.message)
        }
        return -1
    }

    int createProject(String projectName, String projectDescription, int groupId) {
        def project = new GitLabProject(projectName, groupId)
        project.setDescription(projectDescription)
        Call<ResponseBody> call = gitlabApi.createProject(project)

        try {
            Response<ResponseBody> response = call.execute()
            if (response.isSuccessful() && response.body() != null) {
                log.info("GitLab project created: {}", projectName)
                return extractProjectId(response.body().string())
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

    int fetchGroupId(String groupPath) {
        def response = gitlabApi.getGroupByPath(groupPath).execute()
        if (response.isSuccessful()) {
            return response.body()
        } else {
            log.error("Failed to fetch GitLab group ID for: ${groupPath}")
            return -1
        }
    }

    private int extractGroupId(String jsonResponse) {
        def parsed = new JsonSlurper().parseText(jsonResponse)
        return parsed?.id ?: -1
    }

    private int extractProjectId(String jsonResponse) {
        def parsed = new JsonSlurper().parseText(jsonResponse)
        return parsed?.id ?: -1
    }
}
