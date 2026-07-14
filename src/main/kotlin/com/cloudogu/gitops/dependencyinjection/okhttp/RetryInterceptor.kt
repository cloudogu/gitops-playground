package com.cloudogu.gitops.dependencyinjection.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Retries request on specific status codes as well as timeouts.
 * Both error codes (like temporary (!) 500 or 401/403) and timeouts occur often during our jenkins initialization, 
 * due to necessary restarts, e.g. after plugin installs.
 */
class RetryInterceptor : Interceptor {

    var retries: Int = 180
    var waitPeriodInMs: Int = 2000

    companion object {
        private val log = LoggerFactory.getLogger(RetryInterceptor::class.java)
    }

    // Standard no-arg constructor
    constructor()

    // Constructor with parameters
    @JvmOverloads
    constructor(retries: Int, waitPeriodInMs: Int = 2000) {
        this.retries = retries
        this.waitPeriodInMs = waitPeriodInMs
    }

    // Map-constructor for Groovy named arguments
    constructor(map: Map<String, Any?>) {
        (map["retries"] as? Number)?.let { this.retries = it.toInt() }
        (map["waitPeriodInMs"] as? Number)?.let { this.waitPeriodInMs = it.toInt() }
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var i = 0
        var response: Response? = null
        var lastException: IOException? = null

        do {
            try {
                response = chain.proceed(chain.request())

                if (response.code !in getStatusCodesToRetry()) {
                    return response
                }

                log.trace("Retry HTTP Request to {} due to status code {}", chain.request().url.toString(), response.code)
                response.close()

            } catch (e: SocketTimeoutException) {
                lastException = e
                log.trace("Retry HTTP Request to {} due to SocketTimeoutException: {}", chain.request().url.toString(), e.message)
            }

            if (i < retries) {
                try {
                    Thread.sleep(waitPeriodInMs.toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for request retry", e)
                }
            }
            ++i

        } while (i <= retries)

        if (response != null) {
            return response
        } else if (lastException != null) {
            throw lastException
        } else {
            throw IOException("Request failed after $retries retries")
        }
    }

    private fun getStatusCodesToRetry(): List<Int> {
        return listOf(
            408, // Request Timeout
            429, // Too Many Requests
            500, // Internal Server Error
            502, // Bad Gateway
            503, // Service Unavailable
            504  // Gateway Timeout
        )
    }
}
