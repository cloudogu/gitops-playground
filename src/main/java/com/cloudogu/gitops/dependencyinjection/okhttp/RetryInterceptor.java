package com.cloudogu.gitops.dependencyinjection.okhttp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;

@RequiredArgsConstructor
@Slf4j
public class RetryInterceptor implements Interceptor {

	private static final Set<Integer> STATUS_CODES_TO_RETRY = Set.of(
		408, // Request Timeout
		429, // Too Many Requests
		500, // Internal Server Error
		502, // Bad Gateway
		503, // Service Unavailable
		504 // Gateway Timeout
	);

	private static final int DEFAULT_RETRIES = 180;
	private static final int DEFAULT_WAIT_PERIOD_MS = 2000;

	private final int retries;
	private final int waitPeriodInMs;

	public RetryInterceptor() {
		this(DEFAULT_RETRIES, DEFAULT_WAIT_PERIOD_MS);
	}

	@NotNull
	@Override
	public Response intercept(@NotNull Chain chain) throws IOException {
		int i = 0;
		int lastStatusCode = -1;
		IOException lastException = null;

		do {
			try {
				Response response = chain.proceed(chain.request());

				if (!STATUS_CODES_TO_RETRY.contains(response.code())) {
					// Success or non-retriable error - return the response
					return response;
				}

				log.trace("Retry HTTP Request to {} due to status code {}", chain.request().url(), response.code());
				lastStatusCode = response.code();
				response.close();

			} catch (SocketTimeoutException e) {
				lastException = e;
				log.trace(
					"Retry HTTP Request to {} due to SocketTimeoutException: {}", chain.request()
					                                                                   .url(), e.getMessage()
				);
			}

			// Wait before next retry (but not after the last attempt)
			if (i < retries) {
				try {
					Thread.sleep(waitPeriodInMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Retry interceptor interrupted", e);
				}
			}
			++i;

		} while (i <= retries);

		// If we got here, all retries failed
		if (lastException != null) {
			throw lastException;
		}
		throw new IOException("Request to " + chain.request()
		                                           .url() + " failed after " + retries + " retries, last status code " + lastStatusCode);
	}
}
