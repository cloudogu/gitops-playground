package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/** Parent class for all SCMM Apis that lazily creates the APIs */
@Slf4j
public class ScmManagerApiClient {

  private static final int HTTP_CREATED = 201;
  private static final int HTTP_CONFLICT = 409;

  private final OkHttpClient okHttpClient;
  private final String url;

  public ScmManagerApiClient(String url, Credentials credentials, Boolean isInsecure) {
    this.url = url;
    this.okHttpClient = HttpClientFactory.buildOkHttpClient(credentials, isInsecure);
  }

  public UsersApi usersApi() {
    return retrofit().create(UsersApi.class);
  }

  public RepositoryApi repositoryApi() {
    return retrofit().create(RepositoryApi.class);
  }

  public ScmManagerApi generalApi() {
    return retrofit().create(ScmManagerApi.class);
  }

  public PluginApi pluginApi() {
    return retrofit().create(PluginApi.class);
  }

  public static void handleApiResponse(Call<Void> apiCall) {
    handleApiResponse(apiCall, "");
  }

  public static void handleApiResponse(Call<Void> apiCall, String additionalMessage) {
    try {
      Response<Void> response = apiCall.execute();

      if (!response.isSuccessful()
          && response.code() != HTTP_CONFLICT
          && response.code() != HTTP_CREATED) {
        String errorMessage =
            "API call failed!'. HTTP Status: " + response.code() + " - " + response.message();
        if (additionalMessage != null && !additionalMessage.isEmpty()) {
          errorMessage += " Additional Info: " + additionalMessage;
        }
        log.error(errorMessage);
        throw new RuntimeException(errorMessage);
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
    return new Retrofit.Builder()
        .baseUrl(this.url)
        .client(okHttpClient)
        // Converts HTTP body objects to JSON
        .addConverterFactory(JacksonConverterFactory.create())
        .build();
  }
}
