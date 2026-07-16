package com.cloudogu.gitops.dependencyinjection.okhttp;

import okhttp3.Interceptor;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Set;

public class RetryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private static final Set<Integer> STATUS_CODES_TO_RETRY = Set.of(
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
    );

    private final int retries;
    private final int waitPeriodInMs;

    public RetryInterceptor() {
        this(180, 2000);
    }

    public RetryInterceptor(int retries, int waitPeriodInMs) {
        this.retries = retries;
        this.waitPeriodInMs = waitPeriodInMs;
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        int i = 0;
        Response response = null;
        IOException lastException = null;

        do {
            try {
                response = chain.proceed(chain.request());

                if (!STATUS_CODES_TO_RETRY.contains(response.code())) {
                    // Success or non-retriable error - return the response
                    return response;
                }

                log.trace("Retry HTTP Request to {} due to status code {}", chain.request().url(), response.code());
                response.close();

            } catch (SocketTimeoutException e) {
                lastException = e;
                log.trace("Retry HTTP Request to {} due to SocketTimeoutException: {}", chain.request().url(), e.getMessage());
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
        if (response != null) {
            // Return the last failed response
            return response;
        } else if (lastException != null) {
            // All attempts resulted in timeout - throw the last exception
            throw lastException;
        } else {
            // This should never happen, but as a safety net
            throw new IOException("Request failed after " + retries + " retries");
        }
    }
}
