package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JobManager
import com.cloudogu.gitops.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.jenkins.UserManager
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.NetworkingUtils
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.ScmManagerMock
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

class JenkinsTest {
    Config config = new Config(
            scm: [
                    scmManager: [
                            urlForJenkins: "testUrlJenkins"
                    ]],
            jenkins: new Config.JenkinsSchema(active: true))

    String expectedNodeName = 'something'

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    GlobalPropertyManager globalPropertyManager = mock(GlobalPropertyManager)
    JobManager jobManger = mock(JobManager)
    UserManager userManager = mock(UserManager)
    PrometheusConfigurator prometheusConfigurator = mock(PrometheusConfigurator)
    HelmStrategy deploymentStrategy = mock(HelmStrategy)
    Path temporaryYamlFile
    NetworkingUtils networkingUtils = mock(NetworkingUtils.class)
    K8sClient k8sClient = mock(K8sClient)

    @Mock
    ScmManagerMock scmManagerMock = new ScmManagerMock()
    GitHandler gitHandler = new GitHandlerForTests(config, scmManagerMock)

    @BeforeEach
    void setup() {
        // waitForInternalNodeIp -> waitForNode()
        when(k8sClient.waitForNode()).thenReturn("node/${expectedNodeName}".toString())
        when(k8sClient.run(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn('')
    }

    @Test
    void 'Installs Jenkins'() {
        def jenkins = createJenkins()

        config.jenkins.url = 'http://jenkins'
        config.jenkins.helm.chart = 'jen-chart'
        config.jenkins.helm.repoURL = 'https://jen-repo'
        config.jenkins.helm.version = '4.8.1'
        config.jenkins.username = 'jenusr'
        config.jenkins.password = 'jenpw'
        config.jenkins.internalBashImage = 'bash:42'
        config.jenkins.internalDockerClientVersion = '23'

        when(k8sClient.run(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn('''
root:x:0:
daemon:x:1:
docker:x:42:me
me:x:1000:''')

        jenkins.install()

        verify(deploymentStrategy).deployFeature('https://jen-repo', 'jenkins',
                'jen-chart', '4.8.1', 'jenkins',
                'jenkins', temporaryYamlFile)
        verify(k8sClient).label('node', expectedNodeName, new Tuple2('node', 'jenkins'))
        verify(k8sClient).labelRemove('node', '--all', '', 'node')
        verify(k8sClient).createSecret('generic', 'jenkins-credentials', 'jenkins',
                new Tuple2('jenkins-admin-user', 'jenusr'),
                new Tuple2('jenkins-admin-password', 'jenpw'))

        assertThat(parseActualYaml()['dockerClientVersion'].toString()).isEqualTo('23')

        assertThat(parseActualYaml()['controller']['image']['tag']).isEqualTo('4.8.1')

        assertThat(parseActualYaml()['controller']['jenkinsUrl']).isEqualTo('http://jenkins')
        assertThat(parseActualYaml()['controller']['serviceType']).isEqualTo('NodePort')

        assertThat(parseActualYaml()['controller']['ingress']).isNull()

        List customInitContainers = parseActualYaml()['controller']['customInitContainers'] as List
        assertThat(customInitContainers[0]['image']).isEqualTo('bash:42')

        assertThat(parseActualYaml()['agent']['runAsUser']).isEqualTo(1000)
        assertThat(parseActualYaml()['agent']['runAsGroup']).isEqualTo(42)

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> overridesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(k8sClient).run(nameCaptor.capture(), anyString(), eq(jenkins.namespace), overridesCaptor.capture(), any())
        assertThat(nameCaptor.value).startsWith('tmp-docker-gid-grepper-')
        List containers = overridesCaptor.value['spec']['containers'] as List
        assertThat(containers[0]['image'].toString()).isEqualTo('bash:42')
    }

    @Test
    void 'Installs Jenkins without dockerGid'() {
        when(k8sClient.run(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn('''
root:x:0:
daemon:x:1:
me:x:1000:''')
        createJenkins().install()

        assertThat(parseActualYaml()['agent']['runAsUser']).isEqualTo('0')
        assertThat(parseActualYaml()['agent']['runAsGroup']).isEqualTo('133')
    }

    @Test
    void 'Installs only if internal'() {
        config.jenkins.internal = false

        createJenkins().install()
        verify(deploymentStrategy, never()).deployFeature(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(Path))

        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'Additional helm values are merged with default values'() {
        config.jenkins.helm.values = [
                controller: [
                        nodePort: 42
                ]
        ]

        createJenkins().install()

        assertThat(parseActualYaml()['controller']['nodePort']).isEqualTo(42)
    }

    @Test
    void 'Enables ingress when baseUrl is set'() {
        config.jenkins.ingress = 'jenkins.localhost'
        config.application.baseUrl = 'someBaseUrl'

        createJenkins().install()

        assertThat(parseActualYaml()['controller']['ingress']['enabled']).isEqualTo(true)
        assertThat(parseActualYaml()['controller']['ingress']['hostName']).isEqualTo('jenkins.localhost')
    }

    @Test
    void "service type LoadBalancer when run remotely"() {
        config.application.remote = true
        createJenkins().install()

        assertThat(parseActualYaml()['controller']['serviceType']).isEqualTo('LoadBalancer')
    }

    @Test
    void 'Maps config properly'() {
        config.application.remote = true
        config.application.trace = true
        config.features.argocd.active = true
        config.content.examples = true
        config.scm.scmManager.url = 'http://scmm.scm-manager.svc.cluster.local/scm'
        config.scm.scmManager.username = 'scmm-usr'
        config.scm.scmManager.password = 'scmm-pw'
        config.application.namePrefix = 'my-prefix-'
        config.application.namePrefixForEnvVars = 'MY_PREFIX_'
        config.registry.url = 'reg-url'
        config.registry.path = 'reg-path'
        config.registry.username = 'reg-usr'
        config.registry.password = 'reg-pw'
        config.registry.proxyUrl = 'reg-proxy-url'
        config.registry.proxyUsername = 'reg-proxy-usr'
        config.registry.proxyPassword = 'reg-proxy-pw'
        config.jenkins.internal = false
        config.jenkins.helm.version = '4.8.1'
        config.jenkins.username = 'jenusr'
        config.jenkins.password = 'jenpw'
        config.jenkins.url = 'http://jenkins'
        config.jenkins.metricsUsername = 'metrics-usr'
        config.jenkins.metricsPassword = 'metrics-pw'
        config.jenkins.skipPlugins = true
        config.jenkins.skipRestart = true

        createJenkins().install()

        def env = getEnvAsMap()
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "${System.getProperty('user.dir')}/scripts/jenkins/init-jenkins.sh" as String)

        assertThat(env['TRACE']).isEqualTo('true')
        assertThat(env['INTERNAL_JENKINS']).isEqualTo('false')
        assertThat(env['JENKINS_HELM_CHART_VERSION']).isEqualTo('4.8.1')
        assertThat(env['JENKINS_URL']).isEqualTo('http://jenkins')
        assertThat(env['JENKINS_USERNAME']).isEqualTo('jenusr')
        assertThat(env['JENKINS_PASSWORD']).isEqualTo('jenpw')
        assertThat(env['JENKINS_USERNAME']).isEqualTo('jenusr')
        assertThat(env['REMOTE_CLUSTER']).isEqualTo('true')
        assertThat(env['NAME_PREFIX']).isEqualTo('my-prefix-')
        assertThat(env['INSECURE']).isEqualTo('false')

        assertThat(env['SCMM_URL']).isEqualTo('http://scmm.scm-manager.svc.cluster.local/scm')
        assertThat(env['SCMM_PASSWORD']).isEqualTo(scmManagerMock.credentials.password)
        assertThat(env['INSTALL_ARGOCD']).isEqualTo('true')

        assertThat(env['SKIP_PLUGINS']).isEqualTo('true')
        assertThat(env['SKIP_RESTART']).isEqualTo('true')

        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_SCM_URL', 'http://scmm.scm-manager.svc.cluster.local/scm')
        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_K8S_VERSION', Config.K8S_VERSION)

        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_REGISTRY_URL', 'reg-url')
        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_REGISTRY_PATH', 'reg-path')
        verify(globalPropertyManager, never()).setGlobalProperty(eq('MY_PREFIX_REGISTRY_PROXY_URL'), anyString())
        verify(globalPropertyManager, never()).setGlobalProperty(eq('MAVEN_CENTRAL_MIRROR'), anyString())

        verify(userManager).createUser('metrics-usr', 'metrics-pw')
        verify(userManager).grantPermission('metrics-usr', UserManager.Permissions.METRICS_VIEW)
    }

    @Test
    void 'Does not configure prometheus when external Jenkins'() {
        config.features.monitoring.active = true
        config.jenkins.internal = false

        createJenkins().install()

        verify(prometheusConfigurator, never()).enableAuthentication()
    }

    @Test
    void 'Does not configure prometheus when monitoring off'() {
        config.features.monitoring.active = false
        config.jenkins.internal = true

        createJenkins().install()

        verify(prometheusConfigurator, never()).enableAuthentication()
    }

    @Test
    void 'Configures prometheus'() {
        config.features.monitoring.active = true
        config.jenkins.internal = true

        createJenkins().install()

        verify(prometheusConfigurator).enableAuthentication()
    }

    @Test
    void "URL: Use k8s service name if running as k8s pod"() {
        config.jenkins.internal = true
        config.application.runningInsideK8s = true

        createJenkins().install()
        assertThat(config.jenkins.url).isEqualTo("http://jenkins.jenkins.svc.cluster.local:80")
    }

    @Test
    void "URL: Use local ip and nodePort when outside of k8s"() {
        config.jenkins.internal = true
        config.application.runningInsideK8s = false

        when(networkingUtils.findClusterBindAddress()).thenReturn('192.168.16.2')
        when(k8sClient.waitForNodePort(anyString(), anyString())).thenReturn('42')

        createJenkins().install()
        assertThat(config.jenkins.url).endsWith('192.168.16.2:42')
    }

    @Test
    void 'Handles two registries'() {
        config.registry.twoRegistries = true
        config.content.examples = true
        config.application.namePrefix = 'my-prefix-'
        config.application.namePrefixForEnvVars = 'MY_PREFIX_'

        config.registry.url = 'reg-url'
        config.registry.path = 'reg-path'
        config.registry.username = 'reg-usr'
        config.registry.password = 'reg-pw'
        config.registry.proxyUrl = 'reg-proxy-url'
        config.registry.proxyUsername = 'reg-proxy-usr'
        config.registry.proxyPassword = 'reg-proxy-pw'

        createJenkins().install()

        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_REGISTRY_PROXY_URL', 'reg-proxy-url')

        verify(globalPropertyManager).setGlobalProperty(eq('MY_PREFIX_REGISTRY_URL'), anyString())
        verify(globalPropertyManager).setGlobalProperty(eq('MY_PREFIX_REGISTRY_PATH'), anyString())

    }

    @Test
    void 'Does not create create job credentials when argo cd is deactivated'() {
        config.application.namePrefixForEnvVars = 'MY_PREFIX_'
        when(userManager.isUsingCasSecurityRealm()).thenReturn(true)

        createJenkins().install()

        verify(userManager, never()).createUser(anyString(), anyString())
    }

    @Test
    void 'Global property is set for additional envs'() {

        config.jenkins.additionalEnvs = [
                ADDITIONAL_DOCKER_RUN_ARGS: '-u0:0'
        ]

        createJenkins().install()
        verify(globalPropertyManager).setGlobalProperty(eq('ADDITIONAL_DOCKER_RUN_ARGS'), eq('-u0:0'))
    }

    @Test
    void 'Does not create create user if CAS security realm is used'() {
        config.features.argocd.active = false

        createJenkins().install()
        verify(jobManger, never()).createCredential(anyString(), anyString(), anyString(), anyString(), anyString())
        verify(jobManger, never()).startJob(anyString())
    }

    @Test
    void 'Properly handles null values'() {
        config.application.baseUrl = null
        createJenkins().install()

        def env = getEnvAsMap()
        assertThat(env['BASE_URL']).isNotEqualTo('null')
    }

    @Test
    void 'Sets maven mirror '() {
        config.registry.url = 'some value'
        config.jenkins.mavenCentralMirror = 'http://test'
        config.application.namePrefixForEnvVars = 'MY_PREFIX_'

        createJenkins().install()

        verify(globalPropertyManager).setGlobalProperty(eq('MY_PREFIX_MAVEN_CENTRAL_MIRROR'), eq("http://test"))
    }

    protected Map<String, String> getEnvAsMap() {
        commandExecutor.environment.collectEntries { it.split('=') }
    }

    private Jenkins createJenkins() {
        when(networkingUtils.createUrl(anyString(), anyString(), anyString())).thenCallRealMethod()
        when(networkingUtils.createUrl(anyString(), anyString())).thenCallRealMethod()
        new Jenkins(config, commandExecutor, new FileSystemUtils() {
            @Override
            Path writeTempFile(Map mergeMap) {
                def ret = super.writeTempFile(mergeMap)
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
                // Path after template invocation
                return ret
            }
        }, globalPropertyManager, jobManger, userManager, prometheusConfigurator, deploymentStrategy, k8sClient, networkingUtils, gitHandler)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}