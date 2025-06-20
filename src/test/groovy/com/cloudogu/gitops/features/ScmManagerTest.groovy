package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.*
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when 

class ScmManagerTest {

    Config config = new Config(
            application: new Config.ApplicationSchema(
                    username: 'abc',
                    password: '123',
                    namePrefix: 'foo-',
                    trace: true,
                    insecure: false,
                    gitName: 'Cloudogu',
                    gitEmail: 'hello@cloudogu.com',
                    runningInsideK8s : true
            ),
            scmm: new Config.ScmmSchema(
                    url: 'http://scmm',
                    internal: true,
                    ingress: 'scmm.localhost',
                    username: 'scmm-usr',
                    password: 'scmm-pw',
                    gitOpsUsername: 'foo-gitops',
                    urlForJenkins: 'http://scmm4jenkins',
                    helm: new Config.HelmConfigWithValues(
                            chart: 'scm-manager-chart',
                            version: '2.47.0',
                            repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                            values: [:]
                    )
            ),
            jenkins: new Config.JenkinsSchema(
                    internal: true,
                    url: 'http://jenkins',
                    urlForScmm: 'http://jenkins4scm'
            ),
            repositories: new Config.RepositoriesSchema(
                    springBootHelmChart: new Config.RepositorySchemaWithRef(
                            url: 'springBootHelmChartUrl',
                            ref: '1.2.3'
                    ),
                    gitopsBuildLib: new Config.RepositorySchema(
                            url: 'gitopsBuildLibUrl'
                    ),
                    cesBuildLib: new Config.RepositorySchema(
                            url: 'cesBuildLibUrl'
                    )
            )
    )
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()

    Path temporaryYamlFile
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(helmCommands)
    NetworkingUtils networkingUtils = mock(NetworkingUtils.class)
    K8sClient k8sClient = mock(K8sClient)

    @Test
    void 'Installs SCMM and calls script with proper params'() {
        config.scmm.username = 'scmm-usr'
        config.features.ingressNginx.active = true
        config.features.argocd.active = true
        config.scmm.skipPlugins = true
        config.scmm.skipRestart = true
        createScmManager().install()

        assertThat(parseActualYaml()['extraEnv'] as String).contains('SCM_WEBAPP_INITIALUSER\n  value: "scmm-usr"')
        assertThat(parseActualYaml()['extraEnv'] as String).contains('SCM_WEBAPP_INITIALPASSWORD\n  value: "scmm-pw"')
        assertThat(parseActualYaml()['service']).isEqualTo([type: 'NodePort'])
        assertThat(parseActualYaml()['ingress']).isEqualTo([enabled: true, path: '/', hosts: ['scmm.localhost']])
        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo(
                'helm repo add scm-manager https://packages.scm-manager.org/repository/helm-v2-releases/')
        assertThat(helmCommands.actualCommands[1].trim()).startsWith(
                'helm upgrade -i scmm scm-manager/scm-manager-chart --create-namespace')
        assertThat(helmCommands.actualCommands[1].trim()).contains('--version 2.47.0')
        assertThat(helmCommands.actualCommands[1].trim()).contains(" --values ${temporaryYamlFile}")
        assertThat(helmCommands.actualCommands[1].trim()).contains('--namespace foo-scm-manager')

        def env = getEnvAsMap()
        assertThat(commandExecutor.actualCommands[0] as String).isEqualTo(
                "${System.getProperty('user.dir')}/scripts/scm-manager/init-scmm.sh" as String)

        assertThat(env['GIT_COMMITTER_NAME']).isEqualTo('Cloudogu')
        assertThat(env['GIT_COMMITTER_EMAIL']).isEqualTo('hello@cloudogu.com')
        assertThat(env['GIT_AUTHOR_NAME']).isEqualTo('Cloudogu')
        assertThat(env['GIT_AUTHOR_EMAIL']).isEqualTo('hello@cloudogu.com')
        assertThat(env['GITOPS_USERNAME']).isEqualTo('foo-gitops')
        assertThat(env['TRACE']).isEqualTo('true')
        assertThat(env['SCMM_URL']).isEqualTo('http://scmm.foo-scm-manager.svc.cluster.local:80/scm')
        assertThat(env['SCMM_USERNAME']).isEqualTo('scmm-usr')
        assertThat(env['SCMM_PASSWORD']).isEqualTo('scmm-pw')
        assertThat(env['JENKINS_URL']).isEqualTo('http://jenkins')
        assertThat(env['JENKINS_URL_FOR_SCMM']).isEqualTo('http://jenkins4scm')
        assertThat(env['SCMM_URL_FOR_JENKINS']).isEqualTo('http://scmm4jenkins')
        assertThat(env['REMOTE_CLUSTER']).isEqualTo('false')
        assertThat(env['INSTALL_ARGOCD']).isEqualTo('true')
        assertThat(env['SPRING_BOOT_HELM_CHART_COMMIT']).isEqualTo('1.2.3')
        assertThat(env['SPRING_BOOT_HELM_CHART_REPO']).isEqualTo('springBootHelmChartUrl')
        assertThat(env['GITOPS_BUILD_LIB_REPO']).isEqualTo('gitopsBuildLibUrl')
        assertThat(env['CES_BUILD_LIB_REPO']).isEqualTo('cesBuildLibUrl')
        assertThat(env['NAME_PREFIX']).isEqualTo('foo-')
        assertThat(env['INSECURE']).isEqualTo('false')
        assertThat(env['CONTENT_EXAMPLES']).isEqualTo('false')
        assertThat(env['SKIP_PLUGINS']).isEqualTo('true')
        assertThat(env['SKIP_RESTART']).isEqualTo('true')
    }

    @Test
    void 'Sets service and host only if enabled'() {
        config.application.remote = true
        config.scmm.ingress = ''
        createScmManager().install()

        Map actualYaml = parseActualYaml() as Map

        assertThat(actualYaml).doesNotContainKey('service')
        assertThat(actualYaml).doesNotContainKey('ingress')
    }

    @Test
    void 'Installs only if internal'() {
        config.scmm.internal = false
        createScmManager().install()

        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'initialDelaySeconds is set properly'() {
        config.scmm.helm.values = [
                livenessProbe: [
                        initialDelaySeconds: 140
                ]
        ]

        createScmManager().install()
        assertThat(parseActualYaml()['livenessProbe'] as String).contains('initialDelaySeconds:140')
    }

    @Test
    void "URL: Use k8s service name if running as k8s pod"() {
        config.scmm.internal = true
        config.application.runningInsideK8s = true
        
        createScmManager().install()
        assertThat(config.scmm.url).isEqualTo("http://scmm.foo-scm-manager.svc.cluster.local:80/scm")
    }

    @Test
    void "URL: Use local ip and nodePort when outside of k8s"() {
        config.scmm.internal = true
        config.application.runningInsideK8s = false

        when(networkingUtils.findClusterBindAddress()).thenReturn('192.168.16.2')
        when(k8sClient.waitForNodePort(anyString(), anyString())).thenReturn('42')
        
        createScmManager().install()
        assertThat(config.scmm.url).endsWith('192.168.16.2:42/scm')
    }
    
    protected Map<String, String> getEnvAsMap() {
        commandExecutor.environment.collectEntries { it.split('=') }
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }

    private ScmManager createScmManager() {
        when(networkingUtils.createUrl(anyString(), anyString(), anyString())).thenCallRealMethod()
        when(networkingUtils.createUrl(anyString(), anyString())).thenCallRealMethod()
        new ScmManager(config, commandExecutor, new FileSystemUtils() {
            @Override
            Path writeTempFile(Map mapValues) {
                def ret = super.writeTempFile(mapValues)
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")) // Path after template invocation
                return ret
            }
        }, new HelmStrategy(config, helmClient), k8sClient, networkingUtils)
    }
}