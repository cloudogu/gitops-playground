package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.*
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import java.nio.file.Files
import java.nio.file.Path

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

class PrometheusStackTest {
    Config config = new Config(
            registry: new Config.RegistrySchema(
                    internal: true,
                    createImagePullSecrets: false
            ),
            scmm: new Config.ScmmSchema(
                    internal: true,
                    host: '',
                    protocol: 'http',
            ),
            jenkins: new Config.JenkinsSchema(internal: true,
                    metricsUsername: 'metrics',
                    metricsPassword: 'metrics',),
            application: new Config.ApplicationSchema(
                    username: 'abc',
                    password: '123',
                    remote: false,
                    openshift: false,
                    namePrefix: "foo-",
                    mirrorRepos: false,
                    podResources: false,
                    skipCrds: false,
                    namespaceIsolation: false,
                    gitName: 'Cloudogu',
                    gitEmail: 'hello@cloudogu.com',
                    netpols: false
            ),
            features: new Config.FeaturesSchema(
                    argocd: new Config.ArgoCDSchema(active: true),
                    monitoring: new Config.MonitoringSchema(
                            active: true,
                            grafanaUrl: '',
                            grafanaEmailFrom: 'grafana@example.org',
                            grafanaEmailTo: 'infra@example.org',
                            helm: new Config.MonitoringSchema.MonitoringHelmSchema(
                                    chart: 'kube-prometheus-stack',
                                    repoURL: 'https://prom',
                                    version: '19.2.2',

                            )),
                    secrets: new Config.SecretsSchema(active: true),
                    ingressNginx: new Config.IngressNginxSchema(active: true),
                    mail: new Config.MailSchema(
                            mailhog: true,
                    ),

            ),
    )

    K8sClientForTest k8sClient = new K8sClientForTest(config)
    CommandExecutorForTest k8sCommandExecutor = k8sClient.commandExecutorForTest
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    Path temporaryYamlFilePrometheus = null
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    File clusterResourcesRepoDir

    @Test
    void "is disabled via active flag"() {
        config.features.monitoring.active = false
        createStack().install()
        assertThat(temporaryYamlFilePrometheus).isNull()
        assertThat(k8sCommandExecutor.actualCommands).isEmpty()
        verifyNoMoreInteractions(deploymentStrategy)
    }

    @Test
    void 'When mailhog disabled: Does not include mail configurations into cluster resources'() {
        config.features.mail.active = null // user should not do this in real.
        config.features.mail.mailhog = false
        createStack().install()

        def yaml = parseActualYaml()
        assertThat(yaml['grafana']['notifiers']).isNull()
    }

    @Test
    void 'When mailhog enabled: Includes mail configurations into cluster resources'() {
        config.features.mail.active = true
        createStack().install()
        assertThat(parseActualYaml()['grafana']['notifiers']).isNotNull()
    }

    @Test
    void "When Email Addresses is set"() {
        config.features.mail.active = true
        config.features.monitoring.grafanaEmailFrom = 'grafana@example.com'
        config.features.monitoring.grafanaEmailTo = 'infra@example.com'
        createStack().install()

        def notifiersYaml = parseActualYaml()['grafana']['notifiers']['notifiers.yaml']['notifiers']['settings'] as List
        assertThat(notifiersYaml[0]['addresses']).isEqualTo('infra@example.com')
        assertThat(parseActualYaml()['grafana']['env']['GF_SMTP_FROM_ADDRESS']).isEqualTo('grafana@example.com')
    }

    @Test
    void "When Email Addresses is NOT set"() {
        config.features.mail.active = true
        createStack().install()

        def notifiersYaml = parseActualYaml()['grafana']['notifiers']['notifiers.yaml']['notifiers']['settings'] as List
        assertThat(notifiersYaml[0]['addresses']).isEqualTo('infra@example.org')
        assertThat(parseActualYaml()['grafana']['env']['GF_SMTP_FROM_ADDRESS']).isEqualTo('grafana@example.org')
    }

    @Test
    void 'When external Mailserver is set'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpPort = 1010110
        config.features.monitoring.grafanaEmailTo = 'grafana@example.com'
        // needed to check that yaml is inserted correctly

        createStack().install()
        def contactPointsYaml = parseActualYaml()

        assertThat(contactPointsYaml['grafana']['alerting']['contactpoints.yaml']).isEqualTo(new YamlSlurper().parseText(
                """
apiVersion: 1
contactPoints:
- orgId: 1
  name: email
  is_default: true
  receivers:
  - uid: email1
    type: email
    settings:
      addresses: ${config.features.monitoring.grafanaEmailTo}
"""
        )
        )
        assertThat(contactPointsYaml['grafana']['alerting']['notification-policies.yaml']).isEqualTo(new YamlSlurper().parseText(
                '''
apiVersion: 1
policies:
- orgId: 1
  is_default: true
  receiver: email
  routes:
  - receiver: email
  group_by: ["grafana_folder", "alertname"]
'''
        ))

        assertThat(contactPointsYaml['grafana']['env']['GF_SMTP_HOST']).isEqualTo('smtp.example.com:1010110')
    }

    @Test
    void 'When external Mailserver is set with user'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpUser = 'mailserver@example.com'

        createStack().install()

        assertThat(parseActualYaml()['grafana']['smtp']['existingSecret']).isEqualTo('grafana-email-secret')
        k8sCommandExecutor.assertExecuted('kubectl create secret generic grafana-email-secret -n foo-monitoring --from-literal user=mailserver@example.com --from-literal password=')
    }

    @Test
    void 'When external Mailserver is set with password'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpPassword = '1101ABCabc&/+*~'

        createStack().install()
        assertThat(parseActualYaml()['grafana']['smtp']['existingSecret']).isEqualTo('grafana-email-secret')
        k8sCommandExecutor.assertExecuted('kubectl create secret generic grafana-email-secret -n foo-monitoring --from-literal user= --from-literal password=1101ABCabc&/+*~')
    }

    @Test
    void 'When external Mailserver is set without user and password'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'

        createStack().install()

        assertThat(parseActualYaml()['grafana']['valuesFrom']).isNull()
        assertThat(parseActualYaml()['grafana']['smtp']).isNull()
        k8sCommandExecutor.assertNotExecuted('kubectl create secret generic grafana-email-secret')
    }

    @Test
    void 'Check if kubernetes secret will be created when external emailservers credential is set'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpUser = 'grafana@example.com'
        config.features.mail.smtpPassword = '1101ABCabc&/+*~'

        createStack().install()

        k8sCommandExecutor.assertExecuted('kubectl create secret generic grafana-email-secret -n foo-monitoring --from-literal user=grafana@example.com --from-literal password=1101ABCabc&/+*~')
    }

    @Test
    void 'When external Mailserver is set without port'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'

        createStack().install()
        def contactPointsYaml = parseActualYaml()

        assertThat(contactPointsYaml['grafana']['env']['GF_SMTP_HOST']).isEqualTo('smtp.example.com')
    }

    @Test
    void 'When external Mailserver is NOT set'() {
        config.features.mail.active = null // user should not do this in real.
        config.features.mail.mailhog = false
        createStack().install()
        def contactPointsYaml = parseActualYaml()

        assertThat(contactPointsYaml['grafana']['alerting']).isNull()
    }

    @Test
    void "service type LoadBalancer when run remotely"() {
        config.application.remote = true
        createStack().install()

        assertThat(parseActualYaml()['grafana']['service']['type']).isEqualTo('LoadBalancer')
        assertThat(parseActualYaml()['grafana']['service']['nodePort']).isNull()
    }

    @Test
    void "configures admin user if requested"() {
        config.application.username = "my-user"
        config.application.password = "hunter2"
        createStack().install()

        assertThat(parseActualYaml()['grafana']['adminUser']).isEqualTo('my-user')
        assertThat(parseActualYaml()['grafana']['adminPassword']).isEqualTo('hunter2')
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config.application.remote = false
        createStack().install()

        assertThat(parseActualYaml()['grafana']['service']['type']).isEqualTo('NodePort')
        assertThat(parseActualYaml()['grafana']['service']['nodePort']).isEqualTo('9095')
    }

    @Test
    void 'uses ingress if enabled'() {
        config.features.monitoring.grafanaUrl = 'http://grafana.local'
        createStack().install()


        def ingressYaml = parseActualYaml()['grafana']['ingress']
        assertThat(ingressYaml['enabled']).isEqualTo(true)
        assertThat((ingressYaml['hosts'] as List)[0]).isEqualTo('grafana.local')
    }

    @Test
    void 'does not use ingress by default'() {
        createStack().install()

        assertThat(parseActualYaml()['grafana'] as Map).doesNotContainKey('ingress')
    }

    @Test
    void 'uses remote scmm url if requested'() {
        config.scmm.internal = false
        config.scmm.url = 'https://localhost:9091/prefix'
        createStack().install()


        def additionalScrapeConfigs = parseActualYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
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


        def additionalScrapeConfigs = parseActualYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
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

        assertThat(k8sCommandExecutor.actualCommands[1]).isEqualTo("kubectl create secret generic prometheus-metrics-creds-jenkins -n foo-monitoring --from-literal password=hunter2 --dry-run=client -oyaml | kubectl apply -f-")
        def additionalScrapeConfigs = parseActualYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
        assertThat(additionalScrapeConfigs[1]['basic_auth']['username']).isEqualTo('external-metrics-username')
    }

    @Test
    void "configures custom image for grafana"() {
        config.features.monitoring.helm.grafanaImage = "localhost:5000/grafana/grafana:the-tag"
        createStack().install()

        assertThat(parseActualYaml()['grafana']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(parseActualYaml()['grafana']['image']['repository']).isEqualTo('grafana/grafana')
        assertThat(parseActualYaml()['grafana']['image']['tag']).isEqualTo('the-tag')
    }

    @Test
    void "configures custom image for grafana-sidecar"() {
        config.features.monitoring.helm.grafanaSidecarImage = "localhost:5000/grafana/sidecar:the-tag"
        createStack().install()

        assertThat(parseActualYaml()['grafana']['sidecar']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(parseActualYaml()['grafana']['sidecar']['image']['repository']).isEqualTo('grafana/sidecar')
        assertThat(parseActualYaml()['grafana']['sidecar']['image']['tag']).isEqualTo('the-tag')
    }

    @Test
    void "configures custom image for prometheus and operator"() {
        config.features.monitoring.helm.prometheusImage = "localhost:5000/prometheus/prometheus:v1"
        config.features.monitoring.helm.prometheusOperatorImage = "localhost:5000/prometheus-operator/prometheus-operator:v2"
        config.features.monitoring.helm.prometheusConfigReloaderImage = "localhost:5000/prometheus-operator/prometheus-config-reloader:v3"

        createStack().install()

        def actualYaml = parseActualYaml()
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
    void 'deploys image pull secrets for proxy registry'() {
        config.registry.createImagePullSecrets = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'

        createStack().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-monitoring' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        assertThat(parseActualYaml()['global']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
    }

    @Test
    void 'helm release is installed'() {
        createStack().install()

        assertThat(k8sCommandExecutor.actualCommands[0].trim()).isEqualTo(
                'kubectl create secret generic prometheus-metrics-creds-scmm -n foo-monitoring --from-literal password=123 --dry-run=client -oyaml | kubectl apply -f-')

        verify(deploymentStrategy).deployFeature('https://prom', 'prometheusstack',
                'kube-prometheus-stack', '19.2.2', 'monitoring',
                'kube-prometheus-stack', temporaryYamlFilePrometheus)
        /* This corresponds to
                'helm repo add prometheusstack https://prom'
                'helm upgrade -i kube-prometheus-stack prometheusstack/kube-prometheus-stack --version 19.2.2' +
                        " --values ${temporaryYamlFile} --namespace foo-monitoring --create-namespace") */

        def yaml = parseActualYaml()
        assertThat(yaml['grafana']['adminUser']).isEqualTo('abc')
        assertThat(yaml['grafana']['adminPassword']).isEqualTo(123)

        assertThat(yaml['prometheusOperator'] as Map).doesNotContainKey('resources')
        assertThat(yaml['grafana'] as Map).doesNotContainKey('resources')
        assertThat(yaml['grafana']['sidecar'] as Map).doesNotContainKey('resources')
        assertThat(yaml['prometheus']['prometheusSpec'] as Map).doesNotContainKey('resources')

        assertThat(yaml['prometheusOperator']['securityContext']).isNull()
        assertThat(yaml['grafana']['securityContext']).isNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']).isNull()

        assertThat(yaml['kubeApiServer']).isNull()

        assertThat(yaml['prometheusOperator']['admissionWebhooks']['enabled']).isEqualTo(false)
        assertThat(yaml['prometheusOperator']['tls']['enabled']).isEqualTo(false)
        assertThat(yaml['prometheusOperator']['kubeletService']).isNull()
        assertThat(yaml['prometheusOperator']['namespaces']).isNull()
        assertThat(yaml).doesNotContainKey('global')

        assertThat(yaml['grafana']['rbac']).isNull()
        assertThat(yaml['grafana']['sidecar']['dashboards']['searchNamespace']).isEqualTo('ALL')

        assertThat(yaml['crds']).isNull()
        assertThat(new File("$clusterResourcesRepoDir/misc/monitoring/rbac")).doesNotExist()
    }

    @Test
    void 'Skips CRDs'() {
        config.application.skipCrds = true

        createStack().install()

        assertThat(parseActualYaml()['crds']['enabled']).isEqualTo(false)
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createStack().install()

        def yaml = parseActualYaml()
        assertThat(yaml['prometheusOperator']['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(yaml['prometheusOperator']['prometheusConfigReloader']['resources'] as Map).containsKeys('limits', 'requests')
        assertThat(yaml['grafana']['resources'] as Map) containsKeys('limits', 'requests')
        assertThat(yaml['grafana']['sidecar']['resources'] as Map) containsKeys('limits', 'requests')
        assertThat(yaml['prometheus']['prometheusSpec']['resources'] as Map) containsKeys('limits', 'requests')
    }

    @Test
    void 'works with openshift'() {
        config.application.openshift = true

        createStack().install()

        def yaml = parseActualYaml()
        assertThat(yaml['prometheusOperator']['securityContext']).isNotNull()
        assertThat(yaml['prometheusOperator']['securityContext']['fsGroup']).isNull()
        assertThat(yaml['prometheusOperator']['securityContext']['runAsGroup']).isNull()
        assertThat(yaml['prometheusOperator']['securityContext']['runAsUser']).isNull()

        assertThat(yaml['grafana']['securityContext']).isNotNull()
        assertThat(yaml['grafana']['securityContext']['fsGroup']).isEqualTo(1000740000)
        assertThat(yaml['grafana']['securityContext']['runAsGroup']).isEqualTo(1000740000)
        assertThat(yaml['grafana']['securityContext']['runAsUser']).isEqualTo(1000740000)

        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']).isNotNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']['fsGroup']).isNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']['runAsGroup']).isNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']['runAsUser']).isNull()
    }

    @Test
    void 'works with namespaceIsolation'() {
        config.application.namespaceIsolation = true

        def prometheusStack = createStack()
        prometheusStack.install()

        def yaml = parseActualYaml()
        assertThat(yaml['global']['rbac']['create']).isEqualTo(false)

        List<String> expectedNamespaces = ["foo-default", "foo-argocd", "foo-monitoring", "foo-ingress-nginx", "foo-example-apps-staging", "foo-example-apps-production", "foo-secrets"]
        assertThat(prometheusStack.namespaceList.collect { it.toString() }).hasSameElementsAs(expectedNamespaces)

        for (String namespace : prometheusStack.namespaceList) {
            def rbacYaml = new File("$clusterResourcesRepoDir/misc/monitoring/rbac/${namespace}.yaml")
            assertThat(rbacYaml.text).contains("namespace: ${namespace}")
            assertThat(rbacYaml.text).contains("    namespace: foo-monitoring")
        }

        assertThat(yaml['kubeApiServer']['enabled']).isEqualTo(false)

        assertThat(yaml['prometheusOperator']['kubeletService']['enabled']).isEqualTo(false)
        assertThat(yaml['prometheusOperator']['namespaces']['releaseNamespace']).isEqualTo(false)
        assertThat(yaml['prometheusOperator']['namespaces']['additional'] as List).hasSameElementsAs(expectedNamespaces)

        assertThat(yaml['grafana']['rbac']['create']).isEqualTo(false)
        assertThat(yaml['grafana']['sidecar']['dashboards']['searchNamespace']).isEqualTo(prometheusStack.namespaceList.join(','))
    }

    @Test
    void 'network policies are created for prometheus'() {
        config.application.netpols = true
        def prometheusStack = createStack()
        prometheusStack.install()

        for (String namespace : prometheusStack.namespaceList) {
            def netPolsYaml = new File("$clusterResourcesRepoDir/misc/monitoring/netpols/${namespace}.yaml")
            assertThat(netPolsYaml.text).contains("namespace: ${namespace}")
        }
    }

    @Test
    void 'helm releases are installed in air-gapped mode'() {
        config.application.mirrorRepos = true
        when(airGappedUtils.mirrorHelmRepoToGit(any(Config.HelmConfig))).thenReturn('a/b')

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path prometheusSourceChart = rootChartsFolder.resolve('kube-prometheus-stack')
        Files.createDirectories(prometheusSourceChart)

        Map prometheusChartYaml = [version: '1.2.3']
        fileSystemUtils.writeYaml(prometheusChartYaml, prometheusSourceChart.resolve('Chart.yaml').toFile())

        createStack().install()

        def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('kube-prometheus-stack')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://prom')
        assertThat(helmConfig.value.version).isEqualTo('19.2.2')
        verify(deploymentStrategy).deployFeature(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/a/b',
                'prometheusstack', '.', '1.2.3', 'monitoring',
                'kube-prometheus-stack', temporaryYamlFilePrometheus, RepoType.GIT)
    }

    @Test
    void 'Merges additional helm values merged with default values'() {
        config.features.monitoring.helm.values = [
                key: [
                        some: 'thing',
                        one : 1
                ]
        ]

        createStack().install()
        def actual = parseActualYaml()

        assertThat(actual['key']['some']).isEqualTo('thing')
        assertThat(actual['key']['one']).isEqualTo(1)
    }

    private PrometheusStack createStack() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        def configuration = config
        def repoProvider = new TestScmmRepoProvider(config, new FileSystemUtils()) {
            @Override
            ScmmRepo getRepo(String repoTarget) {
                def repo = super.getRepo(repoTarget)
                clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir())

                return repo
            }
        }

        new PrometheusStack(configuration, new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFilePrometheus = Path.of(ret.toString().replace(".ftl", ""))
                // Path after template invocation

                return ret
            }
        }, deploymentStrategy, k8sClient, airGappedUtils, repoProvider)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFilePrometheus) as Map
    }
}
