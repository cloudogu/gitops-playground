package com.cloudogu.gitops.jenkins


import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test

import java.nio.charset.Charset

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class JobManagerTest {
    @Test
    void 'creates credential'() {
        def server = new MockWebServer()
        try {
            server.enqueue(new MockResponse().setBody('{"crumb":"the-crumb"}'))
            server.enqueue(new MockResponse())
            def jobManager = new JobManager(new ApiClient(server.url("jenkins").toString(), 'admin', 'admin', new OkHttpClient()))
            jobManager.createCredential('the-jobname', 'the-id', 'the-username', 'the-password', 'some description')

            assertThat(server.requestCount).isEqualTo(2)
            server.takeRequest() // crumb
            def request = server.takeRequest()
            assertThat(request.path).isEqualTo("/jenkins/job/the-jobname/credentials/store/folder/domain/_/createCredentials")
            assertThat(URLDecoder.decode(request.body.readString(Charset.defaultCharset()), "utf-8")).isEqualTo('json={"credentials":{"scope":"GLOBAL","id":"the-id","username":"the-username","password":"the-password","description":"some description","$class":"com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl"}}')
        } finally {
            server.shutdown()
        }
    }

    @Test
    void 'throw when creating credential fails'() {
        def server = new MockWebServer()
        try {
            server.enqueue(new MockResponse().setBody('{"crumb":"the-crumb"}'))
            server.enqueue(new MockResponse().setResponseCode(404))
            def jobManager = new JobManager(new ApiClient(server.url("jenkins").toString(), 'admin', 'admin', new OkHttpClient()))
            shouldFail(RuntimeException) {
                jobManager.createCredential('the-jobname', 'the-id', 'the-username', 'the-password', 'some description')
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    void 'throws when job contains invalid characters'() {
        def client = mock(ApiClient)
        def jobManager = new JobManager(client)

        shouldFail(RuntimeException) {
            jobManager.deleteJob("foo'foo")
        }
    }

    @Test
    void 'deletes job'() {
        def client = mock(ApiClient)
        def jobManager = new JobManager(client)

        when(client.runScript(anyString())).thenReturn("null")
        jobManager.deleteJob("foo")

        verify(client).runScript("print(Jenkins.instance.getItem('foo')?.delete())")
    }
}
