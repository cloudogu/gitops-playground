package com.cloudogu.gitops.jenkins

import com.cloudogu.gitops.utils.InMemoryCookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

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

        def httpClient = new OkHttpClient.Builder().cookieJar(new InMemoryCookieJar()).build()
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
}
