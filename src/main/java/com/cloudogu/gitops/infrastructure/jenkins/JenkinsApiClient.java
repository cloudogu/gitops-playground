package com.cloudogu.gitops.infrastructure.jenkins;

import com.cloudogu.gitops.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.UncheckedIOException;

@Singleton
@Slf4j
public class JenkinsApiClient {

	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final int HTTP_OK = 200;
	private static final int HTTP_UNAUTHORIZED = 401;
	private static final int HTTP_FORBIDDEN = 403;
	private static final int DEFAULT_MAX_RETRIES = 180;
	private static final int DEFAULT_WAIT_PERIOD_MS = 2000;

	private final Config config;
	private final OkHttpClient client;

	// Number of retries is uncommonly high, because we might have to outlive an unexpected Jenkins
	// restart
	@Setter(AccessLevel.PROTECTED)
	private int maxRetries = DEFAULT_MAX_RETRIES;

	@Setter(AccessLevel.PROTECTED)
	private int waitPeriodInMs = DEFAULT_WAIT_PERIOD_MS;

	public JenkinsApiClient(Config config, @Named("jenkins") OkHttpClient client) {
		this.config = config;

		if (config.getApplication() != null && config.getApplication().getInsecure()) {
			this.client = client.newBuilder().hostnameVerifier((hostname, session) -> true).build();
		} else {
			this.client = client;
		}
	}

	public String runScript(String code) {
		log.trace("Running groovy script in Jenkins: {}", code);
		try (Response response = postRequestWithCrumb("scriptText", new FormBody.Builder().add("script", code)
		                                                                                  .build())) {
			if (response.code() != HTTP_OK) {
				throw new IllegalStateException("Could not run script. Status code " + response.code());
			}
			return response.body().string();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to run Jenkins script", e);
		}
	}

	public Response postRequestWithCrumb(String url) {
		return postRequestWithCrumb(url, null);
	}

	public Response postRequestWithCrumb(String url, RequestBody postData) {
		return sendRequestWithRetries(() -> {
			Request.Builder request = buildRequest(url).header("Jenkins-Crumb", getCrumb());

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
		// Single attempt: this is called from within postRequestWithCrumb()'s own retry loop, which
		// already
		// waits and retries up to maxRetries times. Retrying here too would multiply into maxRetries^2
		// attempts.
		try (Response response = sendRequestWithRetries(() -> buildRequest("crumbIssuer/api/json").build(), 1)) {
			if (response.code() != HTTP_OK) {
				throw new IllegalStateException("Could not create crumb. Status code " + response.code());
			}

			JsonNode json = objectMapper.readTree(response.body().byteStream());

			if (json == null || !json.has("crumb")) {
				throw new IllegalStateException("Could not create crumb. Invalid json.");
			}

			return json.get("crumb").asText();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to retrieve Jenkins crumb", e);
		}
	}

	private Request.Builder buildRequest(String url) {
		return new Request.Builder().url(config.getJenkins().getUrl() + "/" + url)
		                            .header("Authorization", Credentials.basic(config.getJenkins()
		                                                                             .getUsername(), config.getJenkins()
		                                                                                                   .getPassword()));
	}

	@FunctionalInterface
	interface RequestSupplier {
		Request get();
	}

	// We pass a supplier, so that we actually refetch a new crumb for a failed request
	// The Jenkins ApiClient has its own retry logic on top of RetryInterceptor, because of crumb
	// lifetime and restarts
	private Response sendRequestWithRetries(RequestSupplier requestSupplier) {
		return sendRequestWithRetries(requestSupplier, maxRetries);
	}

	private Response sendRequestWithRetries(RequestSupplier requestSupplier, int retries) {
		int retry = 0;
		Response response = null;
		do {
			closeQuietly(response);
			response = attemptRequest(requestSupplier, retry, retries);
			if (response != null && !shouldRetryRequest(response)) {
				break;
			}
			waitBeforeRetry(retry, retries, response);
			retry++;
		} while (retry < retries);

		if (response == null) {
			throw new IllegalStateException("Failed to send request after " + retries + " retries");
		}
		return response;
	}

	private Response attemptRequest(RequestSupplier requestSupplier, int retry, int retries) {
		try {
			Request request = requestSupplier.get();
			return client.newCall(request).execute();
		} catch (Exception e) {
			log.trace("Jenkins request failed, retrying... (try {}/{})", retry, retries, e);
			return null;
		}
	}

	private void waitBeforeRetry(int retry, int retries, Response response) {
		if (retry + 1 >= retries) {
			return;
		}
		try {
			Thread.sleep(waitPeriodInMs);
		} catch (InterruptedException e) {
			closeQuietly(response);
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while waiting for retry", e);
		}
	}

	private static void closeQuietly(Response response) {
		if (response != null) {
			response.close();
		}
	}

	private static boolean shouldRetryRequest(Response response) {
		// We might run into a 403 due to an invalid crumb from a previous session before jenkins was
		// restarted.
		// Here in the ApiClient, we simply retry all 401 and 403 including fetching a new crumb
		return response.code() == HTTP_UNAUTHORIZED || response.code() == HTTP_FORBIDDEN;
	}
}
