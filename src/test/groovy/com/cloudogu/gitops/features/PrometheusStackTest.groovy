package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import groovy.yaml.YamlSlurper
import jakarta.inject.Provider
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat 

class PrometheusStackTest {

    Map config = [
            scmm: [
                    internal: true,
                    host: '',
                    protocol: 'http',
            ],
            jenkins: [
                    internal: true,
                    metricsUsername: 'metrics',
                    metricsPassword: 'metrics',
            ],
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
            ],
            features   : [
                    monitoring: [
                            active: true,
                            grafanaUrl: '',
                            grafanaEmailFrom: 'grafana@example.org',
                            grafanaEmailTo: 'infra@example.org',
                            helm  : [
                                    chart  : 'kube-prometheus-stack',
                                    repoURL: 'https://prom',
                                    version: '19.2.2'
                            ]
                    ],
                    mail   : [
                            mailhog: true,
                            externalMailserver : '',
                            externalMailserverPort : '',
                            externalMailserverUser : '',
                            externalMailserverPassword : ''
                    ]
            ],
    ]
    CommandExecutorForTest helmCommandExecutor = new CommandExecutorForTest()
    CommandExecutorForTest k8sCommandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(helmCommandExecutor)
    Path temporaryYamlFile = null

    @Test
    void "is disabled via active flag"() {
        config['features']['monitoring']['active'] = false
        createStack().install()
        assertThat(temporaryYamlFile).isNull()
        assertThat(k8sCommandExecutor.actualCommands).isEmpty()
        assertThat(helmCommandExecutor.actualCommands).isEmpty()
    }

    @Test
    void 'When mailhog disabled: Does not include mail configurations into cluster resources'() {
        config.features['mail']['mailhog'] = false
        createStack().install()
        assertThat(parseActualStackYaml()['grafana']['notifiers']).isNull()
    }

    @Test
    void 'When mailhog enabled: Includes mail configurations into cluster resources'() {
        config.features['mail']['mailhog'] = true
        createStack().install()
        assertThat(parseActualStackYaml()['grafana']['notifiers']).isNotNull()
    }

    @Test
    void "When Email Addresses is set"() {
        config.features['mail']['mailhog'] = true
        config.features['monitoring']['grafanaEmailFrom'] = 'grafana@example.com'
        config.features['monitoring']['grafanaEmailTo'] = 'infra@example.com'
        createStack().install()

        def notifiersYaml = parseActualStackYaml()['grafana']['notifiers']['notifiers.yaml']['notifiers']['settings'] as List
        assertThat(notifiersYaml[0]['addresses']).isEqualTo('infra@example.com')
        assertThat(parseActualStackYaml()['grafana']['env']['GF_SMTP_FROM_ADDRESS']).isEqualTo('grafana@example.com')
    }

    @Test
    void "When Email Addresses is NOT set"() {
        config.features['mail']['mailhog'] = true
        createStack().install()

        def notifiersYaml = parseActualStackYaml()['grafana']['notifiers']['notifiers.yaml']['notifiers']['settings'] as List
        assertThat(notifiersYaml[0]['addresses']).isEqualTo('infra@example.org')
        assertThat(parseActualStackYaml()['grafana']['env']['GF_SMTP_FROM_ADDRESS']).isEqualTo('grafana@example.org')
    }

    @Test
    void 'When external Mailserver is set'() {
        config.features['mail']['mailhog'] = true
        config.features['mail']['externalMailserver'] = 'smtp.example.com'
        config.features['mail']['externalMailserverPort'] = '1010110'
        config.features['mail']['externalMailserverUser'] = 'mailserver@example.com'
        config.features['mail']['externalMailserverPassword'] = '1101ABCabc&/+*~'
        config.features['monitoring']['grafanaEmailTo'] = 'grafana@example.com'   // needed to check that yaml is inserted correctly

        createStack().install()
        def contactPointsYaml = parseActualStackYaml()

        assert contactPointsYaml['grafana']['alerting']['contactpoints.yaml'] != null
        assert contactPointsYaml['grafana']['alerting']['notification-policies.yaml'] != null
    }

    @Test
    void 'When external Mailserver is NOT set'() {
        config.features['mail']['mailhog'] = false
        createStack().install()
        def contactPointsYaml = parseActualStackYaml()

        assert contactPointsYaml['grafana']['alerting'] == null
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
    void 'uses ingress if enabled'() {
        config['features']['monitoring']['grafanaUrl'] = 'http://grafana.local'
        createStack().install()


        def ingressYaml = parseActualStackYaml()['grafana']['ingress']
        assertThat(ingressYaml['enabled']).isEqualTo(true)
        assertThat((ingressYaml['hosts'] as List)[0]).isEqualTo('grafana.local')
    }

    @Test
    void 'does not use ingress by default'() {
        createStack().install()

        assertThat(parseActualStackYaml()['grafana'] as Map).doesNotContainKey('ingress')
    }

    @Test
    void 'uses remote scmm url if requested'() {
        config.scmm["internal"] = false
        config.scmm["url"] = 'https://localhost:9091/prefix'
        createStack().install()


        def additionalScrapeConfigs = parseActualStackYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
        assertThat(((additionalScrapeConfigs[0]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('localhost:9091')
        assertThat(additionalScrapeConfigs[0]['metrics_path']).isEqualTo('/prefix/scm/api/v2/metrics/prometheus')
        assertThat(additionalScrapeConfigs[0]['scheme']).isEqualTo('https')

        // scrape config for jenkins is unchanged
        assertThat(((additionalScrapeConfigs[1]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('jenkins.default.svc.cluster.local')
        assertThat(additionalScrapeConfigs[1]['scheme']).isEqualTo('http')
        assertThat(additionalScrapeConfigs[1]['metrics_path']).isEqualTo('/prometheus')
    }

    @Test
    void 'uses remote jenkins url if requested'() {
        config.jenkins["internal"] = false
        config.jenkins["url"] = 'https://localhost:9090/jenkins'
        createStack().install()


        def additionalScrapeConfigs = parseActualStackYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
        assertThat(((additionalScrapeConfigs[1]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('localhost:9090')
        assertThat(additionalScrapeConfigs[1]['metrics_path']).isEqualTo('/jenkins/prometheus')
        assertThat(additionalScrapeConfigs[1]['scheme']).isEqualTo('https')

        // scrape config for scmm is unchanged
        assertThat(((additionalScrapeConfigs[0]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('scmm-scm-manager.default.svc.cluster.local')
        assertThat(additionalScrapeConfigs[0]['scheme']).isEqualTo('http')
        assertThat(additionalScrapeConfigs[0]['metrics_path']).isEqualTo('/scm/api/v2/metrics/prometheus')
    }

    @Test
    void 'configures custom metrics user for jenkins'() {
        config.jenkins["metricsUsername"] = 'external-metrics-username'
        config.jenkins["metricsPassword"] = 'hunter2'
        createStack().install()

        assertThat(k8sCommandExecutor.actualCommands[1]).isEqualTo("kubectl create secret generic prometheus-metrics-creds-jenkins -n foo-monitoring --from-literal=password=hunter2 --dry-run=client -oyaml | kubectl apply -f-")
        def additionalScrapeConfigs = parseActualStackYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
        assertThat(additionalScrapeConfigs[1]['basic_auth']['username']).isEqualTo('external-metrics-username')
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

        assertThat(k8sCommandExecutor.actualCommands[0].trim()).isEqualTo(
                'kubectl create secret generic prometheus-metrics-creds-scmm -n foo-monitoring --from-literal=password=123 --dry-run=client -oyaml | kubectl apply -f-')
        assertThat(helmCommandExecutor.actualCommands[0].trim()).isEqualTo(
                'helm repo add prometheusstack https://prom')
        assertThat(helmCommandExecutor.actualCommands[1].trim()).isEqualTo(
                'helm upgrade -i kube-prometheus-stack prometheusstack/kube-prometheus-stack --version 19.2.2' +
                        " --values ${temporaryYamlFile} --namespace foo-monitoring")
    }

    private PrometheusStack createStack() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        def configuration = new Configuration(config)
        new PrometheusStack(configuration, new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                Path ret = super.copyToTempDir(filePath)
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")) // Path after template invocation
                return ret
            }
        }, new HelmStrategy(configuration, helmClient), new K8sClient(k8sCommandExecutor, new FileSystemUtils(), new Provider<Configuration>() {
            @Override
            Configuration get() {
                configuration
            }
        }))
    }

    private parseActualStackYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}
