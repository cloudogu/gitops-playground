package com.cloudogu.gitops.git.providers.gitlab


import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.config.util.GitlabConfig
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.scmmanager.Permission
import groovy.util.logging.Slf4j
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.AccessLevel
import org.gitlab4j.api.models.Group
import org.gitlab4j.api.models.Namespace
import org.gitlab4j.api.models.Project
import org.gitlab4j.api.models.Visibility

@Slf4j
class GitlabProvider implements GitProvider {

    //TODO use gitlab config from Niklas and refactor the values
    private final GitlabConfig gitlabConfig
    private final GitLabApi api

    GitlabProvider(GitlabConfig gitlabConfig) {
        this.gitlabConfig = gitlabConfig
        this.api = new GitLabApi(gitlabConfig.url, gitlabConfig.credentials.password)
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

        api.projectApi.createProject(project)
        log.info("Created GitLab project ${fullPath}")
        return true
    }

    @Override
    String getUrl() {
        return null // TODO get Url
    }


    @Override
    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission) {
        String fullPath = resolveFullPath(repoTarget)
        Project project = findProjectOrThrow(fullPath)
        AccessLevel level = toAccessLevel(role)

        if (groupPermission) {
            def group = api.groupApi.getGroups(principal).find { it.fullPath == principal }
            if (!group) throw new IllegalArgumentException("Group '${principal}' not found")
            api.projectApi.shareProject(project.id, group.id, level, null)
        } else {
            def user = api.userApi.findUsers(principal).find { it.username == principal || it.email == principal }
            if (!user) throw new IllegalArgumentException("User '${principal}' not found")
            api.projectApi.addMember(project.id, user.id, level)
        }
    }

    @Override
    String computePushUrl(String repoTarget) {
        return ""
    }

    @Override
    Credentials getCredentials() {
        return this.gitlabConfig.credentials
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

        def candidates = api.namespaceApi.findNamespaces(namespacePath)
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
     *      {@code name} = the current segment
     *      {@code path} = the current segment lowercased
     *      {@code parentId} = the previously resolved/created parent (if any)
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
            def candidates = api.groupApi.getGroups(accumulativePathSegment)
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

            parent = api.groupApi.addGroup(group)
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

    // mapping of permission role(READ, WRITE, OWNER) to gitlab specific access level
    private static AccessLevel toAccessLevel(Permission.Role role) {
        switch (role) {
            case Permission.Role.READ:   return AccessLevel.REPORTER   // read-only
            case Permission.Role.WRITE:  return AccessLevel.MAINTAINER // push/merge etc.
            case Permission.Role.OWNER:  return AccessLevel.OWNER      // full rights
            default:
                throw new IllegalArgumentException("Unknown role: ${role}")
        }
    }
}