package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.git.TestGitRepoFactory
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.git.ScmManagerMock
import com.cloudogu.gitops.utils.*
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

import java.nio.file.Files
import java.nio.file.Path

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

class PrometheusStackTest {
    Config config = Config.fromMap(
            registry: [
                    internal              : true,
                    createImagePullSecrets: false
            ],
            scm: [
                    scmManager: [
                            internal: true
                    ]
            ],
            jenkins: [
                    internal       : true,
                    active: true,
                    metricsUsername: 'metrics',
                    metricsPassword: 'metrics',
            ],
            application: [
                    username          : 'abc',
                    password          : '123',
                    remote            : false,
                    openshift         : false,
                    namePrefix        : 'foo-',
                    mirrorRepos       : false,
                    podResources      : false,
                    skipCrds          : false,
                    namespaceIsolation: false,
                    gitName           : 'Cloudogu',
                    gitEmail          : 'hello@cloudogu.com',
                    netpols           : false,
                    namespaces        : [
                            dedicatedNamespaces: [
                                    "test1-default",
                                    "test1-argocd",
                                    "test1-monitoring",
                                    "test1-secrets"
                            ] as LinkedHashSet,
                            tenantNamespaces   : [
                                    "test1-example-apps-staging",
                                    "test1-example-apps-production"
                            ] as LinkedHashSet
                    ]
            ],
            features: [
                    argocd      : [
                            active: true
                    ],
                    monitoring  : [
                            active          : true,
                            grafanaUrl      : '',
                            grafanaEmailFrom: 'grafana@example.org',
                            grafanaEmailTo  : 'infra@example.org',
                            helm            : [
                                    chart  : 'kube-prometheus-stack',
                                    repoURL: 'https://prom',
                                    version: '19.2.2'
                            ]
                    ],
                    secrets     : [
                            active: true
                    ],
                    ingressNginx: [
                            active: true
                    ],
                    mail        : [
                            mailhog: true
                    ]
            ])

    K8sClientForTest k8sClient = new K8sClientForTest(config)
    CommandExecutorForTest k8sCommandExecutor = k8sClient.commandExecutorForTest
    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)
    AirGappedUtils airGappedUtils = mock(AirGappedUtils)
    Path temporaryYamlFilePrometheus = null
    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    File clusterResourcesRepoDir

    GitHandler gitHandler = mock(GitHandler.class)
    ScmManagerMock scmManagerMock

    @BeforeEach
    void setup() {
        scmManagerMock = new ScmManagerMock()
    }


    @Test
    void "is disabled via active flag"() {
        config.features.monitoring.active = false
        createStack(scmManagerMock).install()
        assertThat(temporaryYamlFilePrometheus).isNull()
        assertThat(k8sCommandExecutor.actualCommands).isEmpty()
        verifyNoMoreInteractions(deploymentStrategy)
    }

    @Test
    void 'When mailhog disabled: Does not include mail configurations into cluster resources'() {
        config.features.mail.active = null // user should not do this in real.
        config.features.mail.mailhog = false
        createStack(scmManagerMock).install()

        def yaml = parseActualYaml()
        assertThat(yaml['grafana']['notifiers']).isNull()
    }

    @Test
    void 'When mailhog enabled: Includes mail configurations into cluster resources'() {
        config.features.mail.active = true
        createStack(scmManagerMock).install()
        assertThat(parseActualYaml()['grafana']['notifiers']).isNotNull()
    }

    @Test
    void "When Email Addresses is set"() {
        config.features.mail.active = true
        config.features.monitoring.grafanaEmailFrom = 'grafana@example.com'
        config.features.monitoring.grafanaEmailTo = 'infra@example.com'
        createStack(scmManagerMock).install()

        def notifiersYaml = parseActualYaml()['grafana']['notifiers']['notifiers.yaml']['notifiers']['settings'] as List
        assertThat(notifiersYaml[0]['addresses']).isEqualTo('infra@example.com')
        assertThat(parseActualYaml()['grafana']['env']['GF_SMTP_FROM_ADDRESS']).isEqualTo('grafana@example.com')
    }

    @Test
    void "When Email Addresses is NOT set"() {
        config.features.mail.active = true
        createStack(scmManagerMock).install()

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

        createStack(scmManagerMock).install()
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

        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana']['smtp']['existingSecret']).isEqualTo('grafana-email-secret')
        k8sCommandExecutor.assertExecuted('kubectl create secret generic grafana-email-secret -n foo-monitoring --from-literal user=mailserver@example.com --from-literal password=')
    }

    @Test
    void 'When external Mailserver is set with password'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpPassword = '1101ABCabc&/+*~'

        createStack(scmManagerMock).install()
        assertThat(parseActualYaml()['grafana']['smtp']['existingSecret']).isEqualTo('grafana-email-secret')
        k8sCommandExecutor.assertExecuted('kubectl create secret generic grafana-email-secret -n foo-monitoring --from-literal user= --from-literal password=1101ABCabc&/+*~')
    }

    @Test
    void 'When external Mailserver is set without user and password'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'

        createStack(scmManagerMock).install()

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

        createStack(scmManagerMock).install()

        k8sCommandExecutor.assertExecuted('kubectl create secret generic grafana-email-secret -n foo-monitoring --from-literal user=grafana@example.com --from-literal password=1101ABCabc&/+*~')
    }

    @Test
    void 'When external Mailserver is set without port'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'

        createStack(scmManagerMock).install()
        def contactPointsYaml = parseActualYaml()

        assertThat(contactPointsYaml['grafana']['env']['GF_SMTP_HOST']).isEqualTo('smtp.example.com')
    }

    @Test
    void 'When external Mailserver is NOT set'() {
        config.features.mail.active = null // user should not do this in real.
        config.features.mail.mailhog = false
        createStack(scmManagerMock).install()
        def contactPointsYaml = parseActualYaml()

        assertThat(contactPointsYaml['grafana']['alerting']).isNull()
    }

    @Test
    void "service type LoadBalancer when run remotely"() {
        config.application.remote = true
        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana']['service']['type']).isEqualTo('LoadBalancer')
        assertThat(parseActualYaml()['grafana']['service']['nodePort']).isNull()
    }

    @Test
    void "configures admin user if requested"() {
        config.application.username = "my-user"
        config.application.password = "hunter2"
        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana']['adminUser']).isEqualTo('my-user')
        assertThat(parseActualYaml()['grafana']['adminPassword']).isEqualTo('hunter2')
    }

    @Test
    void 'service type ClusterIP when not run remotely'() {
        config.application.remote = false
        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana']['service']['type']).isEqualTo('ClusterIP')
    }



    @Test
    void 'uses ingress if enabled'() {
        config.features.monitoring.grafanaUrl = 'http://grafana.local'

        createStack(scmManagerMock).install()

        def serviceYaml = parseActualYaml()['grafana']['ingress']
        assertThat(serviceYaml['enabled']).isEqualTo(true)
        assertThat((serviceYaml['hosts'] as List)[0]).isEqualTo('grafana.local')
    }

    @Test
    void 'does not use ingress by default'() {
        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana'] as Map).doesNotContainKey('ingress')
    }

    @Test
    void 'cleanupUnusedDashboards removes all dashboards for disabled features'() {
        config.features.monitoring.active = true
        config.features.ingressNginx.active = false
        config.jenkins.active = false
        config.scm.scmManager.url = null   // triggers scmm dashboard cleanup

        createStack(scmManagerMock).install()

        File dashboardDir = new File(clusterResourcesRepoDir, "apps/prometheusstack/misc/dashboard")

        assertThat(new File(dashboardDir, "ingress-nginx-dashboard.yaml")).doesNotExist()
        assertThat(new File(dashboardDir, "ingress-nginx-dashboard-requests-handling.yaml")).doesNotExist()
        assertThat(new File(dashboardDir, "jenkins-dashboard.yaml")).doesNotExist()
        assertThat(new File(dashboardDir, "scmm-dashboard.yaml")).doesNotExist()
    }

    @Test
    void 'Applies Prometheus ServiceMonitor CRD from file before installing (air-gapped mode)'() {
        // Arrange
        config.features.monitoring.active = true
        config.application.mirrorRepos = true
        config.application.skipCrds = false

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path crdFile = rootChartsFolder.resolve(
                "${config.features.monitoring.helm.chart}/charts/crds/crds/crd-servicemonitors.yaml"
        )
        Files.createDirectories(crdFile.parent)
        Files.writeString(crdFile, "dummy") // content can be anything for this test

        Path chartYaml = rootChartsFolder.resolve("${config.features.monitoring.helm.chart}/Chart.yaml")
        Files.createDirectories(chartYaml.parent)
        Files.writeString(chartYaml, "apiVersion: v2\nname: kube-prometheus-stack\nversion: 42.0.3\n")

        createStack(scmManagerMock).install()
        k8sCommandExecutor.assertExecuted("kubectl apply -f ${crdFile}")

    }

    @Test
    void 'Applies Prometheus ServiceMonitor CRD from GitHub before installing'() {
        config.features.monitoring.active = true
        config.application.mirrorRepos = false      // optional, but makes intent explicit
        config.application.skipCrds = false         // optional, but makes intent explicit

        createStack(scmManagerMock).install()

        k8sCommandExecutor.assertExecuted(
                "kubectl apply -f https://raw.githubusercontent.com/prometheus-community/helm-charts/" +
                        "kube-prometheus-stack-${config.features.monitoring.helm.version}/" +
                        "charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml"
        )
    }

    @Test
    void 'does not apply ServiceMonitor CRD when monitoring is disabled'() {
        config.features.monitoring.active = false     // important
        config.application.skipCrds = false           // so it would apply if enabled
        config.application.mirrorRepos = false        // avoid local chart access

        createStack(scmManagerMock).install()

        // no CRD apply should happen at all
        k8sCommandExecutor.assertNotExecuted('kubectl apply -f https://raw.githubusercontent.com/prometheus-community/helm-charts/')
    }

    @Test
    void 'uses remote scmm url if requested'() {
        createStack(scmManagerMock).install()

        def additionalScrapeConfigs = parseActualYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
        assertThat(((additionalScrapeConfigs[0]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('localhost:8080')
        assertThat(additionalScrapeConfigs[0]['metrics_path']).isEqualTo('/scm/api/v2/metrics/prometheus')
        assertThat(additionalScrapeConfigs[0]['scheme']).isEqualTo('http')

        // scrape config for jenkins is unchanged
        assertThat(((additionalScrapeConfigs[1]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('jenkins.foo-jenkins.svc.cluster.local')
        assertThat(additionalScrapeConfigs[1]['scheme']).isEqualTo('http')
        assertThat(additionalScrapeConfigs[1]['metrics_path']).isEqualTo('/prometheus')
    }

    @Test
    void 'uses remote jenkins url if requested'() {
        config.jenkins["internal"] = false
        config.jenkins["url"] = 'https://localhost:9090/jenkins'
        createStack(scmManagerMock).install()
        def additionalScrapeConfigs = parseActualYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List

        // scrape config for scmm is unchanged
        assertThat(((additionalScrapeConfigs[0]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('localhost:8080')
        assertThat(additionalScrapeConfigs[0]['scheme']).isEqualTo('http')
        assertThat(additionalScrapeConfigs[0]['metrics_path']).isEqualTo('/scm/api/v2/metrics/prometheus')


        assertThat(((additionalScrapeConfigs[1]['static_configs'] as List)[0]['targets'] as List)[0]).isEqualTo('localhost:9090')
        assertThat(additionalScrapeConfigs[1]['metrics_path']).isEqualTo('/jenkins/prometheus')
        assertThat(additionalScrapeConfigs[1]['scheme']).isEqualTo('https')
    }

    @Test
    void 'configures custom metrics user for jenkins'() {
        config.jenkins["metricsUsername"] = 'external-metrics-username'
        config.jenkins["metricsPassword"] = 'hunter2'
        createStack(scmManagerMock).install()

        assertThat(k8sCommandExecutor.actualCommands[1]).isEqualTo("kubectl create secret generic prometheus-metrics-creds-jenkins -n foo-monitoring --from-literal password=hunter2 --dry-run=client -oyaml | kubectl apply -f-")
        def additionalScrapeConfigs = parseActualYaml()['prometheus']['prometheusSpec']['additionalScrapeConfigs'] as List
        assertThat(additionalScrapeConfigs[1]['basic_auth']['username']).isEqualTo('external-metrics-username')
    }

    @Test
    void "configures custom image for grafana"() {
        config.features.monitoring.helm.grafanaImage = "localhost:5000/grafana/grafana:the-tag"
        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(parseActualYaml()['grafana']['image']['repository']).isEqualTo('grafana/grafana')
        assertThat(parseActualYaml()['grafana']['image']['tag']).isEqualTo('the-tag')
    }

    @Test
    void "configures custom image for grafana-sidecar"() {
        config.features.monitoring.helm.grafanaSidecarImage = "localhost:5000/grafana/sidecar:the-tag"
        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['grafana']['sidecar']['image']['registry']).isEqualTo('localhost:5000')
        assertThat(parseActualYaml()['grafana']['sidecar']['image']['repository']).isEqualTo('grafana/sidecar')
        assertThat(parseActualYaml()['grafana']['sidecar']['image']['tag']).isEqualTo('the-tag')
    }

    @Test
    void "configures custom image for prometheus and operator"() {
        config.features.monitoring.helm.prometheusImage = "localhost:5000/prometheus/prometheus:v1"
        config.features.monitoring.helm.prometheusOperatorImage = "localhost:5000/prometheus-operator/prometheus-operator:v2"
        config.features.monitoring.helm.prometheusConfigReloaderImage = "localhost:5000/prometheus-operator/prometheus-config-reloader:v3"

        createStack(scmManagerMock).install()

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

        createStack(scmManagerMock).install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-monitoring' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        assertThat(parseActualYaml()['global']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])
    }

    @Test
    void 'helm release is installed'() {
        createStack(scmManagerMock).install()

        assertThat(k8sCommandExecutor.actualCommands[0].trim()).isEqualTo(
                'kubectl create secret generic prometheus-metrics-creds-scmm -n foo-monitoring --from-literal password=123 --dry-run=client -oyaml | kubectl apply -f-')

        verify(deploymentStrategy).deployFeature('https://prom', 'prometheusstack',
                'kube-prometheus-stack', '19.2.2', 'foo-monitoring',
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

        createStack(scmManagerMock).install()

        assertThat(parseActualYaml()['crds']['enabled']).isEqualTo(false)
    }

    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createStack(scmManagerMock).install()

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
        // Prepare UID
        String realoutput = '{"app.kubernetes.io/created-by":"Internal OpenShift","openshift.io/description":"","openshift.io/display-name":"","openshift.io/requester":"myUser@mydomain.de","openshift.io/sa.scc.mcs":"s0:c30,c25","openshift.io/sa.scc.supplemental-groups":"1000920000/10000","openshift.io/sa.scc.uid-range":"1000920000/10000","project-type":"customer"}'
        k8sCommandExecutor.enqueueOutput(new CommandExecutor.Output('', realoutput, 0))

        createStack(scmManagerMock).install()

        def yaml = parseActualYaml()
        assertThat(yaml['prometheusOperator']['securityContext']).isNotNull()
        assertThat(yaml['prometheusOperator']['securityContext']['fsGroup']).isNull()
        assertThat(yaml['prometheusOperator']['securityContext']['runAsGroup']).isNull()
        assertThat(yaml['prometheusOperator']['securityContext']['runAsUser']).isNull()

        assertThat(yaml['grafana']['securityContext']).isNotNull()
        assertThat(yaml['grafana']['securityContext']['fsGroup']).isEqualTo(1000920000)
        assertThat(yaml['grafana']['securityContext']['runAsGroup']).isEqualTo(1000920000)
        assertThat(yaml['grafana']['securityContext']['runAsUser']).isEqualTo(1000920000)

        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']).isNotNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']['fsGroup']).isNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']['runAsGroup']).isNull()
        assertThat(yaml['prometheus']['prometheusSpec']['securityContext']['runAsUser']).isNull()
    }

    @Test
    void 'works with namespaceIsolation'() {
        config.application.namespaceIsolation = true

        def prometheusStack = createStack(scmManagerMock)
        prometheusStack.install()

        def yaml = parseActualYaml()
        assertThat(yaml['global']['rbac']['create']).isEqualTo(false)

        for (String namespace : config.application.namespaces.getActiveNamespaces()) {
            def rbacYaml = new File("$clusterResourcesRepoDir/apps/prometheusstack/misc/rbac/${namespace}.yaml")
            assertThat(rbacYaml.text).contains("namespace: ${namespace}")
            assertThat(rbacYaml.text).contains("    namespace: foo-monitoring")
        }

        assertThat(yaml['kubeApiServer']['enabled']).isEqualTo(false)

        assertThat(yaml['prometheusOperator']['kubeletService']['enabled']).isEqualTo(false)
        assertThat(yaml['prometheusOperator']['namespaces']['releaseNamespace']).isEqualTo(false)
        assertThat(yaml['prometheusOperator']['namespaces']['additional'] as List).hasSameElementsAs(config.application.namespaces.getActiveNamespaces())

        assertThat(yaml['grafana']['rbac']['create']).isEqualTo(false)
        assertThat(yaml['grafana']['sidecar']['dashboards']['searchNamespace']).isEqualTo(config.application.namespaces.getActiveNamespaces().join(','))
    }

    @Test
    void 'network policies are created for prometheus'() {
        config.application.netpols = true
        //config.application.namespaces.dedicatedNamespaces = ["testnamespace1", "testnamespace2"]
        def prometheusStack = createStack(scmManagerMock)
        prometheusStack.install()

        for (String namespace : config.application.namespaces.getActiveNamespaces()) {
            def netPolsYaml = new File("$clusterResourcesRepoDir/apps/prometheusstack/misc/netpols/${namespace}.yaml")
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

        scmManagerMock.inClusterBase = new URI("http://scmm.foo-scm-manager.svc.cluster.local/scm")
        createStack(scmManagerMock).install()

        def helmConfig = ArgumentCaptor.forClass(Config.HelmConfig)
        verify(airGappedUtils).mirrorHelmRepoToGit(helmConfig.capture())
        assertThat(helmConfig.value.chart).isEqualTo('kube-prometheus-stack')
        assertThat(helmConfig.value.repoURL).isEqualTo('https://prom')
        assertThat(helmConfig.value.version).isEqualTo('19.2.2')
        verify(deploymentStrategy).deployFeature(
                'http://scmm.foo-scm-manager.svc.cluster.local/scm/repo/a/b',
                'prometheusstack', '.', '1.2.3', 'foo-monitoring',
                'kube-prometheus-stack', temporaryYamlFilePrometheus, RepoType.GIT)
    }

    @Test
    void 'Merges additional helm values merged with default values'() {
        config.features.monitoring.helm.values = [
                key       : [
                        some: 'thing',
                        one : 1
                ],
                prometheus: [
                        prometheusSpec: [
                                scrapeConfigSelectorNilUsesHelmValues: null
                        ]
                ]
        ]

        createStack(scmManagerMock).install()
        def actual = parseActualYaml()

        assertThat(actual['key']['some']).isEqualTo('thing')
        assertThat(actual['key']['one']).isEqualTo(1)
        assertThat(actual['prometheus']['prometheusSpec']['scrapeConfigSelectorNilUsesHelmValues']).isEqualTo(null)
    }

    @Test
    void 'ServiceMonitor selectors'() {
        config.application.namePrefix = "test1-"
        config.features.argocd.active = true
        config.features.secrets.active = true
        config.features.ingressNginx.active = false
        LinkedHashSet<String> namespaceList = [
                "test1-argocd",
                "test1-monitoring",
                "test1-example-apps-staging",
                "test1-example-apps-production",
                "test1-secrets"
        ]
        config.application.namespaces.dedicatedNamespaces = namespaceList
        createStack(scmManagerMock).install()
        def actual = parseActualYaml()

        assertThat(actual['prometheus']['prometheusSpec']['serviceMonitorNamespaceSelector']).isEqualTo(new YamlSlurper().parseText('''
matchExpressions:
  - key: kubernetes.io/metadata.name
    operator: In
    values:
      - test1-argocd
      - test1-monitoring
      - test1-example-apps-staging
      - test1-example-apps-production
      - test1-secrets
'''
        ))
    }

    private PrometheusStack createStack(ScmManagerMock scmManagerMock) {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        when(gitHandler.getResourcesScm()).thenReturn(scmManagerMock)
        def configuration = config
        TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, new FileSystemUtils()) {
            @Override
            GitRepo getRepo(String repoTarget,GitProvider scm) {
                def repo = super.getRepo(repoTarget, scmManagerMock)
                clusterResourcesRepoDir = new File(repo.getAbsoluteLocalRepoTmpDir())

                // Create dummy dashboards so cleanupUnusedDashboards can delete them
                def dashboardDir = new File(clusterResourcesRepoDir, "apps/prometheusstack/misc/dashboard")
                dashboardDir.mkdirs()

                new File(dashboardDir, "ingress-nginx-dashboard.yaml").text = "dummy"
                new File(dashboardDir, "ingress-nginx-dashboard-requests-handling.yaml").text = "dummy"
                new File(dashboardDir, "jenkins-dashboard.yaml").text = "dummy"
                new File(dashboardDir, "scmm-dashboard.yaml").text = "dummy"

                return repo
            }

        }

        new PrometheusStack(configuration, new FileSystemUtils() {
            @Override
            Path writeTempFile(Map mapValues) {
                def ret = super.writeTempFile(mapValues)
                temporaryYamlFilePrometheus = Path.of(ret.toString().replace(".ftl", ""))
                return ret
            }
        }, deploymentStrategy, k8sClient, airGappedUtils, repoProvider, gitHandler)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFilePrometheus) as Map
    }
}