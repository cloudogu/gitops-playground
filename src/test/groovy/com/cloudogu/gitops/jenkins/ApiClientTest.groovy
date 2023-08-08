package com.cloudogu.gitops.jenkins

import com.cloudogu.gitops.common.MockWebServerHttpsFactory
import com.cloudogu.gitops.config.Configuration
import io.micronaut.context.ApplicationContext
import io.micronaut.context.Qualifier
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom

import static org.assertj.core.api.Assertions.assertThat

class ApiClientTest {
    private MockWebServer webServer = new MockWebServer()

    @AfterEach
    void tearDown() {
        webServer.shutdown()
    }

    @Test
    void 'runs script with crumb'() {
        webServer.setDispatcher { request ->
            switch (request.path) {
                case "/jenkins/crumbIssuer/api/json":
                    return new MockResponse().setBody('{"crumb": "the-crumb", "crumbRequestField": "Jenkins-Crumb"}')
                case "/jenkins/scriptText":
                    return new MockResponse().setBody("ok")
                default:
                    return new MockResponse().setStatus("404")
            }
        }
        webServer.start()

        def httpClient = new OkHttpClient.Builder().cookieJar(new JavaNetCookieJar(new CookieManager())).build()
        def apiClient = new ApiClient(webServer.url("jenkins").toString(), "admin", "admin", httpClient)

        def result = apiClient.runScript("println('ok')")
        assertThat(result).isEqualTo("ok")

        def crumbRequest = webServer.takeRequest()
        assertThat(crumbRequest.path).isEqualTo("/jenkins/crumbIssuer/api/json")
        assertThat(crumbRequest.getHeader('Authorization')).startsWith("Basic ")

        def runScriptRequest = webServer.takeRequest()
        assertThat(runScriptRequest.path).isEqualTo("/jenkins/scriptText")
        assertThat(runScriptRequest.getHeader('Authorization')).startsWith("Basic ")
        assertThat(runScriptRequest.getHeader('Jenkins-Crumb')).startsWith("the-crumb")
    }

    @Test
    void 'allows self-signed certificates when using insecure'() {
        webServer.useHttps(MockWebServerHttpsFactory.createSocketFactory().sslSocketFactory(), false)
        webServer.setDispatcher { request ->
            switch (request.path) {
                case "/jenkins/crumbIssuer/api/json":
                    return new MockResponse().setBody('{"crumb": "the-crumb", "crumbRequestField": "Jenkins-Crumb"}')
                case "/jenkins/scriptText":
                    return new MockResponse().setBody("ok")
                default:
                    return new MockResponse().setStatus("404")
            }
        }
        webServer.start()

        def apiClient = ApplicationContext.run()
                .registerSingleton(new Configuration([
                        application: [ insecure: true ],
                        jenkins: [ url: webServer.url("jenkins"), username: "admin", password: "admin" ]
                ]))
                .getBean(ApiClient)

        def result = apiClient.runScript("println('ok')")
        assertThat(result).isEqualTo("ok")

        def crumbRequest = webServer.takeRequest()
        assertThat(crumbRequest.path).isEqualTo("/jenkins/crumbIssuer/api/json")
        assertThat(crumbRequest.getHeader('Authorization')).startsWith("Basic ")

        def runScriptRequest = webServer.takeRequest()
        assertThat(runScriptRequest.path).isEqualTo("/jenkins/scriptText")
        assertThat(runScriptRequest.getHeader('Authorization')).startsWith("Basic ")
        assertThat(runScriptRequest.getHeader('Jenkins-Crumb')).startsWith("the-crumb")
    }
}
