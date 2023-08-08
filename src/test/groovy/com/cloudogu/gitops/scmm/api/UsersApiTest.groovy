package com.cloudogu.gitops.scmm.api

import com.cloudogu.gitops.common.MockWebServerHttpsFactory
import com.cloudogu.gitops.config.Configuration
import io.micronaut.context.ApplicationContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit

import javax.net.ssl.SSLHandshakeException

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

class UsersApiTest {
    private MockWebServer webServer = new MockWebServer()

    @AfterEach
    void tearDown() {
        webServer.shutdown()
    }

    @Test
    void 'allows self-signed certificates when using insecure option'() {
        webServer.useHttps(MockWebServerHttpsFactory.createSocketFactory().sslSocketFactory(), false)

        def retrofit = ApplicationContext.run()
                .registerSingleton(new Configuration([
                        application: [ insecure: true ],
                        scmm: [ url: webServer.url("scm"), username: "admin", password: "admin" ]
                ]))
                .getBean(Retrofit)

        def usersApi = retrofit.create(UsersApi)

        webServer.enqueue(new MockResponse())
        usersApi.delete('test-user').execute()
        assertThat(webServer.requestCount).isEqualTo(1)
    }

    @Test
    void 'does not allow self-signed certificates by default'() {
        webServer.useHttps(MockWebServerHttpsFactory.createSocketFactory().sslSocketFactory(), false)

        def retrofit = ApplicationContext.run()
                .registerSingleton(new Configuration([
                        application: [ insecure: false ],
                        scmm: [ url: webServer.url("scm"), username: "admin", password: "admin" ]
                ]))
                .getBean(Retrofit)

        def usersApi = retrofit.create(UsersApi)
        shouldFail(SSLHandshakeException) {
            usersApi.delete('test-user').execute()
        }
    }

}
