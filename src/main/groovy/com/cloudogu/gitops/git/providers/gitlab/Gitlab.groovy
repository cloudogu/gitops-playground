package com.cloudogu.gitops.git.providers.gitlab

import java.util.logging.Level

import groovy.util.logging.Slf4j

import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.GitLabApiException
import org.gitlab4j.api.models.AccessLevel
import org.gitlab4j.api.models.Group
import org.gitlab4j.api.models.Project
import org.gitlab4j.api.models.Visibility

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope

@Slf4j
class Gitlab implements GitProvider {

    private final Config config
    private final GitLabApi api
    private GitlabConfig gitlabConfig

    Gitlab(Config config, GitlabConfig gitlabConfig) {
        this.config = config
        this.gitlabConfig = gitlabConfig

        String url = Objects.requireNonNull(gitlabConfig.getUrl(), "Missing gitlab url in config.scm.gitlab.url").trim()
        String pat = Objects.requireNonNull(gitlabConfig.getCredentials()?.password, "Missing gitlab token").trim()
        this.api = new GitLabApi(url, pat)
        this.api.enableRequestResponseLogging(Level.ALL)
    }

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        def repoNamespace = repoTarget.split('/', 2)[0]
        def repoName = repoTarget.split('/', 2)[1]

//        def repoNamespacePrefixed = config.application.namePrefix + repoNamespace
        // 1) Resolve parent by numeric ID (do NOT treat the ID as a path!)
        Group parent = parentGroup()
        String repoNamespacePath = repoNamespace.toLowerCase()
        String projectPath = repoName.toLowerCase()

        long subgroupId = ensureSubgroupUnderParentId(parent, repoNamespacePath)
        String fullProjectPath = "${parentFullPath()}/${repoNamespacePath}/${projectPath}"


        if (findProject(fullProjectPath).present) {
            log.info("GitLab project already exists: ${fullProjectPath}")
            return false
        }

        def project = new Project()
                .withName(repoName)
                .withPath(projectPath)
                .withDescription(description ?: "")
                .withIssuesEnabled(false)
                .withMergeRequestsEnabled(false)
                .withWikiEnabled(false)
                .withSnippetsEnabled(false)
                .withNamespaceId(subgroupId)
                .withInitializeWithReadme(initialize)
        project.visibility = toVisibility(gitlabConfig.defaultVisibility)

        def created = api.projectApi.createProject(project)
        log.info("Created GitLab project ${created.getPathWithNamespace()} (id=${created.id})")
        return true
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        String fullPath = resolveFullPath(repoTarget)
        Project project = findProjectOrThrow(fullPath)
        AccessLevel level = toAccessLevel(role, scope)
        if (scope == Scope.GROUP) {
            def group = api.groupApi.getGroups(principal)
                    .find { it.fullPath == principal || it.path == principal || it.name == principal }
            if (!group) throw new IllegalArgumentException("Group '${principal}' not found")
            api.projectApi.shareProject(project.id, group.id, level, null)
        } else {
            def user = api.userApi.findUsers(principal)
                    .find { it.username == principal || it.email == principal }
            if (!user) throw new IllegalArgumentException("User '${principal}' not found")
            api.projectApi.addMember(project.id, user.id, level)
        }
    }

    @Override
    String repoUrl(String repoTarget, RepoUrlScope scope) {
        String base = gitlabConfig.url.strip()
        return "${base}/${parentFullPath()}/${repoTarget}.git"
    }

    @Override
    String repoPrefix() {
        String base = gitlabConfig.url.strip()
        def prefix = (config.application.namePrefix ?: "").strip()
        return "${base}/${parentFullPath()}/${prefix}"

    }


    @Override
    Credentials getCredentials() {
        return this.gitlabConfig.credentials
    }

    @Override
    String getProtocol() {
        return gitlabConfig.url
    }

    String getHost() {
        return gitlabConfig.url
    }

    @Override
    String getGitOpsUsername() {
        return gitlabConfig.gitOpsUsername
    }

    @Override
    String getUrl() {
        return this.gitlabConfig.url
    }


    /**
     * Prometheus integration is only required for SCM-Manager.
     * GitLab provides its own built-in Prometheus metrics, so we don't expose an endpoint here.
     */
    @Override
    URI prometheusMetricsEndpoint() {
        return null
    }

    /**
     * No-op by design. GitLab repository deletion is not managed through this abstraction.
     * Kept for interface compatibility only.
     */
    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {
        // intentionally left blank
    }

    /**
     * No-op by design. User deletion is not supported or handled through this provider.
     * Kept for interface compatibility only.
     */
    @Override
    void deleteUser(String name) {
        // intentionally left blank
    }

    /**
     * No-op by design. Default branch management is not implemented via this abstraction.
     * Kept for interface compatibility only.
     */
    @Override
    void setDefaultBranch(String repoTarget, String branch) {
        // intentionally left blank
    }

    private Group parentGroup() {
        String raw = gitlabConfig?.parentGroupId?.trim()
        if (!raw) throw new IllegalArgumentException("--gitlab-group-id is required")

        boolean isNumeric = raw ==~ /\d+/

        def groupApi = api.getGroupApi()
        if (isNumeric) {
            return groupApi.getGroup(Long.parseLong(raw))
        } else {
            return groupApi.getGroup(raw.replaceAll('^/+', ''))
        }
    }

    private String parentFullPath() {
        parentGroup().fullPath
    }

    /** Ensure a single-level subgroup exists under 'parent'; return its namespace (group) ID. */
    private long ensureSubgroupUnderParentId(Group parent, String segPath) {
        // 1) Already there?
        Group existing = findDirectSubgroupByPath(parent.id as Long, segPath)
        if (existing != null) return existing.id as Long


        // 2) Guard against project/subgroup name collision in the same parent
        Project collision = findDirectProjectByPath(parent.id as Long, segPath)
        if (collision != null) {
            throw new IllegalStateException(
                    "Cannot create subgroup '${segPath}' under '${parent.fullPath}': " +
                            "a project with that path already exists at '${parent.fullPath}/${segPath}'. " +
                            "Rename/transfer the project first or choose a different subgroup name."
            )
        }

        // 3) Create subgroup
        Group toCreate = new Group()
                .withName(segPath)  // display name
                .withPath(segPath)        // (lowercase etc.)
                .withParentId(parent.id)


        try {
            Group created = api.groupApi.addGroup(toCreate)
            log.info("Created group {}", created.fullPath)
            return created.id as Long
        } catch (GitLabApiException e) {
            // If someone created it in parallel, treat 400/409 as "exists" and re-fetch
            if (e.httpStatus in [400, 409]) {
                Group retry = findDirectSubgroupByPath(parent.id as Long, segPath)
                if (retry != null) return retry.id as Long
            }
            def ve = e.hasValidationErrors() ? e.getValidationErrors() : null
            log.error("addGroup failed (parent={}, segPath={}, status={}, message={}, validationErrors={})",
                    parent.fullPath, segPath, e.httpStatus, e.getMessage(), ve)
            throw e
        }
    }


    /** Find a direct subgroup of 'parentId' with the exact path . */
    private Group findDirectSubgroupByPath(Long parentId, String segPath) {
        // uses the overload: getSubGroups(Object idOrPath)
        List<Group> subGroups = api.groupApi.getSubGroups(parentId)
        return subGroups?.find { Group subGroup -> subGroup.path == segPath }
    }


    /** Find a direct project of 'parentId' with the exact path . */
    private Project findDirectProjectByPath(Long parentId, String path) {
        // uses the overload: getProjects(Object idOrPath)
        List<Project> projects = api.groupApi.getProjects(parentId)
        return projects?.find { Project project -> project.path == path }
    }


    // ---- Helpers ----
    private Optional<Project> findProject(String fullPath) {
        try {
            return Optional.ofNullable(api.projectApi.getProject(fullPath))
        } catch (Exception ignore) {
            return Optional.empty()
        }
    }

    private Project findProjectOrThrow(String fullPath) {
        return findProject(fullPath).orElseThrow {
            new IllegalStateException("GitLab project '${fullPath}' not found")
        }
    }

    private String resolveFullPath(String repoTarget) {
        if (!gitlabConfig.parentGroupId) {
            throw new IllegalStateException("gitlab.parentGroup is not set")
        }
        return "${gitlabConfig.parentGroupId}/${repoTarget}"
    }


    private static Visibility toVisibility(String s) {
        switch ((s ?: "private").toLowerCase()) {
            case "public": return Visibility.PUBLIC
            case "internal": return Visibility.INTERNAL
            default: return Visibility.PRIVATE
        }
    }

// provider-agnostic AccessRole → GitLab AccessLevel
    private static AccessLevel toAccessLevel(AccessRole role, Scope scope) {
        switch (role) {
            case AccessRole.READ:
                // GitLab: Guests usually can't read private repo code; Reporter can.
                return AccessLevel.REPORTER
            case AccessRole.WRITE:
                // Typical push/merge permissions
                return AccessLevel.DEVELOPER
            case AccessRole.MAINTAIN:
                return AccessLevel.MAINTAINER
            case AccessRole.ADMIN:
                // No separate project-level "admin" → cap at Maintainer
                return AccessLevel.MAINTAINER
            case AccessRole.OWNER:
                // OWNER is meaningful for groups/namespaces; for users on a project we cap to MAINTAINER
                return (scope == Scope.GROUP) ? AccessLevel.OWNER : AccessLevel.MAINTAINER
            default:
                throw new IllegalArgumentException("Unknown role: ${role}")
        }
    }


    //TODO when git abctraction feature is ready, we will create before merge to main a branch, that
    // contain this code as preservation for oop
    /*   ================================= SETUP CODE ====================================
      void setup() {
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

      void createRepo(String name, String description) {
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
                      .withNamespaceId(this.gitlabConfig.parentGroup.toLong())
                      .withInitializeWithReadme(true)

              project = Optional.ofNullable(this.gitlabApi.projectApi.createProject(projectSpec))
              log.info("Project ${projectSpec} created in Gitlab!")
          }
          removeBranchProtection(project.get())
      }

      void removeBranchProtection(Project project) {
          try {
              this.gitlabApi.getProtectedBranchesApi().unprotectBranch(project.getId(), project.getDefaultBranch())
              log.debug("Unprotected default branch: " + project.getDefaultBranch())
          } catch (Exception ex) {
              log.error("Failed to unprotect default branch '${project.getDefaultBranch()}' for project '${project.getName()}' (ID: ${project.getId()})", ex)
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
  */

}