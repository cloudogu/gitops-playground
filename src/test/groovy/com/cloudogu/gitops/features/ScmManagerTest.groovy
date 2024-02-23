package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat 

class ScmManagerTest {

    Map config = [
            application: [
                    username  : 'abc',
                    password  : '123',
                    remote    : true,
                    namePrefix: "foo-",
                    trace     : true,
                    baseUrl : 'http://localhost'
            ],
            scmm       : [
                    url: 'http://scmm',
                    internal: true,
                    protocol: 'https',
                    host    : 'abc',
                    username: 'scmm-usr',
                    password: 'scmm-pw',
                    helm  : [
                            version: '2.47.0'
                    ]
            ],
            features   : [
                    argocd: [
                            active: true,
                    ]
            ],
            repositories : [
                    springBootHelmChart: [
                            url: 'springBootHelmChartUrl',
                            ref: '1.2.3'
                    ],
                    gitopsBuildLib: [
                            url: 'gitopsBuildLibUrl'
                    ],
                    cesBuildLib: [
                            url: 'cesBuildLibUrl'
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()

    @Test
    void 'Calls script with proper params'() {
        createScmManager().install()

        def env = getEnvAsMap()
        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "${System.getProperty('user.dir')}/scripts/scm-manager/init-scmm.sh" as String)

        assertThat(env['TRACE']).isEqualTo('true')
        assertThat(env['SCMM_URL']).isEqualTo('http://scmm')
        assertThat(env['SCMM_USERNAME']).isEqualTo('scmm-usr')
        assertThat(env['SCMM_PASSWORD']).isEqualTo('scmm-pw')
        assertThat(env['REMOTE_CLUSTER']).isEqualTo('true')
        assertThat(env['BASE_URL']).isEqualTo('http://localhost')
        assertThat(env['INSTALL_ARGOCD']).isEqualTo('true')
        assertThat(env['SCMM_HELM_CHART_VERSION']).isEqualTo('2.47.0')
        assertThat(env['SPRING_BOOT_HELM_CHART_COMMIT']).isEqualTo('1.2.3')
        assertThat(env['SPRING_BOOT_HELM_CHART_REPO']).isEqualTo('springBootHelmChartUrl')
        assertThat(env['GITOPS_BUILD_LIB_REPO']).isEqualTo('gitopsBuildLibUrl')
        assertThat(env['CES_BUILD_LIB_REPO']).isEqualTo('cesBuildLibUrl')
        assertThat(env['NAME_PREFIX']).isEqualTo('foo-')
    }

    @Test
    void 'Properly handles null values'() {
        config.application['baseUrl'] = null
        createScmManager().install()

        def env = getEnvAsMap()
        assertThat(env['BASE_URL']).isNotEqualTo('null')
    }
    
    protected Map<String, String> getEnvAsMap() {
        commandExecutor.environment.collectEntries { it.split('=') }
    }

    private ScmManager createScmManager() {
        new ScmManager(new Configuration(config), commandExecutor, new FileSystemUtils())
    }
}
