package com.cloudogu.gitops.okhttp

import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

import java.util.concurrent.TimeUnit

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import static org.assertj.core.api.Assertions.assertThat

class RetryInterceptorTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig()
                    .dynamicPort()
                    .dynamicHttpsPort()) // Enable HTTPS with self-signed cert
            .build()

    @Test
    void 'retries three times on 500'() {
        wireMock.stubFor(get(urlEqualTo("/"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("First Retry"))

        wireMock.stubFor(get(urlEqualTo("/"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("First Retry")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Second Retry"))

        wireMock.stubFor(get(urlEqualTo("/"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Second Retry")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Successful Result")))

        def client = createClient()
        def response = client.newCall(new Request.Builder().url(wireMock.baseUrl()).build()).execute()

        assertThat(response.body().string()).isEqualTo("Successful Result")
        wireMock.verify(3, getRequestedFor(urlEqualTo("/")))
    }

    @Test
    void 'retries on timeout'() {
        wireMock.stubFor(get(urlEqualTo("/"))
                .inScenario("Timeout Scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(100)) // Delay longer than read timeout
                .willSetStateTo("After Timeout"))

        wireMock.stubFor(get(urlEqualTo("/"))
                .inScenario("Timeout Scenario")
                .whenScenarioStateIs("After Timeout")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Successful Result")))

        def client = createClient()
        def response = client.newCall(new Request.Builder().url(wireMock.baseUrl()).build()).execute()

        assertThat(response.body().string()).isEqualTo("Successful Result")
        wireMock.verify(2, getRequestedFor(urlEqualTo("/")))
    }

    @Test
    void 'fails after third retry'() {
        wireMock.stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse().withStatus(500)))

        def client = createClient()
        def response = client.newCall(new Request.Builder().url(wireMock.baseUrl()).build()).execute()

        assertThat(response.code()).isEqualTo(500)
        wireMock.verify(4, getRequestedFor(urlEqualTo("/"))) // Initial request + 3 retries
    }

    private OkHttpClient createClient() {
        new OkHttpClient.Builder()
                .addInterceptor(new RetryInterceptor(retries: 3, waitPeriodInMs: 0))
                .readTimeout(50, TimeUnit.MILLISECONDS)
                .build()
    }
}