package com.cloudogu.gitops.infrastructure.git.providers.scmmanager;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.util.ScmManagerConfig;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.Repository;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.RepositoryApi;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import okhttp3.internal.http.RealResponseBody;
import okio.BufferedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScmManagerProviderTest {

	@Mock
	ScmManagerConfig scmmCfg;
	@Mock
	ScmManagerUrlResolver urls;
	@Mock
	ScmManagerApiClient apiClient;
	@Mock
	RepositoryApi repoApi;
	@Mock
	K8sClient k8s;
	@Mock
	NetworkingUtils net;

	@BeforeEach
	void setup() throws URISyntaxException {
		lenient().when(scmmCfg.getCredentials()).thenReturn(new Credentials("user", "password"));
		lenient().when(scmmCfg.getGitOpsUsername()).thenReturn("gitops-bot");

		lenient().when(urls.inClusterBase()).thenReturn(new URI("http://scmm.ns.svc.cluster.local/scm"));
		lenient().when(urls.inClusterRepoPrefix()).thenReturn("http://scmm.ns.svc.cluster.local/scm/repo/fv40-");
		lenient().when(urls.clientApiBase()).thenReturn(new URI("http://nodeport/scm/api/v2/"));

		lenient().when(apiClient.repositoryApi()).thenReturn(repoApi);
	}

	private ScmManagerProvider newScmManager() throws ReflectiveOperationException {
		ScmManagerProvider scmManager = new ScmManagerProvider(scmmCfg, k8s, net, "fv40-", true, false, "fv40-");
		setField(scmManager, "urls", urls);
		setField(scmManager, "apiClient", apiClient);
		return scmManager;
	}

	private static void setField(ScmManagerProvider scmManager, String fieldName, Object value)
		throws ReflectiveOperationException {
		Field field = ScmManagerProvider.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(scmManager, value);
	}

	private static Call<Void> callReturningSuccess(int code) throws IOException {
		@SuppressWarnings("unchecked")
		Call<Void> call = mock(Call.class);
		when(call.execute()).thenReturn(Response.success(code, null));
		return call;
	}

	private static Call<Void> callReturningError(int code) throws IOException {
		@SuppressWarnings("unchecked")
		Call<Void> call = mock(Call.class);
		RealResponseBody body = new RealResponseBody("ignored", 0, mock(BufferedSource.class));
		when(call.execute()).thenReturn(Response.error(code, body));
		return call;
	}

	@Test
	void createRepositoryReturnsTrueOn201AndFalseOnSubsequent409ForTheSameRepo()
		throws IOException, ReflectiveOperationException {
		ScmManagerProvider scmManager = newScmManager();

		Call<Void> created = callReturningSuccess(201);
		Call<Void> conflict = callReturningError(409);
		Set<String> seen = new HashSet<>();

		when(repoApi.create(any(Repository.class), anyBoolean()))
			.thenAnswer(inv -> {
				Repository repository = inv.getArgument(0);
				if (seen.contains(repository.getFullRepoName())) {
					return conflict;
				}

				seen.add(repository.getFullRepoName());
				return created;
			});

		assertTrue(scmManager.createRepository("team/demo", "Demo repo", true));
		assertFalse(scmManager.createRepository("team/demo", "Demo repo", true));
		assertTrue(scmManager.createRepository("team/other", null, false));

		verify(repoApi, times(3)).create(any(Repository.class), anyBoolean());
	}

	@Test
	void setRepositoryPermissionMapsMaintainToWriteAndHandles201409()
		throws IOException, ReflectiveOperationException {
		ScmManagerProvider scmManager = newScmManager();

		Call<Void> created = callReturningSuccess(201);
		Call<Void> conflict = callReturningError(409);
		Set<String> seen = new HashSet<>();

		when(repoApi.createPermission(anyString(), anyString(), any(Permission.class)))
			.thenAnswer(inv -> {
				String namespace = inv.getArgument(0);
				String repoName = inv.getArgument(1);
				String key = namespace + "/" + repoName;

				if (seen.contains(key)) {
					return conflict;
				}

				seen.add(key);
				return created;
			});

		assertDoesNotThrow(() ->
			scmManager.setRepositoryPermission("namespace/repo1", "devs", AccessRole.MAINTAIN, Scope.GROUP)
		);

		assertDoesNotThrow(() ->
			scmManager.setRepositoryPermission("namespace/repo1", "devs", AccessRole.MAINTAIN, Scope.GROUP)
		);

		verify(repoApi, atLeastOnce()).createPermission(
			eq("namespace"),
			eq("repo1"),
			argThat(permission -> permission.groupPermission() && permission.role() == Permission.Role.WRITE)
		);
	}

	@Test
	void urlRepoPrefixRepoUrlVariantsProtocolAndHostComeFromUrlResolver() throws ReflectiveOperationException {
		when(urls.inClusterRepoUrl(anyString()))
			.thenAnswer(answer -> "http://scmm.ns.svc.cluster.local/scm/repo/" + answer.getArgument(0));
		when(urls.clientRepoUrl(anyString()))
			.thenAnswer(answer -> "http://nodeport/scm/repo/" + answer.getArgument(0));

		ScmManagerProvider scmManager = newScmManager();

		assertEquals("http://scmm.ns.svc.cluster.local/scm", scmManager.getUrl());
		assertEquals("http://scmm.ns.svc.cluster.local/scm/repo/fv40-", scmManager.repoPrefix());

		assertEquals(
			"http://scmm.ns.svc.cluster.local/scm/repo/team/app",
			scmManager.repoUrl("team/app", RepoUrlScope.IN_CLUSTER)
		);
		assertEquals(
			"http://nodeport/scm/repo/team/app",
			scmManager.repoUrl("team/app", RepoUrlScope.CLIENT)
		);

		assertEquals("http", scmManager.getProtocol());
		assertEquals("scmm.ns.svc.cluster.local", scmManager.getHost());
	}

	@Test
	void prometheusMetricsEndpointIsDelegatedToUrlResolver() throws URISyntaxException, ReflectiveOperationException {
		when(urls.prometheusEndpoint()).thenReturn(new URI("http://nodeport/scm/api/v2/metrics/prometheus"));

		ScmManagerProvider scmManager = newScmManager();

		assertEquals(
			new URI("http://nodeport/scm/api/v2/metrics/prometheus"),
			scmManager.prometheusMetricsEndpoint()
		);
	}

	@Test
	void credentialsAndGitOpsUsernameComeFromScmManagerConfig() throws ReflectiveOperationException {
		ScmManagerProvider scmManager = newScmManager();

		assertEquals("user", scmManager.getCredentials().getUsername());
		assertEquals("password", scmManager.getCredentials().getPassword());
		assertEquals("gitops-bot", scmManager.getGitOpsUsername());
	}
}
