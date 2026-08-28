package com.cloudogu.gitops.infrastructure.git.providers.gitlab;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.GitlabConfig;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.cloudogu.gitops.utils.Tuple;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.GroupApi;
import org.gitlab4j.api.models.AccessLevel;
import org.gitlab4j.api.models.Group;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.Visibility;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Pattern;

@Slf4j
public class GitlabProvider implements GitProvider {

	// GitLab API paths always use '/', regardless of the host OS
	private static final String PATH_SEPARATOR = "/";
	private static final String NOT_FOUND_SUFFIX = "' not found";
	private static final Pattern NUMERIC = Pattern.compile("\\d+");
	private static final Pattern LEADING_SLASHES = Pattern.compile("^/+");
	private static final int HTTP_BAD_REQUEST = 400;
	private static final int HTTP_CONFLICT = 409;
	private static final int HTTP_NOT_FOUND = 404;

	private final String namePrefix;
	private final GitLabApi api;
	private final GitlabConfig gitlabConfig;
	private Group parentGroupCache;

	public GitlabProvider(GitlabConfig gitlabConfig, String namePrefix) {
		this.gitlabConfig = gitlabConfig;
		this.namePrefix = namePrefix;

		String url = Objects.requireNonNull(gitlabConfig.getUrl(), "Missing gitlab url in config.scm.gitlab.url")
							.trim();
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

	@Override
	public boolean createRepository(String repoTarget, String description, boolean initialize) {
		Tuple<String, String> target = GitProvider.splitRepoTarget(repoTarget);
		String repoNamespace = target.getFirst();
		String repoName = target.getSecond();

		Group parent = parentGroup();
		String repoNamespacePath = repoNamespace.toLowerCase(Locale.ROOT);
		String projectPath = repoName.toLowerCase(Locale.ROOT);

		String fullProjectPath = parent.getFullPath() + PATH_SEPARATOR + repoNamespacePath + PATH_SEPARATOR + projectPath;

		if (findProject(fullProjectPath).isPresent()) {
			log.info("GitLab project already exists: " + fullProjectPath);
			return false;
		}

		long subgroupId = ensureSubgroupUnderParentId(parent, repoNamespacePath);
		Project project = new Project().withName(repoName)
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
				Group group = api.getGroupApi()
								 .getGroups(principal)
								 .stream()
								 .filter(candidateGroup -> principal.equals(candidateGroup.getFullPath()) || principal.equals(
									 candidateGroup.getPath()) || principal.equals(candidateGroup.getName()))
								 .findFirst()
								 .orElseThrow(() -> new IllegalArgumentException("Group '" + principal + NOT_FOUND_SUFFIX));
				api.getProjectApi().shareProject(project.getId(), group.getId(), level, null);
			} else {
				org.gitlab4j.api.models.User user = api.getUserApi()
													   .findUsers(principal)
													   .stream()
													   .filter(candidateUser -> principal.equals(candidateUser.getUsername()) || principal.equals(
														   candidateUser.getEmail()))
													   .findFirst()
													   .orElseThrow(() -> new IllegalArgumentException("User '" + principal + NOT_FOUND_SUFFIX));
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
		String prefix = (namePrefix != null ? namePrefix : "").strip();
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
	 * Prometheus integration is only required for SCM-Manager. GitLab provides its own built-in
	 * Prometheus metrics, so we don't expose an endpoint here.
	 */
	@Override
	public URI prometheusMetricsEndpoint() {
		return null;
	}

	private Group parentGroup() {
		if (parentGroupCache != null) {
			return parentGroupCache;
		}

		String raw = gitlabConfig.getParentGroupId();
		if (raw != null) {
			raw = raw.trim();
		}
		if (raw == null || raw.isEmpty()) {
			throw new IllegalArgumentException("--gitlab-group-id is required");
		}

		boolean isNumeric = NUMERIC.matcher(raw).matches();

		try {
			GroupApi groupApi = api.getGroupApi();
			parentGroupCache = isNumeric ? groupApi.getGroup(Long.parseLong(raw)) : groupApi.getGroup(LEADING_SLASHES.matcher(
																														 raw)
																													 .replaceFirst(
																														 ""));
			return parentGroupCache;
		} catch (GitLabApiException e) {
			throw new RuntimeException("Failed to get parent group: " + raw, e);
		}
	}

	private String parentFullPath() {
		return parentGroup().getFullPath();
	}

	/**
	 * Ensure a single-level subgroup exists under 'parent'; return its namespace (group) ID.
	 */
	private long ensureSubgroupUnderParentId(Group parent, String segPath) {
		Group existing = findDirectSubgroupByPath(parent.getId(), segPath);
		if (existing != null) {
			return existing.getId();
		}

		Project collision = findDirectProjectByPath(parent.getId(), segPath);
		if (collision != null) {
			throw new IllegalStateException("Cannot create subgroup '" + segPath + "' under '" + parent.getFullPath() + "': " + "a project with that path already exists at '" + parent.getFullPath() + "/" + segPath + "'. " + "Rename/transfer the project first or choose a different subgroup name.");
		}

		Group toCreate = new Group().withName(segPath).withPath(segPath).withParentId(parent.getId());

		try {
			Group created = api.getGroupApi().addGroup(toCreate);
			log.info("Created group {}", created.getFullPath());
			return created.getId();
		} catch (GitLabApiException e) {
			if (e.getHttpStatus() == HTTP_BAD_REQUEST || e.getHttpStatus() == HTTP_CONFLICT) {
				Group retry = findDirectSubgroupByPath(parent.getId(), segPath);
				if (retry != null) {
					return retry.getId();
				}
			}
			Map<String, List<String>> ve = e.hasValidationErrors() ? e.getValidationErrors() : null;
			log.error(
				"addGroup failed (parent={}, segPath={}, status={}, message={}, validationErrors={})",
				parent.getFullPath(),
				segPath,
				e.getHttpStatus(),
				e.getMessage(),
				ve
			);
			throw new RuntimeException("Failed to add GitLab group", e);
		}
	}

	/**
	 * Find a direct subgroup of 'parentId' with the exact path .
	 */
	private Group findDirectSubgroupByPath(Long parentId, String segPath) {
		try {
			List<Group> subGroups = api.getGroupApi().getSubGroups(parentId);
			if (subGroups == null) {
				return null;
			}
			return subGroups.stream().filter(subGroup -> segPath.equals(subGroup.getPath())).findFirst().orElse(null);
		} catch (GitLabApiException e) {
			throw new RuntimeException("Failed to list subgroups of group " + parentId, e);
		}
	}

	/**
	 * Find a direct project of 'parentId' with the exact path .
	 */
	private Project findDirectProjectByPath(Long parentId, String path) {
		try {
			List<Project> projects = api.getGroupApi().getProjects(parentId);
			if (projects == null) {
				return null;
			}
			return projects.stream().filter(project -> path.equals(project.getPath())).findFirst().orElse(null);
		} catch (GitLabApiException e) {
			throw new RuntimeException("Failed to list projects of group " + parentId, e);
		}
	}

	// ---- Helpers ----
	private Optional<Project> findProject(String fullPath) {
		try {
			return Optional.ofNullable(api.getProjectApi().getProject(fullPath));
		} catch (GitLabApiException e) {
			if (e.getHttpStatus() == HTTP_NOT_FOUND) {
				return Optional.empty();
			}
			throw new RuntimeException("Failed to look up GitLab project: " + fullPath, e);
		}
	}

	private Project findProjectOrThrow(String fullPath) {
		return findProject(fullPath).orElseThrow(() -> new IllegalStateException("GitLab project '" + fullPath + NOT_FOUND_SUFFIX));
	}

	private String resolveFullPath(String repoTarget) {
		if (gitlabConfig.getParentGroupId() == null) {
			throw new IllegalStateException("gitlab.parentGroup is not set");
		}
		Tuple<String, String> target = GitProvider.splitRepoTarget(repoTarget);
		return parentGroup().getFullPath() + "/" + target.getFirst().toLowerCase(Locale.ROOT) + "/" + target.getSecond()
																											.toLowerCase(
																												Locale.ROOT);
	}

	private static Visibility toVisibility(String s) {
		if (s == null) {
			s = "private";
		}
		return switch (s.toLowerCase(Locale.ROOT)) {
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
