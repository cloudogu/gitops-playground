package com.cloudogu.gitops.git.providers.scmmanager.api

import retrofit2.Call
import retrofit2.http.*

interface PluginApi {
	@POST("v2/plugins/available/{name}/install")
	Call<Void> install(@Path("name") String name, @Query("restart") Boolean restart)

	@PUT("v2/config/jenkins/")
	@Headers("Content-Type: application/json")
	Call<Void> configureJenkinsPlugin(@Body Map<String, Object> config)
}