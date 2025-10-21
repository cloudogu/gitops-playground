package com.cloudogu.gitops.git.providers.scmmanager.api

import com.cloudogu.gitops.common.MockWebServerHttpsFactory
import com.cloudogu.gitops.config.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import javax.net.ssl.SSLHandshakeException

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

class UsersApiTest {
    private MockWebServer webServer = new MockWebServer()
    private Credentials credentials = new Credentials("user", "pass")

    @AfterEach
    void tearDown() {
        webServer.shutdown()
    }

    @Test
    void 'allows self-signed certificates when using insecure option'() {
        webServer.useHttps(MockWebServerHttpsFactory.createSocketFactory().sslSocketFactory(), false)

        def api = usersApi(true)
        webServer.enqueue(new MockResponse().setResponseCode(204))

        def resp = api.delete('test-user').execute()

        assertThat(resp.isSuccessful()).isTrue()
        assertThat(webServer.requestCount).isEqualTo(1)
    }

    @Test
    void 'does not allow self-signed certificates by default'() {
        webServer.useHttps(MockWebServerHttpsFactory.createSocketFactory().sslSocketFactory(), false)

        def api = usersApi(false)

        shouldFail(SSLHandshakeException) {
            api.delete('test-user').execute()
        }
        assertThat(webServer.requestCount).isEqualTo(0)
    }


    private UsersApi usersApi(boolean insecure) {
        def client = new ScmManagerApiClient(apiBaseUrl(), credentials, insecure)
        return client.usersApi()
    }

    private String apiBaseUrl() {
        return "${webServer.url('scm')}/api/"
    }

}