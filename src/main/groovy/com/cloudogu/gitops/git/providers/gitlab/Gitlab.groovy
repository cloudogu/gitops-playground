package com.cloudogu.gitops.git.providers.gitlab

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.Scope
import groovy.util.logging.Slf4j
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.*

import java.util.logging.Level

@Slf4j
class Gitlab implements GitProvider {

    private final Config config
    private final GitLabApi gitlabApi
    private GitlabConfig gitlabConfig

    Gitlab(Config config, GitlabConfig gitlabConfig) {
        this.config = config
        this.gitlabConfig = gitlabConfig
        this.gitlabApi = new GitLabApi(credentials.toString(), credentials.password)
        this.gitlabApi.enableRequestResponseLogging(Level.ALL)
    }

    @Override
    boolean createRepository(String repoTarget, String description, boolean initialize) {
        String fullPath = resolveFullPath(repoTarget)

        //check if there is already a project with the same fullPath
        if (findProject(fullPath).present) return false

        long namespaceId = ensureNamespaceId(fullPath)
        def project = new Project()
                .withName(projectName(fullPath))
                .withDescription(description ?: "")
                .withIssuesEnabled(true)
                .withMergeRequestsEnabled(true)
                .withWikiEnabled(true)
                .withSnippetsEnabled(true)
                .withNamespaceId(namespaceId)
                .withInitializeWithReadme(initialize)
        project.visibility = toVisibility(gitlabConfig.defaultVisibility)

        gitlabApi.projectApi.createProject(project)
        log.info("Created GitLab project ${fullPath}")
        return true
    }

    @Override
    void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        String fullPath = resolveFullPath(repoTarget)
        Project project = findProjectOrThrow(fullPath)
        AccessLevel level = toAccessLevel(role, scope)
        if (scope == Scope.GROUP) {
            def group = gitlabApi.groupApi.getGroups(principal)
                    .find { it.fullPath == principal || it.path == principal || it.name == principal }
            if (!group) throw new IllegalArgumentException("Group '${principal}' not found")
            gitlabApi.projectApi.shareProject(project.id, group.id, level, null)
        } else {
            def user = gitlabApi.userApi.findUsers(principal)
                    .find { it.username == principal || it.email == principal }
            if (!user) throw new IllegalArgumentException("User '${principal}' not found")
            gitlabApi.projectApi.addMember(project.id, user.id, level)
        }
    }

    @Override
    String computePushUrl(String repoTarget) {
        return null
    }

    @Override
    String computePullUrlForInCluster(String repoTarget) {
        return null
    }

    @Override
    String computeRepoPrefixForInCluster(boolean includeNamePrefix) {
        return null
    }

    @Override
    Credentials getCredentials() {
        return this.gitlabConfig.credentials
    }

    @Override
    String getProtocol() {
        return null
    }

    String getHost() {
        return null
    }

    @Override
    String getGitOpsUsername() {
        return gitlabConfig.gitOpsUsername
    }

    @Override
    String getUrl() {
        //Gitlab is not supporting internal URLs for now.
        return this.url
    }

    @Override
    URI prometheusMetricsEndpoint() {
        return null
    }

    //TODO implement
    @Override
    void deleteRepository(String namespace, String repository, boolean prefixNamespace) {

    }

    //TODO implement
    @Override
    void deleteUser(String name) {

    }

    //TODO implement
    @Override
    void setDefaultBranch(String repoTarget, String branch) {

    }


    // ---- Helpers ----
    private Optional<Project> findProject(String fullPath) {
        try {
            return Optional.ofNullable(gitlabApi.projectApi.getProject(fullPath))
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
        if (repoTarget?.contains("/")) return repoTarget
        if (!gitlabConfig.parentGroup) {
            throw new IllegalStateException("gitlab.parentGroup is not set")
        }
        return "${gitlabConfig.parentGroup}/${repoTarget}"
    }

    private static String projectName(String fullPath) {
        return fullPath.substring(fullPath.lastIndexOf('/') + 1)
    }

    /**
     * Resolves the namespace (group/subgroup) for the given full project path and returns its namespace ID.
     * <p>
     * The method extracts the namespace portion from {@code fullPath} (everything before the last slash),
     * looks it up via the GitLab API, and:
     *  - returns the existing namespace ID if found
     *  - throws an {@link IllegalStateException} if not found and {@code autoCreateGroups} is {@code false}
     *  - otherwise creates the full group chain and returns the newly created namespace ID.
     *
     * @param fullPath the fully qualified project path, e.g. {@code "group/subgroup/my-project"}; the method
     *                 uses the part before the last slash as the namespace path (e.g. {@code "group/subgroup"}).
     * @return the GitLab namespace ID corresponding to the extracted namespace path
     * @throws IllegalStateException if the namespace does not exist and automatic creation is disabled
     *                               ({@code config.autoCreateGroups == false})
     * @implNote Requires API credentials with sufficient permissions to create groups when
     * {@code config.autoCreateGroups} is {@code true}.
     */
    private long ensureNamespaceId(String fullPath) {
        String namespacePath = fullPath.substring(0, fullPath.lastIndexOf('/'))

        def candidates = gitlabApi.namespaceApi.findNamespaces(namespacePath)
        Namespace namespace = null
        for (Namespace namespaceCandidate : candidates) {
            if (namespacePath == namespaceCandidate.fullPath) {
                namespace = namespaceCandidate
                break
            }
        }
        if (namespace != null) {
            return namespace.id
        }

        if (!gitlabConfig.autoCreateGroups) {
            throw new IllegalStateException("Namespace '${namespacePath}' does not exist (autoCreateGroups=false).")
        }

        return createNamespaceChain(namespacePath).id
    }

    /**
     * Ensures that the full group hierarchy specified by {@code namespacePath} exists in GitLab,
     * creating any missing groups along the way, and returns the deepest (last) group.
     *
     * The method splits {@code namespacePath} by {@code '/'} into path segments (e.g. {@code "group/subgroup"}),
     * iteratively builds the accumulated path (e.g. {@code "group"}, then {@code "group/subgroup"}),
     * and for each level:
     *
     * Checks whether a group with that exact {@code fullPath} already exists.
     *   If it exists, uses it as the parent for the next level.
     *   If it does not exist, creates the group with:
     * {@code name} = the current segment
     * {@code path} = the current segment lowercased
     * {@code parentId} = the previously resolved/created parent (if any)
     *
     * Existing groups are reused, and only missing segments are created.
     * Requires API credentials with permissions to create groups within the target hierarchy.
     *
     * @param namespacePath a slash-separated group path, e.g. {@code "group/subgroup/subsub"}
     * @return the deepest {@link Group} corresponding to the last segment of {@code namespacePath}
     * @implNote Uses {@code groupApi.getGroups(accumulativePathSegment)} to look up existing groups by {@code fullPath} at each level.
     *           The created group's {@code path} is normalized to lowercase; ensure this matches your naming policy.
     * @throws RuntimeException if the underlying GitLab API calls fail (e.g., insufficient permissions or network errors)
     */
    private Group createNamespaceChain(String namespacePath) {
        Group parent = null
        def accumulativePathSegment = ""

        // Split on '/', skip empty segments (leading, double, or trailing '/')
        def segments = namespacePath.split('/')
        for (String pathSegment : segments) {
            if (pathSegment == null || pathSegment.isEmpty()) {
                continue
            }

            // Build "group", then "group/subgroup", then ...
            accumulativePathSegment = accumulativePathSegment.isEmpty()
                    ? pathSegment
                    : accumulativePathSegment + "/" + pathSegment

            // use an explicit for-loop to check each candidate
            def candidates = gitlabApi.groupApi.getGroups(accumulativePathSegment)
            Group existing = null
            for (Group g : candidates) {
                if (accumulativePathSegment == g.fullPath) {
                    existing = g
                    break
                }
            }

            if (existing != null) {
                parent = existing
                continue
            }

            // Create the group if it does not exist
            Group group = new Group()
                    .withName(pathSegment)
                    .withPath(pathSegment.toLowerCase())
                    .withParentId(parent != null ? parent.id : null)

            parent = gitlabApi.groupApi.addGroup(group)
            log.info("Created group {}", accumulativePathSegment)
        }

        return parent
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

