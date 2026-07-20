package com.cloudogu.gitops.infrastructure.git.providers.gitlab;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.GitlabConfig;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.cloudogu.gitops.utils.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;

import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.Group;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.Visibility;

@Slf4j
public class GitlabProvider implements GitProvider {

    private final DeploymentContext context;
    private final GitLabApi api;
    private final GitlabConfig gitlabConfig;

    public GitlabProvider(DeploymentContext context, GitlabConfig gitlabConfig) {
        this.context = context;
        this.gitlabConfig = gitlabConfig;

        String url = Objects.requireNonNull(gitlabConfig.getUrl(), "Missing gitlab url in config.scm.gitlab.url").trim();
        Credentials creds = gitlabConfig.getCredentials();
        String pat = null;
        if (creds != null) {
            pat = creds.getPassword();
        }
        Objects.requireNonNull(pat, "Missing gitlab token");
        pat = pat.trim();

        this.api = new GitLabApi(url, pat);
        this.api.enableRequestResponseLogging(Level.ALL);
    }

    private Config getConfig() {
        return context.getConfig();
    }

    @Override
    public boolean createRepository(String repoTarget, String description, boolean initialize) {
        Tuple<String, String> target = GitProvider.splitRepoTarget(repoTarget);
        String repoNamespace = target.getFirst();
        String repoName = target.getSecond();

        Group parent = parentGroup();
        String repoNamespacePath = repoNamespace.toLowerCase();
        String projectPath = repoName.toLowerCase();

        long subgroupId = ensureSubgroupUnderParentId(parent, repoNamespacePath);
        String fullProjectPath = parent.getFullPath() + "/" + repoNamespacePath + "/" + projectPath;

        if (findProject(fullProjectPath).isPresent()) {
            log.info("GitLab project already exists: " + fullProjectPath);
            return false;
        }

        Project project = new Project()
            .withName(repoName)
            .withPath(projectPath)
            .withDescription(description != null ? description : "")
            .withIssuesEnabled(false)
            .withMergeRequestsEnabled(false)
            .withWikiEnabled(false)
            .withSnippetsEnabled(false)
            .withNamespaceId(subgroupId)
            .withInitializeWithReadme(initialize);
        project.setVisibility(toVisibility(gitlabConfig.getDefaultVisibility()));

        try {
            Project created = api.getProjectApi().createProject(project);
            log.info("Created GitLab project " + created.getPathWithNamespace() + " (id=" + created.getId() + ")");
            return true;
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to create GitLab project", e);
        }
    }

    @Override
    public void setRepositoryPermission(String repoTarget, String principal, AccessRole role, Scope scope) {
        String fullPath = resolveFullPath(repoTarget);
        Project project = findProjectOrThrow(fullPath);
        AccessLevel level = toAccessLevel(role, scope);
        try {
            if (scope == Scope.GROUP) {
                Group group = api.getGroupApi().getGroups(principal).stream()
                    .filter(g -> principal.equals(g.getFullPath()) || principal.equals(g.getPath()) || principal.equals(g.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Group '" + principal + "' not found"));
                api.getProjectApi().shareProject(project.getId(), group.getId(), level, null);
            } else {
                org.gitlab4j.api.models.User user = api.getUserApi().findUsers(principal).stream()
                    .filter(u -> principal.equals(u.getUsername()) || principal.equals(u.getEmail()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("User '" + principal + "' not found"));
                api.getProjectApi().addMember(project.getId(), user.getId(), level);
            }
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to set repository permission", e);
        }
    }

    @Override
    public String repoUrl(String repoTarget, RepoUrlScope scope) {
        String base = gitlabConfig.getUrl().strip();
        return base + "/" + parentFullPath() + "/" + repoTarget + ".git";
    }

    @Override
    public String repoPrefix() {
        String base = gitlabConfig.getUrl().strip();
        String prefix = (getConfig().getApplication().getNamePrefix() != null ? getConfig().getApplication().getNamePrefix() : "").strip();
        return base + "/" + parentFullPath() + "/" + prefix;
    }

    @Override
    public Credentials getCredentials() {
        return this.gitlabConfig.getCredentials();
    }

    @Override
    public String getProtocol() {
        return gitlabConfig.getUrl();
    }

    @Override
    public String getHost() {
        return gitlabConfig.getUrl();
    }

    @Override
    public String getGitOpsUsername() {
        return gitlabConfig.getGitOpsUsername();
    }

    @Override
    public String getUrl() {
        return this.gitlabConfig.getUrl();
    }

    /**
     * Prometheus integration is only required for SCM-Manager.
     * GitLab provides its own built-in Prometheus metrics, so we don't expose an endpoint here.*/
    @Override
    public URI prometheusMetricsEndpoint() {
        return null;
    }

    private Group parentGroup() {
        String raw = gitlabConfig.getParentGroupId();
        if (raw != null) {
            raw = raw.trim();
        }
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("--gitlab-group-id is required");
        }

        boolean isNumeric = raw.matches("\\d+");

        try {
            var groupApi = api.getGroupApi();
            if (isNumeric) {
                return groupApi.getGroup(Long.parseLong(raw));
            } else {
                return groupApi.getGroup(raw.replaceFirst("^/+", ""));
            }
        } catch (GitLabApiException e) {
            throw new RuntimeException("Failed to get parent group: " + raw, e);
        }
    }

    private String parentFullPath() {
        return parentGroup().getFullPath();
    }

    /** Ensure a single-level subgroup exists under 'parent'; return its namespace (group) ID. */
    private long ensureSubgroupUnderParentId(Group parent, String segPath) {
        Group existing = findDirectSubgroupByPath(parent.getId(), segPath);
        if (existing != null) {
            return existing.getId();
        }

        Project collision = findDirectProjectByPath(parent.getId(), segPath);
        if (collision != null) {
            throw new IllegalStateException("Cannot create subgroup '" + segPath + "' under '" + parent.getFullPath() + "': " +
                "a project with that path already exists at '" + parent.getFullPath() + "/" + segPath + "'. " +
                "Rename/transfer the project first or choose a different subgroup name.");
        }

        Group toCreate = new Group()
            .withName(segPath)
            .withPath(segPath)
            .withParentId(parent.getId());

        try {
            Group created = api.getGroupApi().addGroup(toCreate);
            log.info("Created group {}", created.getFullPath());
            return created.getId();
        } catch (GitLabApiException e) {
            if (e.getHttpStatus() == 400 || e.getHttpStatus() == 409) {
                Group retry = findDirectSubgroupByPath(parent.getId(), segPath);
                if (retry != null) {
                    return retry.getId();
                }
            }
            var ve = e.hasValidationErrors() ? e.getValidationErrors() : null;
            log.error("addGroup failed (parent={}, segPath={}, status={}, message={}, validationErrors={})",
                parent.getFullPath(), segPath, e.getHttpStatus(), e.getMessage(), ve);
            throw new RuntimeException(e);
        }
    }

    /** Find a direct subgroup of 'parentId' with the exact path . */
    private Group findDirectSubgroupByPath(Long parentId, String segPath) {
        try {
            List<Group> subGroups = api.getGroupApi().getSubGroups(parentId);
            if (subGroups == null) return null;
            return subGroups.stream()
                .filter(subGroup -> segPath.equals(subGroup.getPath()))
                .findFirst()
                .orElse(null);
        } catch (GitLabApiException e) {
            throw new RuntimeException(e);
        }
    }

    /** Find a direct project of 'parentId' with the exact path . */
    private Project findDirectProjectByPath(Long parentId, String path) {
        try {
            List<Project> projects = api.getGroupApi().getProjects(parentId);
            if (projects == null) return null;
            return projects.stream()
                .filter(project -> path.equals(project.getPath()))
                .findFirst()
                .orElse(null);
        } catch (GitLabApiException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Helpers ----
    private Optional<Project> findProject(String fullPath) {
        try {
            return Optional.ofNullable(api.getProjectApi().getProject(fullPath));
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private Project findProjectOrThrow(String fullPath) {
        return findProject(fullPath).orElseThrow(() ->
            new IllegalStateException("GitLab project '" + fullPath + "' not found")
        );
    }

    private String resolveFullPath(String repoTarget) {
        if (gitlabConfig.getParentGroupId() == null) {
            throw new IllegalStateException("gitlab.parentGroup is not set");
        }
        return gitlabConfig.getParentGroupId() + "/" + repoTarget;
    }

    private static Visibility toVisibility(String s) {
        if (s == null) {
            s = "private";
        }
        return switch (s.toLowerCase()) {
            case "public" -> Visibility.PUBLIC;
            case "internal" -> Visibility.INTERNAL;
            default -> Visibility.PRIVATE;
        };
    }

    // provider-agnostic AccessRole → GitLab AccessLevel
    private static AccessLevel toAccessLevel(AccessRole role, Scope scope) {
        return switch (role) {
            case READ -> AccessLevel.REPORTER;
            case WRITE -> AccessLevel.DEVELOPER;
            case MAINTAIN, ADMIN -> AccessLevel.MAINTAINER;
            case OWNER -> (scope == Scope.GROUP) ? AccessLevel.OWNER : AccessLevel.MAINTAINER;
            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
    }
}
