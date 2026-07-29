package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;

import java.util.List;
import java.util.Map;

/**
 * Retrofit client for the SCM-Manager user REST API.
 */
public interface UsersApi {
	/**
	 * Deletes the user with the given username.
	 *
	 * @param id username of the user to delete
	 * @return call that completes when the user was deleted
	 */
	@DELETE("v2/users/{id}")
	Call<Void> delete(@Path("id") String id);

	/**
	 * Creates a new user account.
	 *
	 * @param user payload describing the user to create
	 * @return call that completes when the user was created
	 */
	@Headers("Content-Type: application/vnd.scmm-user+json;v=2")
	@POST("v2/users")
	Call<Void> addUser(@Body ScmManagerUser user);

	/**
	 * Replaces the global permissions of the given user.
	 *
	 * @param username    username of the user to update
	 * @param permissions permission collection to set
	 * @return call that completes when the permissions were set
	 */
	@Headers("Content-Type: application/vnd.scmm-permissionCollection+json;v=2")
	@PUT("v2/users/{username}/permissions")
	Call<Void> setPermissionForUser(@Path("username") String username, @Body Map<String, List<String>> permissions);
}
