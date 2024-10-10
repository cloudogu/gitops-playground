package com.cloudogu.gitops

import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.schema.Schema
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils
import com.cloudogu.gitops.utils.TestLogger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable
import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when 

class ApplicationConfiguratorTest {

    static final String EXPECTED_REGISTRY_URL = 'http://my-reg'
    static final int EXPECTED_REGISTRY_INTERNAL_PORT = 33333
    static final Schema.VaultMode EXPECTED_VAULT_MODE = Schema.VaultMode.dev
    public static final String EXPECTED_JENKINS_URL = 'http://my-jenkins'
    public static final String EXPECTED_SCMM_URL = 'http://my-scmm'

    private ApplicationConfigurator applicationConfigurator
    private NetworkingUtils networkingUtils
    private FileSystemUtils fileSystemUtils
    private TestLogger testLogger
    Map testConfig = [
            application: [
                    localHelmChartFolder : 'someValue',
            ],
            registry   : [
                    url         : EXPECTED_REGISTRY_URL,
                    proxyUrl: "proxy-$EXPECTED_REGISTRY_URL",
                    proxyUsername: "proxy-user",
                    proxyPassword: "proxy-pw",
                    internalPort: EXPECTED_REGISTRY_INTERNAL_PORT,
            ],
            jenkins    : [
                    url     : EXPECTED_JENKINS_URL
                    ],
            scmm       : [
                    url     : EXPECTED_SCMM_URL,
                    ],
            features    : [
                    secrets : [
                            vault : [
                                    mode : EXPECTED_VAULT_MODE
                            ]
                    ],
                    argocd: [:],
                    mail: [:],
                    monitoring: [:],
                    ingressNginx: [:],
                    exampleApps: [
                            petclinic: [:],
                            nginx    : [:],
                    ]
            ]
    ]
    
    // We have to set this value using env vars, which makes tests complicated, so ignore it
    Map almostEmptyConfig = [
            application: [
                    localHelmChartFolder : 'someValue',
            ],
    ]
    
    @BeforeEach
    void setup() {
        networkingUtils = mock(NetworkingUtils.class)
        fileSystemUtils = mock(FileSystemUtils.class)
        applicationConfigurator = new ApplicationConfigurator(networkingUtils, fileSystemUtils)
        testLogger = new TestLogger(applicationConfigurator.getClass())
        when(fileSystemUtils.getRootDir()).thenReturn("/test")
        when(fileSystemUtils.getLineFromFile("/test/scm-manager/values.ftl.yaml", "nodePort:")).thenReturn("nodePort: 9091")
        when(fileSystemUtils.getLineFromFile("/test/jenkins/values.yaml", "nodePort:")).thenReturn("nodePort: 9090")

        when(networkingUtils.createUrl(anyString(), anyString(), anyString())).thenCallRealMethod()
        when(networkingUtils.createUrl(anyString(), anyString())).thenCallRealMethod()
        when(networkingUtils.findClusterBindAddress()).thenReturn("localhost")
    }

    @Test
    void "correct config with no programm arguments"() {
        when(networkingUtils.findClusterBindAddress()).thenReturn("localhost")
        when(networkingUtils.createUrl("localhost", "9091", "/scm")).thenReturn("http://localhost:9091/scm")
        when(networkingUtils.getProtocol("http://localhost:9091/scm")).thenReturn("http")
        when(networkingUtils.getHost("http://localhost:9091/scm")).thenReturn("localhost:9091/scm")

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.jenkins.url).isEqualTo(EXPECTED_JENKINS_URL)
        assertThat(actualConfig.jenkins.internal).isEqualTo(false)
        
        assertThat(actualConfig.features.secrets.vault.mode).isEqualTo(EXPECTED_VAULT_MODE)
        
        // Dynamic value (depends on vault mode)
        assertThat(actualConfig.features.secrets.active).isEqualTo(true)
    }
    
    @Test
    void "uses k8s services for jenkins and scmm if running as k8s job"() {
        testConfig.jenkins['url'] = ''
        testConfig.scmm['url'] = ''

        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1").execute {
            Schema actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

            assertThat(actualConfig.scmm.url).isEqualTo("http://scmm-scm-manager.default.svc.cluster.local:80/scm")
            assertThat(actualConfig.jenkins.url).isEqualTo("http://jenkins.default.svc.cluster.local:80")
        }
    }

    @Test
    void 'Fails if jenkins is internal and scmm is external'() {
        testConfig.jenkins['url'] = ''
        testConfig.scmm['url'] = 'external'

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('When setting jenkins URL, scmm URL must also be set and the other way round')
    }
    
    @Test
    void 'Fails if jenkins is external and scmm is internal'() {
        testConfig.jenkins['url'] = 'external'
        testConfig.scmm['url'] = ''
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('When setting jenkins URL, scmm URL must also be set and the other way round')
    }
    
    @Test
    void 'Fails if monitoring local is not set'() {
        testConfig['application']['mirrorRepos'] = true
        testConfig['application']['localHelmChartFolder'] = ''
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('Missing config for localHelmChartFolder.\n' +
                'Either run inside the official container image or setting env var LOCAL_HELM_CHART_FOLDER=\'charts\' ' +
                'after running \'scripts/downloadHelmCharts.sh\' from the repo')
    }

    @Test
    void 'Fails if createImagePullSecrets is used without secrets'() {
        testConfig['registry']['createImagePullSecrets'] = true
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('createImagePullSecrets needs to be used with either registry username and password or the readOnly variants')
    }
    
    @Test
    void 'Ignores empty localHemlChartFolder, if mirrorRepos is not set'() {
        testConfig['application']['mirrorRepos'] = false
        testConfig['application']['localHelmChartFolder'] = ''
        
        applicationConfigurator.initAndValidateConfig(testConfig)
        // no exceptions means success
    }
    
    @Test
    void "uses default localhost url for jenkins and scmm if nothing specified"() {
        testConfig.jenkins['url'] = ''
        testConfig.scmm['url'] = ''

        Schema actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.scmm.url).isEqualTo("http://localhost:9091/scm")
        assertThat(actualConfig.jenkins.url).isEqualTo("http://localhost:9090")
    }

    @Test
    void "Certain properties are read from env"() {
        withEnvironmentVariable('SPRING_BOOT_HELM_CHART_REPO', 'value1').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initAndValidateConfig(testConfig)
            assertThat(actualConfig.repositories.springBootHelmChart.url).isEqualTo('value1')
        }
        withEnvironmentVariable('SPRING_PETCLINIC_REPO', 'value2').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initAndValidateConfig(testConfig)
            assertThat(actualConfig.repositories.springPetclinic.url).isEqualTo('value2')
        }
        withEnvironmentVariable('GITOPS_BUILD_LIB_REPO', 'value3').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initAndValidateConfig(testConfig)
            assertThat(actualConfig.repositories.gitopsBuildLib.url).isEqualTo('value3')
        }
        withEnvironmentVariable('CES_BUILD_LIB_REPO', 'value4').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initAndValidateConfig(testConfig)
            assertThat(actualConfig.repositories.cesBuildLib.url).isEqualTo('value4')
        }
    }

    @Test
    void "base url: evaluates for all tools"() {
        testConfig.application['baseUrl'] = 'http://localhost'
        
        testConfig.features['argocd']['active'] = true
        testConfig.features['mail']['mailhog'] = true
        testConfig.features['monitoring']['active'] = true
        testConfig.features['secrets']['active'] = true

        Schema actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd.localhost")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("http://mailhog.localhost")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("http://grafana.localhost")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("http://vault.localhost")
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic.localhost")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx.localhost")
        assertThat(actualConfig.scmm['ingress']).isEqualTo("scmm.localhost")
    }

    @Test
    void "base url with url-hyphens: evaluates for all tools"() {
        testConfig.application['baseUrl'] = 'http://localhost'
        testConfig.application['urlSeparatorHyphen'] = true

        testConfig.features['argocd']['active'] = true
        testConfig.features['mail']['mailhog'] = true
        testConfig.features['monitoring']['active'] = true
        testConfig.features['secrets']['active'] = true

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd-localhost")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("http://mailhog-localhost")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("http://grafana-localhost")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("http://vault-localhost")
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic-localhost")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx-localhost")
        assertThat(actualConfig.scmm['ingress']).isEqualTo("scmm-localhost")
    }

    @Test
    void "base url: also works when port is included "() {
        testConfig.application['baseUrl'] = 'http://localhost:8080'
        testConfig.features['argocd']['active'] = true

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd.localhost:8080")
    }

    @Test
    void "base url: also works when port is included and use url-hyphens is set"() {
        testConfig.application['baseUrl'] = 'http://localhost:6502'
        testConfig.features['argocd']['active'] = true
        testConfig.application['urlSeparatorHyphen'] = true

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd-localhost:6502")
    }


    @Test
    void "base url: does not evaluate for inactive tools"() {
        testConfig.features['argocd']['active'] = false
        testConfig.features['mail']['active'] = false
        testConfig.features['monitoring']['active'] = false
        testConfig.features['secrets']['active'] = false

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("")
    }

    @Test
    void "base url: individual url params take precedence"() {
        testConfig.application['baseUrl'] = 'http://localhost'

        testConfig.features['argocd']['active'] = true
        testConfig.features['mail']['active'] = true
        testConfig.features['monitoring']['active'] = true
        testConfig.features['secrets']['active'] = true

        testConfig.features['argocd']['url'] = 'argocd'
        testConfig.features['mail']['mailhogUrl'] = 'mailhog'
        testConfig.features['monitoring']['grafanaUrl'] = 'grafana'
        testConfig.features['secrets']['vault']['url'] = 'vault'
        testConfig.features['exampleApps']['petclinic']['baseDomain'] = 'petclinic'
        testConfig.features['exampleApps']['nginx']['baseDomain'] = 'nginx'

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("argocd")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("mailhog")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("grafana")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("vault")
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx")
    }

    @Test
    void "Sets namePrefix"() {
        testConfig.application['namePrefix'] = 'my-prefix'

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)
        assertThat(actualConfig.application['namePrefix'].toString()).isEqualTo('my-prefix-')
        assertThat(actualConfig.application['namePrefixForEnvVars'].toString()).isEqualTo('MY_PREFIX_')
    }

    @Test
    void "Sets namePrefix when ending in hyphen"() {
        testConfig.application['namePrefix'] = 'my-prefix-'

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)
        assertThat(actualConfig.application['namePrefix'].toString()).isEqualTo('my-prefix-')
        assertThat(actualConfig.application['namePrefixForEnvVars'].toString()).isEqualTo('MY_PREFIX_')
    }
    
    @Test
    void "Registry: Sets to internal when no URL set"() {
        testConfig.registry['url'] = null
        testConfig.registry['proxyUrl'] = null
        
        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)
        
        assertThat(actualConfig.registry.url.toString()).isEqualTo('localhost:33333')
        assertThat(actualConfig.registry.internalPort).isEqualTo(EXPECTED_REGISTRY_INTERNAL_PORT)
        assertThat(actualConfig.registry.internal).isEqualTo(true)
    }
    
    @Test
    void "Registry: Sets to external when only registry URL set"() {
        testConfig.registry['proxyUrl'] = null

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)
        
        assertThat(actualConfig.registry.internal).isEqualTo(false)
    }
    
    @Test
    void "Registry: Sets to internal when only proxy Url is set"() {
        testConfig.registry['url'] = null

        def actualConfig = applicationConfigurator.initAndValidateConfig(testConfig)
        
        assertThat(actualConfig.registry.internal).isEqualTo(true)
    }
    
    @Test
    void "Registry: Fails when proxy but no username and password set"() {
        def expectedException = 'Proxy URL needs to be used with proxy-username and proxy-password'
        
        testConfig.registry['proxyUsername'] = null
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo(expectedException)
        
        testConfig.registry['proxyUsername'] = 'something'
        testConfig.registry['proxyPassword'] = null
        exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo(expectedException)
        
        testConfig.registry['proxyUsername'] = null
        exception = shouldFail(RuntimeException) {
            applicationConfigurator.initAndValidateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo(expectedException)
    }
}
