package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitHandling.providers.scmmanager.api.ScmmApiClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import retrofit2.Retrofit

import static org.assertj.core.api.Assertions.assertThat 

class ScmmDestructionHandlerTest {

    private ScmmApiClient scmmApiClient
    private MockWebServer mockWebServer

    @BeforeEach
    void setUp() {
        mockWebServer = new MockWebServer()
        def retrofit = new Retrofit.Builder()
                .baseUrl(mockWebServer.url(""))
                .build()
        scmmApiClient = new ScmmApiClient(Mockito.mock(Config), Mockito.mock(OkHttpClient)) {
            @Override
            protected Retrofit retrofit() {
                return retrofit
            }
        }
    }

    @AfterEach
    void tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    void 'destroys all'() {
        def destructionHandler = new ScmmDestructionHandler(Config.fromMap( [application: [namePrefix: 'foo-']]), scmmApiClient)

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
