package com.cloudogu.gitops.okhttp

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.util.concurrent.TimeUnit

import static org.assertj.core.api.Assertions.assertThat

class RetryInterceptorTest {
    private MockWebServer webServer = new MockWebServer()

    @AfterEach
    void tearDown() {
        webServer.shutdown()
    }

    @Test
    void 'retries three times on 500'() {
        webServer.enqueue(new MockResponse().setResponseCode(500))
        webServer.enqueue(new MockResponse().setResponseCode(500))
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("Successful Result"))

        def client = createClient()

        def response = client.newCall(new Request.Builder().url(webServer.url("")).build()).execute()

        assertThat(response.body().string()).isEqualTo("Successful Result")
    }

    @Test
    void 'fails after third retry'() {
        webServer.enqueue(new MockResponse().setResponseCode(500))
        webServer.enqueue(new MockResponse().setResponseCode(500))
        webServer.enqueue(new MockResponse().setResponseCode(500))
        webServer.enqueue(new MockResponse().setResponseCode(500))
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("Successful Result"))

        def client = createClient()

        def response = client.newCall(new Request.Builder().url(webServer.url("")).build()).execute()

        assertThat(response.code()).isEqualTo(500)
        assertThat(webServer.takeRequest(1, TimeUnit.MILLISECONDS).path).isNotNull()
        assertThat(webServer.takeRequest(1, TimeUnit.MILLISECONDS).path).isNotNull()
    }

    private OkHttpClient createClient() {
        new OkHttpClient.Builder()
                .addInterceptor(new RetryInterceptor(waitPeriodInMs: 0))
                .build()
    }
}