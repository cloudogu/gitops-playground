package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * Parent class for all SCMM Apis that lazily creates the APIs
 */
@Slf4j
public class ScmManagerApiClient {

	private static final int HTTP_CREATED = 201;
	private static final int HTTP_CONFLICT = 409;

	private final OkHttpClient okHttpClient;
	private final String url;

	/**
	 * Creates a client for the SCM-Manager REST API.
	 *
	 * @param url         base URL of the SCM-Manager REST API
	 * @param credentials basic auth credentials used for every request
	 * @param isInsecure  whether TLS certificate and hostname verification should be disabled
	 */
	public ScmManagerApiClient(String url, Credentials credentials, Boolean isInsecure) {
		this.url = url;
		this.okHttpClient = HttpClientFactory.buildOkHttpClient(credentials, isInsecure);
	}

	/**
	 * Creates a {@link UsersApi} bound to this client's base URL and credentials.
	 *
	 * @return a users API client
	 */
	public UsersApi usersApi() {
		return retrofit().create(UsersApi.class);
	}

	/**
	 * Creates a {@link RepositoryApi} bound to this client's base URL and credentials.
	 *
	 * @return a repository API client
	 */
	public RepositoryApi repositoryApi() {
		return retrofit().create(RepositoryApi.class);
	}

	/**
	 * Creates a {@link ScmManagerApi} bound to this client's base URL and credentials.
	 *
	 * @return a general API client
	 */
	public ScmManagerApi generalApi() {
		return retrofit().create(ScmManagerApi.class);
	}

	/**
	 * Creates a {@link PluginApi} bound to this client's base URL and credentials.
	 *
	 * @return a plugin API client
	 */
	public PluginApi pluginApi() {
		return retrofit().create(PluginApi.class);
	}

	/**
	 * Executes the API call without additional context, see {@link #handleApiResponse(Call, String)}.
	 *
	 * @param apiCall the call to execute
	 */
	public static void handleApiResponse(Call<Void> apiCall) {
		handleApiResponse(apiCall, "");
	}

	/**
	 * Executes the API call and throws when the response is neither successful nor an acceptable
	 * status (201 Created, 409 Conflict for already existing resources).
	 *
	 * @param apiCall           the call to execute
	 * @param additionalMessage extra context appended to the error message on failure
	 */
	public static void handleApiResponse(Call<Void> apiCall, String additionalMessage) {
		try {
			Response<Void> response = apiCall.execute();

			if (!response.isSuccessful() && response.code() != HTTP_CONFLICT && response.code() != HTTP_CREATED) {
				String errorMessage = "API call failed!'. HTTP Status: " + response.code() + " - " + response.message();
				if (additionalMessage != null && !additionalMessage.isEmpty()) {
					errorMessage += " Additional Info: " + additionalMessage;
				}
				log.error(errorMessage);
				throw new IllegalStateException(errorMessage);
			} else {
				log.debug("Successfully completed " + apiCall);
			}
		} catch (Exception e) {
			String errorMessage = "Error executing API: " + e.getMessage();
			log.error(errorMessage, e);
			throw new RuntimeException(errorMessage, e);
		}
	}

	protected Retrofit retrofit() {
		return new Retrofit.Builder().baseUrl(this.url).client(okHttpClient)
		                             // Converts HTTP body objects to JSON
		                             .addConverterFactory(JacksonConverterFactory.create()).build();
	}
}
