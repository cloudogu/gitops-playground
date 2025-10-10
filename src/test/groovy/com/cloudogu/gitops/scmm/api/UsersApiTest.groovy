package com.cloudogu.gitops.scmm.api

import com.cloudogu.gitops.common.MockWebServerHttpsFactory
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.scmmanager.api.UsersApi
import io.micronaut.context.ApplicationContext
import io.micronaut.inject.qualifiers.Qualifiers
import okhttp3.OkHttpClient
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

        OkHttpClient okHttpClient = ApplicationContext.run()
                .registerSingleton(new Config(application: new Config.ApplicationSchema(insecure: true)))
                .getBean(OkHttpClient.class, Qualifiers.byName("scmm"))
        def usersApi = retrofit(okHttpClient).create(UsersApi)

        webServer.enqueue(new MockResponse())
        usersApi.delete('test-user').execute()
        assertThat(webServer.requestCount).isEqualTo(1)
    }

    @Test
    void 'does not allow self-signed certificates by default'() {
        webServer.useHttps(MockWebServerHttpsFactory.createSocketFactory().sslSocketFactory(), false)

        def usersApi = retrofit().create(UsersApi)
        shouldFail(SSLHandshakeException) {
            usersApi.delete('test-user').execute()
        }
    }

    private Retrofit retrofit(OkHttpClient okHttpClient = null) {
        def builder = new Retrofit.Builder()
                .baseUrl("${webServer.url("scm")}/api/")
        if (okHttpClient)
            builder = builder.client(okHttpClient)
        builder.build()
    }
}