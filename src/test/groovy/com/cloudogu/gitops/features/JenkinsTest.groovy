package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JobManager
import com.cloudogu.gitops.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.jenkins.UserManager
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

class JenkinsTest {
    Config config = new Config()

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    GlobalPropertyManager globalPropertyManager = mock(GlobalPropertyManager)
    JobManager jobManger = mock(JobManager)
    UserManager userManager = mock(UserManager)
    PrometheusConfigurator prometheusConfigurator = mock(PrometheusConfigurator)

    @Test
    void 'Maps config properly'() {
        config.application.remote = true
        config.application.trace = true
        config.application.urlSeparatorHyphen = true
        config.features.argocd.active = true
        config.scmm.url = 'http://scmm'
        config.scmm.urlForJenkins ='http://scmm-scm-manager/scm'
        config.scmm.username = 'scmm-usr'
        config.scmm.password = 'scmm-pw'
        config.application.baseUrl = 'http://localhost'
        config.application.namePrefix = 'my-prefix-'
        config.application.namePrefixForEnvVars = 'MY_PREFIX_'
        config.registry.url = 'reg-url'
        config.registry.path = 'reg-path'
        config.registry.username = 'reg-usr'
        config.registry.password = 'reg-pw'
        config.registry.proxyUrl = 'reg-proxy-url'
        config.registry.proxyUsername = 'reg-proxy-usr'
        config.registry.proxyPassword = 'reg-proxy-pw'
        config.jenkins.internal = true
        config.jenkins.helm.version = '4.8.1'
        config.jenkins.username = 'jenusr'
        config.jenkins.password = 'jenpw'
        config.jenkins.url = 'http://jenkins'
        config.jenkins.metricsUsername = 'metrics-usr'
        config.jenkins.metricsPassword = 'metrics-pw'

        
        createJenkins().install()

        def env = getEnvAsMap()
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "${System.getProperty('user.dir')}/scripts/jenkins/init-jenkins.sh" as String)

        assertThat(env['TRACE']).isEqualTo('true')
        assertThat(env['INTERNAL_JENKINS']).isEqualTo('true')
        assertThat(env['JENKINS_HELM_CHART_VERSION']).isEqualTo('4.8.1')
        assertThat(env['JENKINS_URL']).isEqualTo('http://jenkins')
        assertThat(env['JENKINS_USERNAME']).isEqualTo('jenusr')
        assertThat(env['JENKINS_PASSWORD']).isEqualTo('jenpw')
        assertThat(env['JENKINS_USERNAME']).isEqualTo('jenusr')
        assertThat(env['REMOTE_CLUSTER']).isEqualTo('true')
        assertThat(env['BASE_URL']).isEqualTo('http://localhost')
        assertThat(env['NAME_PREFIX']).isEqualTo('my-prefix-')
        assertThat(env['INSECURE']).isEqualTo('false')
        assertThat(env['URL_SEPARATOR_HYPHEN']).isEqualTo('true')

        assertThat(env['SCMM_URL']).isEqualTo('http://scmm-scm-manager/scm')
        assertThat(env['SCMM_PASSWORD']).isEqualTo('scmm-pw')
        assertThat(env['INSTALL_ARGOCD']).isEqualTo('true')

        verify(globalPropertyManager).setGlobalProperty('SCMM_URL', 'http://scmm')
        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_K8S_VERSION', Config.K8S_VERSION)

        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_REGISTRY_URL', 'reg-url')
        verify(globalPropertyManager).setGlobalProperty('MY_PREFIX_REGISTRY_PATH', 'reg-path')
        verify(globalPropertyManager, never()).setGlobalProperty(eq('MY_PREFIX_REGISTRY_PROXY_URL'), anyString())
        verify(globalPropertyManager, never()).setGlobalProperty(eq('MAVEN_CENTRAL_MIRROR'), anyString())

        verify(userManager).createUser('metrics-usr', 'metrics-pw')
        verify(userManager).grantPermission('metrics-usr', UserManager.Permissions.METRICS_VIEW)

        verify(prometheusConfigurator).enableAuthentication()

        verify(jobManger).createCredential('my-prefix-example-apps', 'scmm-user',
                'my-prefix-gitops', 'scmm-pw', 'credentials for accessing scm-manager')

        verify(jobManger).startJob('my-prefix-example-apps')
        verify(jobManger).createJob('my-prefix-example-apps', 'http://scmm-scm-manager/scm',
                "my-prefix-argocd", 'scmm-user')

        verify(jobManger).createCredential('my-prefix-example-apps', 'registry-user',
                'reg-usr', 'reg-pw', 'credentials for accessing the docker-registry for writing images built on jenkins')
        verify(jobManger, never()).createCredential(eq('my-prefix-example-apps'), eq('registry-proxy-user'),
                anyString(), anyString(), anyString())
        verify(jobManger, never()).createCredential(eq('my-prefix-example-apps'), eq('registry-proxy-user'),
                anyString(), anyString(), anyString())
    }

    @Test
    void 'Handles two registries'() {
        config.registry.twoRegistries = true
        config.features.argocd.active = true
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

        verify(jobManger).createCredential('my-prefix-example-apps', 'registry-user',
                'reg-usr', 'reg-pw',
                'credentials for accessing the docker-registry for writing images built on jenkins')
        verify(jobManger).createCredential('my-prefix-example-apps', 'registry-proxy-user',
                'reg-proxy-usr', 'reg-proxy-pw',
                'credentials for accessing the docker-registry that contains 3rd party or base images')
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
        new Jenkins(config, commandExecutor, new FileSystemUtils(), globalPropertyManager, jobManger, userManager, prometheusConfigurator)
    }
}
