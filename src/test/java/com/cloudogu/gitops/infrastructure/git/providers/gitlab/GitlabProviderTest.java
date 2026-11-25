package com.cloudogu.gitops.infrastructure.git.providers.gitlab;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.GitlabConfig;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitlabProviderTest {

	private static final String API = "/api/v4";
	private static final String TOKEN = "glpat-secret";

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
															   .options(wireMockConfig().dynamicPort())
															   .build();

	private final GitlabConfig config = mock(GitlabConfig.class);

	@Test
	void createsSubgroupAndProjectWhenNeitherExists() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(aResponse().withStatus(404)));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42/subgroups")).willReturn(json("[]")));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42/projects")).willReturn(json("[]")));
		wireMock.stubFor(post(urlPathEqualTo(API + "/groups")).willReturn(json("{\"id\":7,\"full_path\":\"parent/ns\"}")));
		wireMock.stubFor(post(urlPathEqualTo(API + "/projects")).willReturn(json("{\"id\":99,\"path_with_namespace\":\"parent/ns/repo\"}")));

		boolean created = provider("42").createRepository("NS/Repo", "a description", true);

		assertThat(created).isTrue();
		wireMock.verify(1, postRequestedFor(urlPathEqualTo(API + "/groups"))
			.withRequestBody(containing("path=ns"))
			.withRequestBody(containing("parent_id=42")));
		wireMock.verify(1, postRequestedFor(urlPathEqualTo(API + "/projects"))
			.withRequestBody(containing("name=Repo"))
			.withRequestBody(containing("path=repo"))
			.withRequestBody(containing("namespace_id=7"))
			.withRequestBody(containing("visibility=private"))
			.withRequestBody(containing("initialize_with_readme=true")));
	}

	@Test
	void sendsTokenAsPrivateTokenHeader() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(json("{\"id\":99}")));

		provider("42").createRepository("ns/repo", "", true);

		wireMock.verify(getRequestedFor(urlPathEqualTo(API + "/groups/42")).withHeader("PRIVATE-TOKEN", equalTo(TOKEN)));
	}

	@Test
	void doesNotCreateAnythingWhenProjectAlreadyExists() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(json("{\"id\":99}")));

		boolean created = provider("42").createRepository("ns/repo", "", true);

		assertThat(created).isFalse();
		wireMock.verify(0, postRequestedFor(urlPathEqualTo(API + "/projects")));
		wireMock.verify(0, postRequestedFor(urlPathEqualTo(API + "/groups")));
	}

	@Test
	void reusesExistingSubgroup() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(aResponse().withStatus(404)));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42/subgroups")).willReturn(json("[{\"id\":7,\"path\":\"ns\"}]")));
		wireMock.stubFor(post(urlPathEqualTo(API + "/projects")).willReturn(json("{\"id\":99,\"path_with_namespace\":\"parent/ns/repo\"}")));

		provider("42").createRepository("ns/repo", null, false);

		wireMock.verify(0, postRequestedFor(urlPathEqualTo(API + "/groups")));
		wireMock.verify(1, postRequestedFor(urlPathEqualTo(API + "/projects"))
			.withRequestBody(containing("namespace_id=7")));
	}

	@Test
	void failsWhenProjectBlocksSubgroupPath() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(aResponse().withStatus(404)));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42/subgroups")).willReturn(json("[]")));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42/projects")).willReturn(json("[{\"id\":5,\"path\":\"ns\"}]")));

		assertThatThrownBy(() -> provider("42").createRepository("ns/repo", "", true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("a project with that path already exists");
	}

	@Test
	void wrapsApiErrorWhenCreatingProjectFails() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(aResponse().withStatus(404)));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42/subgroups")).willReturn(json("[{\"id\":7,\"path\":\"ns\"}]")));
		wireMock.stubFor(post(urlPathEqualTo(API + "/projects")).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> provider("42").createRepository("ns/repo", "", true))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("Failed to create GitLab project");
	}

	@Test
	void resolvesParentGroupByPathWhenIdIsNotNumeric() {
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/mygroup%2Fsub"))
			.willReturn(json("{\"id\":42,\"full_path\":\"mygroup/sub\"}")));
		when(config.getUrl()).thenReturn(wireMock.baseUrl());
		when(config.getParentGroupId()).thenReturn("/mygroup/sub");

		String url = new GitlabProvider(config, credentials(), "").repoUrl("ns/repo", RepoUrlScope.CLIENT);

		assertThat(url).isEqualTo(wireMock.baseUrl() + "/mygroup/sub/ns/repo.git");
	}

	@Test
	void failsWithoutParentGroup() {
		assertThatThrownBy(() -> provider(" ").createRepository("ns/repo", "", true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("--gitlab-group-id is required");
	}

	@Test
	void buildsRepoUrlAndPrefixFromParentGroup() {
		stubParentGroup();
		GitlabProvider provider = provider("42", "my-prefix-");

		assertThat(provider.repoUrl("ns/repo", RepoUrlScope.CLIENT)).isEqualTo(wireMock.baseUrl() + "/parent/ns/repo.git");
		assertThat(provider.repoPrefix()).isEqualTo(wireMock.baseUrl() + "/parent/my-prefix-");
	}

	@Test
	void addsUserAsProjectMemberWithMappedAccessLevel() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(json("{\"id\":99}")));
		wireMock.stubFor(get(urlPathEqualTo(API + "/users")).willReturn(json("[{\"id\":3,\"username\":\"other\"},{\"id\":4,\"username\":\"jane\"}]")));
		wireMock.stubFor(post(urlPathEqualTo(API + "/projects/99/members")).willReturn(json("{\"id\":4}")));

		provider("42").setRepositoryPermission("ns/repo", "jane", AccessRole.WRITE, Scope.USER);

		wireMock.verify(1, postRequestedFor(urlPathEqualTo(API + "/projects/99/members"))
			.withRequestBody(containing("user_id=4"))
			.withRequestBody(containing("access_level=30")));
	}

	@Test
	void sharesProjectWithGroupWithMappedAccessLevel() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(json("{\"id\":99}")));
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups")).willReturn(json("[{\"id\":5,\"name\":\"Devs\",\"path\":\"devs\",\"full_path\":\"devs\"}]")));
		wireMock.stubFor(post(urlPathEqualTo(API + "/projects/99/share")).willReturn(json("{\"id\":1}")));

		provider("42").setRepositoryPermission("ns/repo", "devs", AccessRole.OWNER, Scope.GROUP);

		wireMock.verify(1, postRequestedFor(urlPathEqualTo(API + "/projects/99/share"))
			.withRequestBody(containing("group_id=5"))
			.withRequestBody(containing("group_access=50")));
	}

	@Test
	void failsWhenPermissionPrincipalIsUnknown() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(json("{\"id\":99}")));
		wireMock.stubFor(get(urlPathEqualTo(API + "/users")).willReturn(json("[]")));

		assertThatThrownBy(() -> provider("42").setRepositoryPermission("ns/repo", "ghost", AccessRole.READ, Scope.USER))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("User 'ghost' not found");
	}

	@Test
	void failsWhenProjectForPermissionIsMissing() {
		stubParentGroup();
		wireMock.stubFor(get(urlPathEqualTo(API + "/projects/parent%2Fns%2Frepo")).willReturn(aResponse().withStatus(404)));

		assertThatThrownBy(() -> provider("42").setRepositoryPermission("ns/repo", "jane", AccessRole.READ, Scope.USER))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("GitLab project 'parent/ns/repo' not found");
	}

	private GitlabProvider provider(String parentGroupId) {
		return provider(parentGroupId, "");
	}

	private GitlabProvider provider(String parentGroupId, String prefix) {
		when(config.getUrl()).thenReturn(wireMock.baseUrl());
		when(config.getParentGroupId()).thenReturn(parentGroupId);
		return new GitlabProvider(config, credentials(), prefix);
	}

	private void stubParentGroup() {
		wireMock.stubFor(get(urlPathEqualTo(API + "/groups/42")).willReturn(json("{\"id\":42,\"full_path\":\"parent\"}")));
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withHeader("Content-Type", "application/json").withBody(body);
	}

	private static Credentials credentials() {
		return new Credentials("oauth2", TOKEN);
	}
}
