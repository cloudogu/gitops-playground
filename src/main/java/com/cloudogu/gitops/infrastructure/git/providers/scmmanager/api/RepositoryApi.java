package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.Permission;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Retrofit client for the SCM-Manager repository REST API.
 */
public interface RepositoryApi {
	/**
	 * Deletes the repository identified by namespace and name.
	 *
	 * @param namespace SCM-Manager namespace of the repository
	 * @param name      name of the repository
	 * @return call that completes when the repository was deleted
	 */
	@DELETE("v2/repositories/{namespace}/{name}")
	Call<Void> delete(@Path("namespace") String namespace, @Path("name") String name);

	/**
	 * Creates a new repository, optionally initializing it with an initial branch.
	 *
	 * @param repository payload describing the repository to create
	 * @param initialize whether the repository should be initialized with an initial branch
	 * @return call that completes when the repository was created
	 */
	@POST("v2/repositories/")
	@Headers("Content-Type: application/vnd.scmm-repository+json;v=2")
	Call<Void> create(@Body Repository repository, @Query("initialize") boolean initialize);

	/**
	 * Adds a permission entry to the repository identified by namespace and name.
	 *
	 * @param namespace  SCM-Manager namespace of the repository
	 * @param name       name of the repository
	 * @param permission permission entry to add
	 * @return call that completes when the permission was created
	 */
	@POST("v2/repositories/{namespace}/{name}/permissions/")
	@Headers("Content-Type: application/vnd.scmm-repositoryPermission+json")
	Call<Void> createPermission(@Path("namespace") String namespace,
	                            @Path("name") String name,
	                            @Body Permission permission);
}
