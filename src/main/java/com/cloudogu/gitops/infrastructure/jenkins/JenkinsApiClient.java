package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.config.Config;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;

@Singleton
@Slf4j
public class JenkinsApiClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Config config;
    private final OkHttpClient client;

    // Number of retries is uncommonly high, because we might have to outlive an unexpected Jenkins restart
    @Setter(AccessLevel.PROTECTED)
    private int maxRetries = 180;
    @Setter(AccessLevel.PROTECTED)
    private int waitPeriodInMs = 2000;

    public JenkinsApiClient(Config config, @Named("jenkins") OkHttpClient client) {
        this.config = config;

        if (config.getApplication() != null && Boolean.TRUE.equals(config.getApplication().getInsecure())) {
            this.client = client.newBuilder()
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } else {
            this.client = client;
        }
    }

    public String runScript(String code) {
        log.trace("Running groovy script in Jenkins: {}", code);
        try (Response response = postRequestWithCrumb("scriptText", new FormBody.Builder().add("script", code).build())) {
            if (response.code() != 200) {
                throw new RuntimeException("Could not run script. Status code " + response.code());
            }
            return response.body().string();
        } catch (IOException e) {
            throw new RuntimeException("Failed to run Jenkins script", e);
        }
    }

    public Response postRequestWithCrumb(String url) {
        return postRequestWithCrumb(url, null);
    }

    public Response postRequestWithCrumb(String url, RequestBody postData) {
        return sendRequestWithRetries(() -> {
            Request.Builder request = buildRequest(url)
                    .header("Jenkins-Crumb", getCrumb());

            if (postData != null) {
                request.method("POST", postData);
            } else {
                // Explicitly set empty body. Otherwise okhttp sends GET
                RequestBody emptyBody = RequestBody.create("", null);
                request.method("POST", emptyBody);
            }

            return request.build();
        });
    }

    private String getCrumb() {
        log.trace("Getting Crumb for Jenkins");
        try (Response response = sendRequestWithRetries(() -> buildRequest("crumbIssuer/api/json").build())) {
            if (response.code() != 200) {
                throw new RuntimeException("Could not create crumb. Status code " + response.code());
            }

            JsonNode json = objectMapper.readTree(response.body().byteStream());

            if (json == null || !json.has("crumb")) {
                throw new RuntimeException("Could not create crumb. Invalid json.");
            }

            return json.get("crumb").asText();
        } catch (IOException e) {
            throw new RuntimeException("Failed to retrieve Jenkins crumb", e);
        }
    }

    private Request.Builder buildRequest(String url) {
        return new Request.Builder()
                .url(config.getJenkins().getUrl() + "/" + url)
                .header("Authorization", Credentials.basic(config.getJenkins().getUsername(), config.getJenkins().getPassword()));
    }

    @FunctionalInterface
    interface RequestSupplier {
        Request get() throws Exception;
    }

    // We pass a supplier, so that we actually refetch a new crumb for a failed request
    // The Jenkins ApiClient has its own retry logic on top of RetryInterceptor, because of crumb lifetime and restarts
    private Response sendRequestWithRetries(RequestSupplier requestSupplier) {
        int retry = 0;
        Response response = null;
        do {
            if (response != null) {
                response.close();
                response = null;
            }
            try {
                Request request = requestSupplier.get();
                response = client.newCall(request).execute();
                if (!shouldRetryRequest(response)) {
                    break;
                }
            } catch (Exception e) {
                log.trace("Jenkins request failed, retrying... (try {}/{})", retry, maxRetries, e);
            }
            try {
                Thread.sleep(waitPeriodInMs);
            } catch (InterruptedException e) {
                if (response != null) {
                    response.close();
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for retry", e);
            }
        } while (++retry < maxRetries);

        if (response == null) {
            throw new RuntimeException("Failed to send request after " + maxRetries + " retries");
        }
        return response;
    }

    private boolean shouldRetryRequest(Response response) {
        // We might run into a 403 due to an invalid crumb from a previous session before jenkins was restarted.
        // Here in the ApiClient, we simply retry all 401 and 403 including fetching a new crumb
        return response.code() == 401 || response.code() == 403;
    }

}
