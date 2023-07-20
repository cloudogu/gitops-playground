package com.cloudogu.gitops.jenkins

import groovy.test.GroovyAssert
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

class PrometheusConfiguratorTest {
    @Test
    void 'enables authentication'() {
        def client = mock(ApiClient)
        def configurator = new PrometheusConfigurator(client)

        when(client.runScript(anyString())).thenReturn("true")
        configurator.enableAuthentication()
        verify(client).runScript(anyString())
    }

    @Test
    void 'throws when enabling fails'() {
        def client = mock(ApiClient)
        def configurator = new PrometheusConfigurator(client)

        when(client.runScript(anyString())).thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]")
        shouldFail(RuntimeException) {
            configurator.enableAuthentication()
        }
    }
}
