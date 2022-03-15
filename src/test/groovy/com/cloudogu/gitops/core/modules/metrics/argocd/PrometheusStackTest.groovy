package com.cloudogu.gitops.core.modules.metrics.argocd

import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import static org.assertj.core.api.Assertions.assertThat

class PrometheusStackTest {

    public @TempDir
    Path tempDir
    File temporaryStackYamlFile
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
    void "service type LoadBalancer when run remotely"() {
        config['application']['remote'] = true
        PrometheusStack prometheusStack = createStack()
        prometheusStack.configure()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('LoadBalancer');
    }
    
    @Test
    void "service type NodePort when not run remotely"() {
        config['application']['remote'] = false
        PrometheusStack prometheusStack = createStack()
        prometheusStack.configure()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('NodePort');
    }

    private PrometheusStack createStack() {
        new PrometheusStack(config, tempDir.toString())
    }
    
    private parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parseText(ys.parse(temporaryStackYamlFile)['spec']['source']['helm']['values'].toString())
    }
}