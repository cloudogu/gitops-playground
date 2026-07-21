package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.cloudogu.gitops.infrastructure.git.providers.scmmanager.Permission;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface RepositoryApi {
  @DELETE("v2/repositories/{namespace}/{name}")
  Call<Void> delete(@Path("namespace") String namespace, @Path("name") String name);

  @POST("v2/repositories/")
  @Headers("Content-Type: application/vnd.scmm-repository+json;v=2")
  Call<Void> create(@Body Repository repository, @Query("initialize") boolean initialize);

  @POST("v2/repositories/{namespace}/{name}/permissions/")
  @Headers("Content-Type: application/vnd.scmm-repositoryPermission+json")
  Call<Void> createPermission(
      @Path("namespace") String namespace, @Path("name") String name, @Body Permission permission);
}
