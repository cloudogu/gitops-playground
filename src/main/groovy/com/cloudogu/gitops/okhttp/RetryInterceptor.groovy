package com.cloudogu.gitops.okhttp

import groovy.util.logging.Slf4j

import okhttp3.Interceptor
import okhttp3.Response
import org.jetbrains.annotations.NotNull

/**
 * Retries request on specific status codes as well as timeouts.
 * Both error codes (like temporary (!) 500 or 401/403) and timeouts occur often during our jenkins initialization, 
 * due to necessary restarts, e.g. after plugin installs.*/
@Slf4j
class RetryInterceptor implements Interceptor {
	private int retries
	private int waitPeriodInMs

	// Number of retries in uncommonly high, because we might have to outlive a unexpected Jenkins restart
	RetryInterceptor(int retries = 180, int waitPeriodInMs = 2000) {
		this.waitPeriodInMs = waitPeriodInMs
		this.retries = retries
	}

	@Override
	Response intercept(@NotNull Chain chain) throws IOException {
		def i = 0
		Response response = null
		IOException lastException = null

		do {
			try {
				response = chain.proceed(chain.request())

				if (response.code() !in getStatusCodesToRetry()) {
					// Success or non-retriable error - return the response
					return response
				}

				log.trace("Retry HTTP Request to {} due to status code {}", chain.request().url().toString(), response.code())
				response.close()

			} catch (SocketTimeoutException e) {
				lastException = e
				log.trace("Retry HTTP Request to {} due to SocketTimeoutException: {}", chain.request().url().toString(), e.message)
			}

			// Wait before next retry (but not after the last attempt)
			if (i < retries) {
				Thread.sleep(waitPeriodInMs)
			}
			++i

		} while (i <= retries)

		// If we got here, all retries failed
		if (response != null) {
			// Return the last failed response
			return response
		} else if (lastException != null) {
			// All attempts resulted in timeout - throw the last exception
			throw lastException
		} else {
			// This should never happen, but as a safety net
			throw new IOException("Request failed after ${retries} retries")
		}
	}

	private List<Integer> getStatusCodesToRetry() {
		return [// list of codes from curl --retry
		        408, // Request Timeout
		        429, // Too Many Requests
		        500, // Internal Server Error
		        502, // Bad Gateway
		        503, // Service Unavailable
		        504, // Gateway Timeout
		]
	}
}