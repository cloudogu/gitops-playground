package com.cloudogu.gitops.scm

import groovy.util.logging.Slf4j
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.Group
import org.gitlab4j.api.models.Project

import java.util.logging.Level

@Slf4j
class Gitlab {

    private SCMCredentials gitlabCredentials
    private GitLabApi gilabApi

    Gitlab(SCMCredentials credentials) {
        this.gitlabCredentials = credentials
        this.gilabApi = new GitLabApi(credentials.url, credentials.password)
        this.gilabApi.enableRequestResponseLogging(Level.ALL)
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
            project = Optional.ofNullable(this.gilabApi.projectApi.createProject(projectSpec))
        }
        removeBranchProtection(project.get())
    }


    void removeBranchProtection(Project project) {
        try {
            this.gilabApi.getProtectedBranchesApi().unprotectBranch(project.getId(), project.getDefaultBranch())
            log.info("Unprotected default branch: " + project.getDefaultBranch())
        } catch (Exception ex) {
            log.error("Failed Unprotecting branch for repo ${project}")
        }
    }


    private Optional<Group> getGroup(String groupName) {
        try {
            return Optional.ofNullable(this.gilabApi.groupApi.getGroup(groupName))
        } catch (Exception e) {
            return Optional.empty()
        }
    }

    private Optional<Group> addGroup(Group group) {
        try {
            return Optional.ofNullable(this.gilabApi.groupApi.addGroup(group))
        } catch (Exception e) {
            return Optional.empty()
        }
    }

    private Optional<Project> getProject(String projectPath) {
        try {
            return Optional.ofNullable(this.gilabApi.projectApi.getProject(projectPath))
        } catch (Exception e) {
            return Optional.empty()
        }
    }

}