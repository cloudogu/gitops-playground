package com.cloudogu.gitops.jenkins

import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

import com.cloudogu.gitops.config.Config

import io.micronaut.context.ApplicationContext

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

class JenkinsApiClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig()
                    .dynamicPort()
                    .dynamicHttpsPort())
            .build()

    @Test
    void 'runs script with crumb'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-crumb", "crumbRequestField": "Jenkins-Crumb"}')))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")))

        def httpClient = getUnsafeOkHttpClient().newBuilder().cookieJar(new JavaNetCookieJar(new CookieManager())).build()
        def apiClient = new JenkinsApiClient(
                new Config(jenkins: new Config.JenkinsSchema(url: "${wireMock.baseUrl()}/jenkins")),
                httpClient)

        def result = apiClient.runScript("println('ok')")
        assertThat(result).isEqualTo("ok")

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .withHeader("Authorization", matching("Basic .*")))

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/scriptText"))
                .withHeader("Authorization", matching("Basic .*"))
                .withHeader("Jenkins-Crumb", equalTo("the-crumb")))
    }

    @Test
    void 'adds crumb to sendRequest'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-crumb", "crumbRequestField": "Jenkins-Crumb"}')))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/foobar"))
                .willReturn(aResponse().withStatus(200)))

        def client = new JenkinsApiClient(
                new Config(jenkins: new Config.JenkinsSchema(url: "${wireMock.baseUrl()}/jenkins")),
                getUnsafeOkHttpClient())
        client.postRequestWithCrumb("foobar")

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")))
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/foobar"))
                .withHeader("Jenkins-Crumb", equalTo("the-crumb")))
    }

    @Test
    void 'adds crumb and post data to sendRequest'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-crumb", "crumbRequestField": "Jenkins-Crumb"}')))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/foobar"))
                .willReturn(aResponse().withStatus(200)))

        def client = new JenkinsApiClient(
                new Config(jenkins: new Config.JenkinsSchema(url: "${wireMock.baseUrl()}/jenkins")),
                getUnsafeOkHttpClient())
        client.postRequestWithCrumb("foobar", new FormBody.Builder().add('key', 'value with spaces').build())

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")))
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/foobar"))
                .withHeader("Jenkins-Crumb", equalTo("the-crumb"))
				.withFormParam("key", equalTo("value with spaces")))

	}

    @Test
    void 'allows self-signed certificates when using insecure'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-crumb", "crumbRequestField": "Jenkins-Crumb"}')))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")))

        def apiClient = ApplicationContext.run()
                .registerSingleton(new Config(
                        application: new Config.ApplicationSchema(
                                insecure: true),
                        jenkins: new Config.JenkinsSchema(
                                url: "${wireMock.baseUrl().replace('http://', 'https://')}/jenkins")
                ))
                .getBean(JenkinsApiClient)

        def result = apiClient.runScript("println('ok')")
        assertThat(result).isEqualTo("ok")

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .withHeader("Authorization", matching("Basic .*")))

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/scriptText"))
                .withHeader("Authorization", matching("Basic .*"))
                .withHeader("Jenkins-Crumb", equalTo("the-crumb")))
    }

    @Test
    void 'retries on invalid crumb'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .inScenario("Invalid Crumb Retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-invalid-crumb", "crumbRequestField": "Jenkins-Crumb"}'))
                .willSetStateTo("First Crumb"))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
                .inScenario("Invalid Crumb Retry")
                .whenScenarioStateIs("First Crumb")
                .withHeader("Jenkins-Crumb", equalTo("the-invalid-crumb"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withBody('{"servlet":"Stapler", "message":"No valid crumb was included in the request", "url":"/scriptText", "status":"403"}'))
                .willSetStateTo("Invalid Crumb Response"))

        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .inScenario("Invalid Crumb Retry")
                .whenScenarioStateIs("Invalid Crumb Response")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-second-crumb", "crumbRequestField": "Jenkins-Crumb"}'))
                .willSetStateTo("Second Crumb"))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
                .inScenario("Invalid Crumb Retry")
                .whenScenarioStateIs("Second Crumb")
                .withHeader("Jenkins-Crumb", equalTo("the-second-crumb"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")))

        def httpClient = getUnsafeOkHttpClient()
        def apiClient = new JenkinsApiClient(
                new Config(jenkins: new Config.JenkinsSchema(url: "${wireMock.baseUrl()}/jenkins")),
                httpClient)
        apiClient.setMaxRetries(3)
        apiClient.setWaitPeriodInMs(0)

        def result = apiClient.runScript("println('ok')")
        assertThat(result).isEqualTo("ok")

        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")))
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/jenkins/scriptText")))
    }

    @Test
    void 'retries on invalid crumb are limited'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-invalid-crumb", "crumbRequestField": "Jenkins-Crumb"}')))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withBody('{"servlet":"Stapler", "message":"No valid crumb was included in the request", "url":"/scriptText", "status":"403"}')))

        def httpClient = getUnsafeOkHttpClient()
        def apiClient = new JenkinsApiClient(
                new Config(jenkins: new Config.JenkinsSchema(url: "${wireMock.baseUrl()}/jenkins")),
                httpClient)
        apiClient.setMaxRetries(3)
        apiClient.setWaitPeriodInMs(0)

        shouldFail(RuntimeException) {
            apiClient.runScript("println('ok')")
        }

        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")))
        wireMock.verify(3, postRequestedFor(urlPathEqualTo("/jenkins/scriptText")))
    }

    @Test
    void 'retries when fetching crumb fails'() {
        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .inScenario("Crumb Fetch Retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("error"))
                .willSetStateTo("First Attempt Failed"))

        wireMock.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                .inScenario("Crumb Fetch Retry")
                .whenScenarioStateIs("First Attempt Failed")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('{"crumb": "the-invalid-crumb", "crumbRequestField": "Jenkins-Crumb"}')))

        wireMock.stubFor(post(urlPathEqualTo("/jenkins/scriptText"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")))

        def httpClient = getUnsafeOkHttpClient()
        def apiClient = new JenkinsApiClient(
                new Config(jenkins: new Config.JenkinsSchema(url: "${wireMock.baseUrl()}/jenkins")),
                httpClient)
        apiClient.setMaxRetries(3)
        apiClient.setWaitPeriodInMs(0)

        def result = apiClient.runScript("println('ok')")
        assertThat(result).isEqualTo("ok")

        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/jenkins/crumbIssuer/api/json")))
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/jenkins/scriptText")))
    }

    private static OkHttpClient getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = [
                    new X509TrustManager() {
                        @Override
                        void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override
                        void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override
                        X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0]
                        }
                    }
            ] as TrustManager[]

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, new SecureRandom())
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory()

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0])
                    .hostnameVerifier { hostname, session -> true }
                    .build()
        } catch (Exception e) {
            throw new RuntimeException(e)
        }
    }
}