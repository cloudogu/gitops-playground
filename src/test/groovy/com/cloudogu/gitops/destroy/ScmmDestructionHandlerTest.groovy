package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.scmm.api.UsersApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit

import static org.assertj.core.api.Assertions.assertThat

class ScmmDestructionHandlerTest {

    private Retrofit retrofit
    private MockWebServer mockWebServer

    @BeforeEach
    void setUp() {
        mockWebServer = new MockWebServer()
        retrofit = new Retrofit.Builder()
                .baseUrl(mockWebServer.url(""))
                .build()
    }

    @AfterEach
    void tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    void 'destroys all'() {
        def destructionHandler = new ScmmDestructionHandler(new Configuration([application: [namePrefix: 'foo-']]), retrofit.create(UsersApi), retrofit.create(RepositoryApi))

        for (def i = 0; i < 1 /* user */ + 13 /* repositories */; i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(204))
        }
        destructionHandler.destroy()

        def request = mockWebServer.takeRequest()
        assertThat(request.requestUrl.encodedPath()).isEqualTo("/v2/users/foo-gitops")
        assertThat(request.method).isEqualTo("DELETE")

        for (def i = 0; i < 13; ++i) {
            request = mockWebServer.takeRequest()
            assertThat(request.method).isEqualTo("DELETE")
            assertThat(request.requestUrl.encodedPath()).matches(~/^\/v2\/repositories\/.*\/.*$/)
        }
    }
}
