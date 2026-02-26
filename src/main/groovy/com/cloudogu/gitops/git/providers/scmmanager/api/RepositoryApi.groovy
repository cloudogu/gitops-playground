package com.cloudogu.gitops.git.providers.scmmanager.api

import com.cloudogu.gitops.git.providers.scmmanager.Permission

import retrofit2.Call
import retrofit2.http.*

interface RepositoryApi {
	@DELETE("v2/repositories/{namespace}/{name}")
	Call<Void> delete(@Path("namespace") String namespace, @Path("name") String name)

	@POST("v2/repositories/")
	@Headers("Content-Type: application/vnd.scmm-repository+json;v=2")
	Call<Void> create(@Body Repository repository, @Query("initialize") boolean initialize)

	@POST("v2/repositories/{namespace}/{name}/permissions/")
	@Headers("Content-Type: application/vnd.scmm-repositoryPermission+json")
	Call<Void> createPermission(@Path("namespace") String namespace, @Path("name") String name, @Body Permission permission)
}