package com.cloudogu.gitops.okhttp

import groovy.util.logging.Slf4j
import okhttp3.Interceptor
import okhttp3.Response
import org.jetbrains.annotations.NotNull

/**
 * Retries request on specific status codes as well as timeouts.
 * Both error codes (like temporary (!) 500 or 401/403) and timeouts occur often during our jenkins initialization, 
 * due to necessary restarts, e.g. after plugin installs.
 */
@Slf4j
class RetryInterceptor implements Interceptor {
    private int retries
    private int waitPeriodInMs

    // Number of retries in uncommonly high, because we might have to  outlive a unexpected Jenkins restart
    RetryInterceptor(int retries = 180, int waitPeriodInMs = 2000) {
        this.waitPeriodInMs = waitPeriodInMs
        this.retries = retries
    }

    @Override
    Response intercept(@NotNull Chain chain) throws IOException {
        def i = 0;
        Response response = null
        do {
            try {
                response = chain.proceed(chain.request())
                if (response.code() !in getStatusCodesToRetry()) {
                    break
                }

                log.trace("Retry HTTP Request to {} due to status code {}", chain.request().url().toString(), response.code())

            } catch (SocketTimeoutException e) {
                // fallthrough to retry
                log.trace("Retry HTTP Request to {} due to SocketTimeoutException: {}", chain.request().url().toString(), e.message)
            }
            response?.close()
            Thread.sleep(waitPeriodInMs)
            ++i
        } while(i < retries)

        return response
    }

    private List<Integer> getStatusCodesToRetry() {
        return [
            // list of codes if from curl --retry
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504, // Gateway Timeout
        ]
    }
}
