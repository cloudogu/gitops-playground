package com.cloudogu.gitops.features

import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat 

class PrometheusStackTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false
            ],
            features   : [
                    monitoring: [
                            active: true,
                            helm  : [
                                    chart  : 'kube-prometheus-stack',
                                    repoURL: 'https://prom',
                                    version: '19.2.2'
                            ]
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(commandExecutor)
    Path temporaryYamlFile = null

    @Test
    void "is disabled via active flag"() {
        config['features']['monitoring']['active'] = false
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

    @Test
    void 'helm release is installed'() {
        createStack().install()
     
        assertThat(commandExecutor.actualCommands[0].trim()).isEqualTo(
                'helm repo add prometheusstack https://prom')
        assertThat(commandExecutor.actualCommands[1].trim()).isEqualTo(
                'helm upgrade -i kube-prometheus-stack prometheusstack/kube-prometheus-stack --version 19.2.2' +
                        " --values ${temporaryYamlFile} --namespace monitoring")
    }

    private PrometheusStack createStack() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        new PrometheusStack(config, new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                temporaryYamlFile = super.copyToTempDir(filePath)
                return temporaryYamlFile
            }
        }, new HelmStrategy(helmClient))
    }

    private parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}
