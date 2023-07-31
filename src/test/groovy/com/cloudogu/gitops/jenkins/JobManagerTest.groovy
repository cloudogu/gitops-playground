package com.cloudogu.gitops.jenkins

import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class JobManagerTest {
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
