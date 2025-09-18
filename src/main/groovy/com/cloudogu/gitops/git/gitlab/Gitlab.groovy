package com.cloudogu.gitops.git.gitlab

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.git.GitProvider
import com.cloudogu.gitops.git.local.GitRepo
import com.cloudogu.gitops.git.scmm.jgit.InsecureCredentialProvider
import groovy.util.logging.Slf4j
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.Group
import org.gitlab4j.api.models.Project

import java.util.logging.Level

@Slf4j
class Gitlab implements GitProvider {

    private GitLabApi gitlabApi
    private Config config

    GitlabConfig gitlabConfig

    Gitlab(Config config, GitlabConfig gitlabConfig) {
        this.config = config
        this.gitlabConfig = gitlabConfig
        this.gitlabApi = new GitLabApi(credentials.toString(), credentials.password)
        this.gitlabApi.enableRequestResponseLogging(Level.ALL)
    }

    Group createGroup(String groupName, String mainGroupName = '') {
        Group group = this.gitlabApi.groupApi.getGroup(groupName)
        if (!mainGroupName) {
            def tempGroup = new Group()
                    .withName(mainGroupName)
                    .withPath(mainGroupName.toLowerCase())
                    .withParentId(null)

            return this.gitlabApi.groupApi.addGroup(tempGroup)
        }
        return group
    }

    //TODO
    @Override
    void createRepo(String name, String description) {
        Optional<Project> project = getProject("${this.gitlabConfig.parentGroup}/${name}".toString()) // TODO: fullpath
        if (project.isEmpty()) {
            Project projectSpec = new Project()
                    .withName(name)
                    .withDescription(description)
                    .withIssuesEnabled(true)
                    .withMergeRequestsEnabled(true)
                    .withWikiEnabled(true)
                    .withSnippetsEnabled(true)
                    .withPublic(false)
                    .withNamespaceId(this.gitlabConfig.parentGroup.toLong())
                    .withInitializeWithReadme(true)

            project = Optional.ofNullable(this.gitlabApi.projectApi.createProject(projectSpec))
            log.info("Project ${projectSpec} created in Gitlab!")
        }
        removeBranchProtection(project.get())
    }


//    void setup() {
//        log.info("Creating Gitlab Groups")
//        def mainGroupName = "${config.application.namePrefix}scm".toString()
//        Group mainSCMGroup = this.gitlabApi.groupApi.getGroup(mainGroupName)
//        if (!mainSCMGroup) {
//            def tempGroup = new Group()
//                    .withName(mainGroupName)
//                    .withPath(mainGroupName.toLowerCase())
//                    .withParentId(null)
//
//            mainSCMGroup = this.gitlabApi.groupApi.addGroup(tempGroup)
//        }
//
//        String argoCDGroupName = 'argocd'
//        Optional<Group> argoCDGroup = getGroup("${mainGroupName}/${argoCDGroupName}")
//        if (argoCDGroup.isEmpty()) {
//            def tempGroup = new Group()
//                    .withName(argoCDGroupName)
//                    .withPath(argoCDGroupName.toLowerCase())
//                    .withParentId(mainSCMGroup.id)
//
//            argoCDGroup = addGroup(tempGroup)
//        }
//
//        argoCDGroup.ifPresent(this.&createArgoCDRepos)
//
//        String dependencysGroupName = '3rd-party-dependencies'
//        Optional<Group> dependencysGroup = getGroup("${mainGroupName}/${dependencysGroupName}")
//        if (dependencysGroup.isEmpty()) {
//            def tempGroup = new Group()
//                    .withName(dependencysGroupName)
//                    .withPath(dependencysGroupName.toLowerCase())
//                    .withParentId(mainSCMGroup.id)
//
//            addGroup(tempGroup)
//        }
//
//        String exercisesGroupName = 'exercises'
//        Optional<Group> exercisesGroup = getGroup("${mainGroupName}/${exercisesGroupName}")
//        if (exercisesGroup.isEmpty()) {
//            def tempGroup = new Group()
//                    .withName(exercisesGroupName)
//                    .withPath(exercisesGroupName.toLowerCase())
//                    .withParentId(mainSCMGroup.id)
//
//            exercisesGroup = addGroup(tempGroup)
//        }
//
//        exercisesGroup.ifPresent(this.&createExercisesRepos)
//    }

//    void createRepo(String name, String description) {
//        Optional<Project> project = getProject("${parentGroup.getFullPath()}/${name}".toString())
//        if (project.isEmpty()) {
//            Project projectSpec = new Project()
//                    .withName(name)
//                    .withDescription(description)
//                    .withIssuesEnabled(true)
//                    .withMergeRequestsEnabled(true)
//                    .withWikiEnabled(true)
//                    .withSnippetsEnabled(true)
//                    .withPublic(false)
//                    .withNamespaceId(this.gitlabConfig.parentGroup.toLong())
//                    .withInitializeWithReadme(true)
//
//            project = Optional.ofNullable(this.gitlabApi.projectApi.createProject(projectSpec))
//            log.info("Project ${projectSpec} created in Gitlab!")
//        }
//        removeBranchProtection(project.get())
//    }

    void removeBranchProtection(Project project) {
        try {
            this.gitlabApi.getProtectedBranchesApi().unprotectBranch(project.getId(), project.getDefaultBranch())
            log.debug("Unprotected default branch: " + project.getDefaultBranch())
        } catch (Exception ex) {
            log.error("Failed to unprotect default branch '${project.getDefaultBranch()}' for project '${project.getName()}' (ID: ${project.getId()})", ex)
        }
    }

    private CredentialsProvider getCredentialProvider() {
        def passwordAuthentication = new UsernamePasswordCredentialsProvider("oauth2",)
        if (!config.application.insecure) {
            return passwordAuthentication
        }
        return new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication)
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
    Credentials getCredentials() {
        return this.gitlabConfig.credentials
    }

//TODO
    @Override
    void init() {

    }

    @Override
    Boolean isInternal() {
        return false
    }

    @Override
    String getUrl() {
        //Gitlab is not supporting internal URLs for now.
        return this.url
    }

    @Override
    GitRepo getRepo(String target) {
        return null
    }

//    @Override
//    GitRepo getRepo(String name, String description) {
//       return null
//    }
}