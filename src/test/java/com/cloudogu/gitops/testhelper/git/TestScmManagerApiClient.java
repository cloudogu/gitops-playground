package com.cloudogu.gitops.testhelper.git;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.Permission;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.Repository;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.RepositoryApi;
import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api.ScmManagerApiClient;
import lombok.Getter;
import okhttp3.internal.http.RealResponseBody;
import okio.BufferedSource;
import org.mockito.ArgumentMatchers;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestScmManagerApiClient extends ScmManagerApiClient {

	@Getter
	private final RepositoryApi repositoryApi = mock(RepositoryApi.class);
	private final Set<String> createdRepos = new HashSet<>();
	private final Set<String> createdPermissions = new HashSet<>();

	public TestScmManagerApiClient(Config config) {
		super(
			config.getScm().getScmManager().getUrl(),
			new Credentials(
				config.getScm().getScmManager().getUsername(),
				config.getScm().getScmManager().getPassword()
			),
			null
		);
	}

	@Override
	public RepositoryApi repositoryApi() {
		return repositoryApi;
	}

	/**
	 * Make all repo API calls return created on the first call and exists on subsequent calls for each repo.
	 */
	public void mockRepoApiBehaviour() {
		Call<Void> responseCreated = mockSuccessfulResponse(201);
		Call<Void> responseExists = mockErrorResponse(409);

		when(repositoryApi.create(ArgumentMatchers.any(Repository.class), anyBoolean()))
			.thenAnswer(invocation -> {
				Repository repo = invocation.getArgument(0);
				if (createdRepos.contains(repo.getFullRepoName())) {
					return responseExists;
				}
				createdRepos.add(repo.getFullRepoName());
				return responseCreated;
			});

		when(repositoryApi.createPermission(anyString(), anyString(), ArgumentMatchers.any(Permission.class)))
			.thenAnswer(invocation -> {
				String namespace = invocation.getArgument(0);
				String name = invocation.getArgument(1);
				String repository = namespace + "/" + name;
				if (createdPermissions.contains(repository)) {
					return responseExists;
				}
				createdPermissions.add(repository);
				return responseCreated;
			});
	}

	@SuppressWarnings("unchecked")
	public static Call<Void> mockSuccessfulResponse(int expectedReturnCode) {
		Call<Void> expectedCall = mock(Call.class);
		try {
			when(expectedCall.execute()).thenReturn(Response.success(expectedReturnCode, null));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return expectedCall;
	}

	@SuppressWarnings("unchecked")
	public static Call<Void> mockErrorResponse(int expectedReturnCode) {
		Call<Void> expectedCall = mock(Call.class);
		// Response is a final class that cannot be mocked.
		Response<Void> errorResponse = Response.error(
			expectedReturnCode,
			new RealResponseBody("dontcare", 0, mock(BufferedSource.class))
		);
		try {
			when(expectedCall.execute()).thenReturn(errorResponse);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return expectedCall;
	}
}
