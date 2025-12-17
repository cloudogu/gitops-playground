package com.cloudogu.gitops.okhttp

import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import static org.assertj.core.api.Assertions.assertThat

class RetryInterceptorTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig()
                    .dynamicPort()
            .dynamicHttpsPort())
            .build()

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll()
    }

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
    void 'retries three times on 500 with HTTPS'() {
        wireMock.stubFor(get(urlEqualTo("/secure"))
                .inScenario("HTTPS Retry Scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("First Retry"))

        wireMock.stubFor(get(urlEqualTo("/secure"))
                .inScenario("HTTPS Retry Scenario")
                .whenScenarioStateIs("First Retry")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Second Retry"))

        wireMock.stubFor(get(urlEqualTo("/secure"))
                .inScenario("HTTPS Retry Scenario")
                .whenScenarioStateIs("Second Retry")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("Successful Result")))

        def client = createClient()
        def httpsUrl = "https://localhost:${wireMock.httpsPort}/secure"
        def response = client.newCall(new Request.Builder().url(httpsUrl).build()).execute()

        assertThat(response.body().string()).isEqualTo("Successful Result")
        wireMock.verify(3, getRequestedFor(urlEqualTo("/secure")))
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
        // 1. Create a TrustManager that trusts everyone
        def trustAllCerts = [
                new X509TrustManager() {
                    void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0] }
                }
        ] as TrustManager[]

        def sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom())

        new OkHttpClient.Builder()
                .addInterceptor(new RetryInterceptor(retries: 3, waitPeriodInMs: 0))
                .readTimeout(50, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier({ hostname, session -> true } as HostnameVerifier)
                .build()
    }
}