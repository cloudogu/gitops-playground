package com.cloudogu.gitops.scm

import com.cloudogu.gitops.config.Config
import groovy.util.logging.Slf4j
import jakarta.inject.Named
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.Group
import org.gitlab4j.api.models.Project

import java.util.logging.Level

@Slf4j
class Gitlab implements ISCM {

    private SCMCredentials gitlabCredentials
    private GitLabApi gitlabApi
    private Config config

    Gitlab(@Named("gitlabCredentials") SCMCredentials credentials, Config config) {
        this.config = config
        this.gitlabCredentials = credentials
        this.gitlabApi = new GitLabApi(credentials.url.toString(), credentials.password)
        this.gitlabApi.enableRequestResponseLogging(Level.ALL)
    }

    void init() {
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


    Project createRepo(String name, String description, Group parentGroup) {
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

            project = Optional.ofNullable(this.gitlabApi.projectApi.createProject(projectSpec))
            log.info("Project ${projectSpec} created in Gitlab!")
        }
        removeBranchProtection(project.get())
        return project as Project
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
            log.debug("Unprotected default branch: " + project.getDefaultBranch())
        } catch (Exception ex) {
            log.error("Failed Unprotecting branch for repo ${project}")
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

    @Override
    def createRepo() {
        return null
    }
}