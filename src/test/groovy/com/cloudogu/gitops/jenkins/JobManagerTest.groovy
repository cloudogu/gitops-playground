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
            def exception = shouldFail(RuntimeException) {
                jobManager.createCredential('the-jobname', 'the-id', 'the-username', 'the-password', 'some description')
            }
            assertThat(exception.getMessage()).isEqualTo('Could not create credential id=the-id,job=the-jobname. StatusCode: 404')
        } finally {
            server.shutdown()
        }
    }

    @Test
    void 'starts job'() {
        def server = new MockWebServer()
        try {
            server.enqueue(new MockResponse().setBody('{"crumb":"the-crumb"}'))
            server.enqueue(new MockResponse().setResponseCode(200))
            def jobManager = new JobManager(new ApiClient(server.url("jenkins").toString(), 'admin', 'admin', new OkHttpClient()))
            jobManager.startJob('the-jobname')

            assertThat(server.requestCount).isEqualTo(2)
            server.takeRequest() // crumb
            def request = server.takeRequest()
            assertThat(request.path).isEqualTo("/jenkins/job/the-jobname/build?delay=0sec")
        } finally {
            server.shutdown()
        }
    }

    @Test
    void 'throw when starting job fails'() {
        def server = new MockWebServer()
        try {
            server.enqueue(new MockResponse().setBody('{"crumb":"the-crumb"}'))
            server.enqueue(new MockResponse().setResponseCode(400))
            def jobManager = new JobManager(new ApiClient(server.url("jenkins").toString(), 'admin', 'admin', new OkHttpClient()))
            def exception = shouldFail(RuntimeException) {
                jobManager.startJob('the-jobname')
            }
            assertThat(exception.getMessage()).isEqualTo('Could not trigger build of Jenkins job: the-jobname. StatusCode: 400')
        } finally {
            server.shutdown()
        }
    }


    @Test
    void 'throws when job contains invalid characters'() {
        def client = mock(ApiClient)
        def jobManager = new JobManager(client)

        def exception = shouldFail(RuntimeException) {
            jobManager.deleteJob("foo'foo")
        }
        assertThat(exception.getMessage()).isEqualTo('Job name cannot contain quotes.')
    }
    
    @Test
    void 'throws when job deletion fails'() {
        def client = mock(ApiClient)
        def jobManager = new JobManager(client)

        def exception = shouldFail(RuntimeException) {
            jobManager.deleteJob("foo-foo")
        }
        assertThat(exception.getMessage()).isEqualTo('Could not delete job foo-foo')
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
