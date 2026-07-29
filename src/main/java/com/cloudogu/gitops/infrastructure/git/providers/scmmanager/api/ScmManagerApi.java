package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.PUT;

import java.util.Map;

/**
 * Retrofit client for the general SCM-Manager REST API (availability check, global config).
 */
public interface ScmManagerApi {

	/**
	 * Probes the API root to check whether SCM-Manager is up and reachable.
	 *
	 * @return call that succeeds when SCM-Manager is available
	 */
	@GET("v2")
	Call<Void> checkScmmAvailable();

	/**
	 * Writes the global SCM-Manager configuration.
	 *
	 * @param config global configuration to store
	 * @return call that completes when the configuration was written
	 */
	@PUT("v2/config")
	@Headers("Content-Type: application/vnd.scmm-config+json;v=2")
	Call<Void> setConfig(@Body Map<String, Object> config);
}
