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
    void "configures admin user if requested"() {
        config['application']['username'] = "my-user"
        config['application']['password'] = "hunter2"
        createStack().install()

        assertThat(parseActualStackYaml()['grafana']['adminUser']).isEqualTo('my-user')
        assertThat(parseActualStackYaml()['grafana']['adminPassword']).isEqualTo('hunter2')
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createStack().install()

        assertThat(parseActualStackYaml()['grafana']['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void "configures custom image for grafana"() {
        config['features']['monitoring']['helm']['grafanaImage'] = "localhost:5000/grafana/grafana:the-tag"
        createStack().install()

        assertThat(parseActualStackYaml()['grafana']['image']['repository']).isEqualTo('localhost:5000/grafana/grafana')
        assertThat(parseActualStackYaml()['grafana']['image']['tag']).isEqualTo('the-tag')
    }

    @Test
    void "configures custom image for grafana-sidecar"() {
        config['features']['monitoring']['helm']['grafanaSidecarImage'] = "localhost:5000/grafana/sidecar:the-tag"
        createStack().install()

        assertThat(parseActualStackYaml()['grafana']['sidecar']['image']['repository']).isEqualTo('localhost:5000/grafana/sidecar')
        assertThat(parseActualStackYaml()['grafana']['sidecar']['image']['tag']).isEqualTo('the-tag')
    }

    @Test
    void "configures custom image for prometheus and operator"() {
        config['features']['monitoring']['helm']['prometheusImage'] = "localhost:5000/prometheus/prometheus:v1"
        config['features']['monitoring']['helm']['prometheusOperatorImage'] = "localhost:5000/prometheus-operator/prometheus-operator:v2"
        config['features']['monitoring']['helm']['prometheusConfigReloaderImage'] = "localhost:5000/prometheus-operator/prometheus-config-reloader:v3"
        createStack().install()


        def actualYaml = parseActualStackYaml()
        assertThat(actualYaml['prometheus']['prometheusSpec']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(actualYaml['prometheus']['prometheusSpec']['image']['repository']).isEqualTo('prometheus/prometheus')
        assertThat(actualYaml['prometheus']['prometheusSpec']['image']['tag']).isEqualTo('v1')
        assertThat(actualYaml['prometheusOperator']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(actualYaml['prometheusOperator']['image']['repository']).isEqualTo('prometheus-operator/prometheus-operator')
        assertThat(actualYaml['prometheusOperator']['image']['tag']).isEqualTo('v2')
        assertThat(actualYaml['prometheusOperator']['prometheusConfigReloader']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(actualYaml['prometheusOperator']['prometheusConfigReloader']['image']['repository']).isEqualTo('prometheus-operator/prometheus-config-reloader')
        assertThat(actualYaml['prometheusOperator']['prometheusConfigReloader']['image']['tag']).isEqualTo('v3')
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
