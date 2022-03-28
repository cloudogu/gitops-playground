package com.cloudogu.gitops.core.modules.metrics.argocd

import com.cloudogu.gitops.core.clients.k8s.K8sClient
import com.cloudogu.gitops.core.utils.FileSystemUtils
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock

class PrometheusStackTest {

    public @TempDir
    Path tempDir
    File temporaryStackYamlFile
    private K8sClient k8sClient = mock(K8sClient.class)
    
    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false
            ],
            modules    : [metrics: true]
    ]

    @BeforeEach
    void setup() {
        File originalStackYamlFile = new File("${System.properties['user.dir']}/argocd/control-app/${PrometheusStack.STACK_YAML_PATH}")
        temporaryStackYamlFile = new File("${tempDir.toString()}/${PrometheusStack.STACK_YAML_PATH}")
        temporaryStackYamlFile.mkdirs()
        Files.copy(originalStackYamlFile.toPath(), temporaryStackYamlFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    
    
    @Test
    void "ignore remote flag when metrics off"() {
        config['application']['remote'] = true
        config['modules'] = [metrics: false]
        PrometheusStack prometheusStack = createStack()
        prometheusStack.configure()
        
        // No exception means success
        // Otherwise: java.io.FileNotFoundException: /tmp/.../applications/application-kube-prometheus-stack-helm.yaml (No such file or directory)
    }
    
    @Test
    void "service type LoadBalancer when run remotely"() {
        config['application']['remote'] = true
        createStack().configure()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('LoadBalancer')
    }
    
    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createStack().configure()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('NodePort')
    }

    private PrometheusStack createStack() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        new PrometheusStack(config, tempDir.toString(), new FileSystemUtils(), k8sClient)
    }
    
    private parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parseText(ys.parse(temporaryStackYamlFile)['spec']['source']['helm']['values'].toString())
    }
}