package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat 

class JenkinsTest {

    Map config = [
            application: [
                    username  : 'abc',
                    password  : '123',
                    remote    : true,
                    namePrefix: "foo-",
                    trace     : true,
                    baseUrl : 'http://localhost'
            ],
            jenkins    : [
                    internal       : true,
                    username       : 'jenusr',
                    password       : 'jenpw',
                    url: 'http://jenkins',
                    urlForScmm     : 'http://jenkins4scm',
                    metricsUsername: 'metrics-usr',
                    metricsPassword: 'metrics-pw',
                    helm           : [
                            version: '4.8.1'
                    ]
            ],
            registry   : [
                    url     : 'reg-url',
                    path    : 'reg-path',
                    username: 'reg-usr',
                    password: 'reg-pw'
            ],
            scmm       : [
                    url: 'http://scmm',
                    internal: true,
                    protocol: 'https',
                    host    : 'abc',
                    username: 'scmm-usr',
                    password: 'scmm-pw'
            ],
            features   : [
                    argocd: [
                            active: true,
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()

    @Test
    void 'Calls script with proper params'() {
        createJenkins().install()

        def env = getEnvAsMap()
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "${System.getProperty('user.dir')}/scripts/jenkins/init-jenkins.sh" as String)

        assertThat(env['TRACE']).isEqualTo('true')
        def absoluteBasedir = Path.of(env['ABSOLUTE_BASEDIR'])
        assertThat(absoluteBasedir.toString()).startsWith(System.getProperty('user.dir'));
        assertThat(absoluteBasedir.toString()).endsWith('/scripts')
        assertThat(env['PLAYGROUND_DIR']).isEqualTo(System.getProperty('user.dir'))
        assertThat(env['JENKINS_HELM_CHART_VERSION']).isEqualTo('4.8.1')
        assertThat(env['JENKINS_URL']).isEqualTo('http://jenkins')
        assertThat(env['JENKINS_USERNAME']).isEqualTo('jenusr')
        assertThat(env['JENKINS_PASSWORD']).isEqualTo('jenpw')
        assertThat(env['JENKINS_USERNAME']).isEqualTo('jenusr')
        assertThat(env['REMOTE_CLUSTER']).isEqualTo('true')
        assertThat(env['BASE_URL']).isEqualTo('http://localhost')

        assertThat(env['K8S_VERSION']).isEqualTo(ApplicationConfigurator.K8S_VERSION)
        assertThat(env['SCMM_URL']).isEqualTo('http://scmm')
        assertThat(env['SCMM_PASSWORD']).isEqualTo('scmm-pw')
        assertThat(env['JENKINS_METRICS_USERNAME']).isEqualTo('metrics-usr')
        assertThat(env['JENKINS_METRICS_PASSWORD']).isEqualTo('metrics-pw')
        //assertThat(env['REGISTRY_URL']).isEqualTo('reg-url')
        //assertThat(env['REGISTRY_PATH']).isEqualTo('reg-path')
        assertThat(env['REGISTRY_USERNAME']).isEqualTo('reg-usr')
        assertThat(env['REGISTRY_PASSWORD']).isEqualTo('reg-pw')
        assertThat(env['INSTALL_ARGOCD']).isEqualTo('true')
    }

    @Test
    void 'Properly handles null values'() {
        config.application['baseUrl'] = null
        createJenkins().install()
        
        def env = getEnvAsMap()
        assertThat(env['BASE_URL']).isNotEqualTo('null')
    }

    protected Map<String, String> getEnvAsMap() {
        commandExecutor.environment.collectEntries { it.split('=') }
    }
    
    private Jenkins createJenkins() {
        new Jenkins(new Configuration(config), commandExecutor, new FileSystemUtils(),)
    }
}
