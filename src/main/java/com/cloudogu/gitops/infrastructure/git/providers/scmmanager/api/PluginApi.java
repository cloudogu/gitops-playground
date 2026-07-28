package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import java.util.Map;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Retrofit client for the SCM-Manager plugin REST API. */
public interface PluginApi {
/**
* Installs the given plugin from the list of available plugins, optionally restarting SCM-Manager
* afterwards.
*
* @param name name of the plugin to install
* @param restart whether SCM-Manager should restart after the installation
* @return call that completes when the installation was triggered
*/
@POST("v2/plugins/available/{name}/install")
Call<Void> install(@Path("name") String name, @Query("restart") Boolean restart);

/**
* Writes the configuration of the SCM-Manager Jenkins plugin.
*
* @param config Jenkins plugin configuration to store
* @return call that completes when the configuration was written
*/
@PUT("v2/config/jenkins/")
@Headers("Content-Type: application/json")
Call<Void> configureJenkinsPlugin(@Body Map<String, Object> config);
}
