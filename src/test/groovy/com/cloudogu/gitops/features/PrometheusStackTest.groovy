package com.cloudogu.gitops.features

import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock 

class PrometheusStackTest {

    
    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false
            ],
            features    : [metrics: true]
    ]
    K8sClient k8sClient = mock(K8sClient.class)
    HelmClient helmClient = mock(HelmClient.class)
    Path temporaryYamlFile = null

    @Test
    void "is disabled via metrics flag"() {
        config['features']['metrics'] = false
        createStack().install()
        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void "service type LoadBalancer when run remotely"() {
        config['application']['remote'] = true
        createStack().install()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('LoadBalancer')
    }
    
    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createStack().install()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('NodePort')
    }

    private PrometheusStack createStack() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        new PrometheusStack(config, new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                temporaryYamlFile = super.copyToTempDir(filePath)
                return temporaryYamlFile
            }
        }, k8sClient, helmClient)
    }
    
    private parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}