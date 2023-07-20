package com.cloudogu.gitops.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import org.jetbrains.annotations.NotNull

/**
 * Retries request on specific status codes
 */
class RetryInterceptor implements Interceptor {
    private int retries
    private int waitPeriodInMs

    RetryInterceptor(int retries = 3, int waitPeriodInMs = 1000) {
        this.waitPeriodInMs = waitPeriodInMs
        this.retries = retries
    }

    @Override
    Response intercept(@NotNull Chain chain) throws IOException {
        def i = 0;
        def response = chain.proceed(chain.request())
        while (i < retries && response.code() in getStatusCodesToRetry()) {
            Thread.sleep(waitPeriodInMs)
            response = chain.proceed(chain.request())
            ++i
        }

        return response
    }

    private List<Integer> getStatusCodesToRetry() {
        // list of codes if from curl --retry
        return [
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504, // Gateway Timeout
        ]
    }
}
