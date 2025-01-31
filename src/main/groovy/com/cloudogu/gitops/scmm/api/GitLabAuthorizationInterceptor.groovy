package com.cloudogu.gitops.scmm.api

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

class GitLabAuthorizationInterceptor implements Interceptor {

    private final String accessToken;

    /**
     * Constructor for GitLabAuthorizationInterceptor.
     *
     * @param accessToken Personal Access Token (PAT) for GitLab API.
     */
    GitLabAuthorizationInterceptor(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalArgumentException("GitLab access token must not be null or empty");
        }
        this.accessToken = accessToken;
    }

    /**
     * Intercepts and modifies the request to include the PRIVATE-TOKEN header.
     *
     * @param chain The request chain.
     * @return The modified response.
     * @throws IOException if the request fails.
     */
    @Override
    @NotNull Response intercept(@NotNull Chain chain) throws IOException {
        Request originalRequest = chain.request();

        // Add the PRIVATE-TOKEN header to the request
        Request modifiedRequest = originalRequest.newBuilder()
                .header("PRIVATE-TOKEN", accessToken)
                .build();

        return chain.proceed(modifiedRequest);
    }
}
