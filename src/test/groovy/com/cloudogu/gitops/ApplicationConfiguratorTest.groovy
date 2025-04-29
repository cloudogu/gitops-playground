package com.cloudogu.gitops

import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Config
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
    static final Config.VaultMode EXPECTED_VAULT_MODE = Config.VaultMode.dev
    public static final String EXPECTED_JENKINS_URL = 'http://my-jenkins'
    public static final String EXPECTED_SCMM_URL = 'http://my-scmm'

    private ApplicationConfigurator applicationConfigurator
    private NetworkingUtils networkingUtils
    private FileSystemUtils fileSystemUtils
    private TestLogger testLogger
    Config testConfig = Config.fromMap([
            application: [
                    localHelmChartFolder : 'someValue',
                    namePrefix : ''
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
            ]
    ])
    
    // We have to set this value using env vars, which makes tests complicated, so ignore it
    Config almostEmptyConfig = Config.fromMap([
            application: [
                    localHelmChartFolder : 'someValue',
            ],
    ])
    
    @BeforeEach
    void setup() {
        networkingUtils = mock(NetworkingUtils.class)
        fileSystemUtils = new FileSystemUtils()
        applicationConfigurator = new ApplicationConfigurator(networkingUtils, fileSystemUtils)
        testLogger = new TestLogger(applicationConfigurator.getClass())

        when(networkingUtils.createUrl(anyString(), anyString(), anyString())).thenCallRealMethod()
        when(networkingUtils.createUrl(anyString(), anyString())).thenCallRealMethod()
        when(networkingUtils.findClusterBindAddress()).thenReturn("localhost")
    }

    @Test
    void "correct config with no programm arguments"() {
        when(networkingUtils.findClusterBindAddress()).thenReturn("localhost")
        when(networkingUtils.createUrl("localhost", "9091", "/scm")).thenReturn("http://localhost:9091/scm")

        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.jenkins.url).isEqualTo(EXPECTED_JENKINS_URL)
        assertThat(actualConfig.jenkins.internal).isEqualTo(false)
        assertThat(actualConfig.features.secrets.vault.mode).isEqualTo(EXPECTED_VAULT_MODE)

        // Dynamic value (depends on vault mode)
        assertThat(actualConfig.features.secrets.active).isEqualTo(true)
    }

    @Test
    void "sets config application runningInsideK8s"() {
        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1").execute {
            Config actualConfig = applicationConfigurator.initConfig(testConfig)
            assertThat(actualConfig.application.runningInsideK8s).isEqualTo(true)
        }
    }
    
    @Test
    void "uses k8s services for jenkins and scmm if running as k8s job"() {
        testConfig.jenkins.url = ''
        testConfig.scmm.url = ''

        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1").execute {
            Config actualConfig = applicationConfigurator.initConfig(testConfig)

            assertThat(actualConfig.jenkins.url).isEqualTo("http://jenkins.jenkins.svc.cluster.local:80")
        }
    }

    @Test
    void 'Fails if jenkins is internal and scmm is external'() {
        testConfig.jenkins.url = ''
        testConfig.scmm.url = 'external'

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.validateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('When setting jenkins URL, scmm URL must also be set and the other way round')
    }
    
    @Test
    void 'Fails if jenkins is external and scmm is internal'() {
        testConfig.jenkins.url = 'external'
        testConfig.scmm.url = ''
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.validateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('When setting jenkins URL, scmm URL must also be set and the other way round')
    }
    
    @Test
    void 'Fails if monitoring local is not set'() {
        testConfig.application.mirrorRepos = true
        testConfig.application.localHelmChartFolder = ''
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.validateConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('Missing config for localHelmChartFolder.\n' +
                'Either run inside the official container image or setting env var LOCAL_HELM_CHART_FOLDER=\'charts\' ' +
                'after running \'scripts/downloadHelmCharts.sh\' from the repo')
    }

    @Test
    void 'Fails if createImagePullSecrets is used without secrets'() {
        testConfig.registry.createImagePullSecrets = true
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('createImagePullSecrets needs to be used with either registry username and password or the readOnly variants')
    }
    
    @Test
    void 'Ignores empty localHemlChartFolder, if mirrorRepos is not set'() {
        testConfig.application.mirrorRepos = false
        testConfig.application.localHelmChartFolder = ''
        
        applicationConfigurator.initConfig(testConfig)
        // no exceptions means success
    }
    
    @Test
    void "uses default localhost url for jenkins and scmm if nothing specified"() {
        testConfig.jenkins.url = ''
        testConfig.scmm.url = ''

        Config actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.jenkins.url).isEqualTo("http://localhost:9090")
    }

    @Test
    void "Certain properties are read from env"() {
        withEnvironmentVariable('SPRING_BOOT_HELM_CHART_REPO', 'value1').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.springBootHelmChart.url).isEqualTo('value1')
        }
        withEnvironmentVariable('SPRING_PETCLINIC_REPO', 'value2').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.springPetclinic.url).isEqualTo('value2')
        }
        withEnvironmentVariable('GITOPS_BUILD_LIB_REPO', 'value3').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.gitopsBuildLib.url).isEqualTo('value3')
        }
        withEnvironmentVariable('CES_BUILD_LIB_REPO', 'value4').execute {
            def actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.cesBuildLib.url).isEqualTo('value4')
        }
    }

    @Test
    void "base url: evaluates for all tools"() {
        testConfig.application.baseUrl = 'http://localhost'
        
        testConfig.features.argocd.active = true
        testConfig.features.mail.mailhog = true
        testConfig.features.monitoring.active = true
        testConfig.features.secrets.active = true

        Config actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd.localhost")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("http://mailhog.localhost")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("http://grafana.localhost")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("http://vault.localhost")
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic.localhost")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx.localhost")
        assertThat(actualConfig.scmm.ingress).isEqualTo("scmm.localhost")
        assertThat(actualConfig.jenkins.ingress).isEqualTo("jenkins.localhost")
    }

    @Test
    void "base url with url-hyphens: evaluates for all tools"() {
        testConfig.application.baseUrl = 'http://localhost'
        testConfig.application.urlSeparatorHyphen = true

        testConfig.features.argocd.active = true
        testConfig.features.mail.mailhog = true
        testConfig.features.monitoring.active = true
        testConfig.features.secrets.active = true

        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd-localhost")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("http://mailhog-localhost")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("http://grafana-localhost")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("http://vault-localhost")
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic-localhost")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx-localhost")
        assertThat(actualConfig.scmm.ingress).isEqualTo("scmm-localhost")
        assertThat(actualConfig.jenkins.ingress).isEqualTo("jenkins-localhost")
    }

    @Test
    void "base url: also works when port is included "() {
        testConfig.application.baseUrl = 'http://localhost:8080'
        testConfig.features.argocd.active = true

        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd.localhost:8080")
    }

    @Test
    void "base url: also works when port is included and use url-hyphens is set"() {
        testConfig.application.baseUrl = 'http://localhost:6502'
        testConfig.features.argocd.active = true
        testConfig.application.urlSeparatorHyphen = true

        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("http://argocd-localhost:6502")
    }


    @Test
    void "base url: does not evaluate for inactive tools"() {
        testConfig.features.argocd.active = false
        testConfig.features.mail.active = false
        testConfig.features.monitoring.active = false
        testConfig.features.secrets.active = false


        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo('')
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo('')
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo('')
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo('')
    }

    @Test
    void "base url: individual url params take precedence"() {
        testConfig.application.baseUrl = 'http://localhost'

        testConfig.features.argocd.active = true
        testConfig.features.mail.active = true
        testConfig.features.monitoring.active = true
        testConfig.features.secrets.active = true

        testConfig.features.argocd.url = 'argocd'
        testConfig.features.mail.mailhogUrl = 'mailhog'
        testConfig.features.monitoring.grafanaUrl = 'grafana'
        testConfig.features.secrets.vault.url = 'vault'
        testConfig.features.exampleApps.petclinic.baseDomain = 'petclinic'
        testConfig.features.exampleApps.nginx.baseDomain = 'nginx'

        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.argocd.url).isEqualTo("argocd")
        assertThat(actualConfig.features.mail.mailhogUrl).isEqualTo("mailhog")
        assertThat(actualConfig.features.monitoring.grafanaUrl).isEqualTo("grafana")
        assertThat(actualConfig.features.secrets.vault.url).isEqualTo("vault")
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx")
    }

    @Test
    void "Sets namePrefix"() {
        testConfig.application.namePrefix = 'my-prefix'

        def actualConfig = applicationConfigurator.initConfig(testConfig)
        assertThat(actualConfig.application.namePrefix.toString()).isEqualTo('my-prefix-')
        assertThat(actualConfig.application.namePrefixForEnvVars.toString()).isEqualTo('MY_PREFIX_')
    }

    @Test
    void "Sets namePrefix when ending in hyphen"() {
        testConfig.application.namePrefix = 'my-prefix-'

        def actualConfig = applicationConfigurator.initConfig(testConfig)
        assertThat(actualConfig.application.namePrefix.toString()).isEqualTo('my-prefix-')
        assertThat(actualConfig.application.namePrefixForEnvVars.toString()).isEqualTo('MY_PREFIX_')
    }
    
    @Test
    void "Registry: Sets to internal when no URL set"() {
        testConfig.registry.url = null
        testConfig.registry.proxyUrl = null
        
        def actualConfig = applicationConfigurator.initConfig(testConfig)
        
        assertThat(actualConfig.registry.url).isEqualTo('localhost:33333')
        assertThat(actualConfig.registry.internalPort).isEqualTo(EXPECTED_REGISTRY_INTERNAL_PORT)
        assertThat(actualConfig.registry.internal).isEqualTo(true)
    }
    
    @Test
    void "Registry: Sets to external when only registry URL set"() {
        testConfig.registry.proxyUrl = null

        def actualConfig = applicationConfigurator.initConfig(testConfig)
        
        assertThat(actualConfig.registry.internal).isEqualTo(false)
    }
    
    @Test
    void "Registry: Sets to internal when only proxy Url is set"() {
        testConfig.registry.url = null

        def actualConfig = applicationConfigurator.initConfig(testConfig)
        
        assertThat(actualConfig.registry.internal).isEqualTo(true)
    }
    
    @Test
    void "Registry: Fails when proxy but no username and password set"() {
        def expectedException = 'Proxy URL needs to be used with proxy-username and proxy-password'
        
        testConfig.registry.proxyUsername = null
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo(expectedException)
        
        testConfig.registry.proxyUsername = 'something'
        testConfig.registry.proxyPassword = null
        exception = shouldFail(RuntimeException) {
            applicationConfigurator.initConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo(expectedException)
        
        testConfig.registry.proxyUsername = null
        exception = shouldFail(RuntimeException) {
            applicationConfigurator.initConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo(expectedException)
    }

    @Test
    void "validateEnvConfig allows valid env entries"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = 'https://100.125.0.1:443'
        testConfig.features.argocd.env = [
            [name: "ENV_VAR_1", value: "value1"] ,
            [name: "ENV_VAR_2", value: "value2"]
        ] as List<Map<String, String>>

        // No exception should be thrown
        applicationConfigurator.initConfig(testConfig)
    }

    @Test
    void "validateEnvConfig throws exception for missing 'name' in env entry"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = 'https://100.125.0.1:443'
        testConfig.features.argocd.env = [
                [name: "ENV_VAR_1", value: "value1"],
                [value: "value2"]  // Missing 'name'
        ] as List<Map<String, String>>

        def exception = shouldFail(IllegalArgumentException) {
            applicationConfigurator.initConfig(testConfig)
        }

        assertThat(exception.message).contains("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: [value:value2]")
    }

    @Test
    void "validateEnvConfig throws exception for missing 'value' in env entry"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = 'https://100.125.0.1:443'
        testConfig.features.argocd.env = [
                [name: "ENV_VAR_1", value: "value1"],
                [name: "ENV_VAR_2"]  // Missing 'value'
        ] as List<Map<String, String>>

        def exception = shouldFail(IllegalArgumentException) {
            applicationConfigurator.initConfig(testConfig)
        }

        assertThat(exception.message).contains("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: [name:ENV_VAR_2]")
    }

    @Test
    void "validateEnvConfig throws exception for non-map env entry"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = 'https://100.125.0.1:443'
        testConfig.features.argocd.env = [
                [name: "ENV_VAR_1", value: "value1"],
                "invalid_entry"  // Invalid entry
        ] as List<Map<String, String>>

        def exception = shouldFail(IllegalArgumentException) {
            applicationConfigurator.initConfig(testConfig)
        }

        assertThat(exception.message).contains("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: invalid_entry")
    }

    @Test
    void "validateEnvConfig allows empty env list"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = 'https://100.125.0.1:443'
        testConfig.features.argocd.env

        // No exception should be thrown
        applicationConfigurator.initConfig(testConfig)
    }

    @Test
    void "validateEnvConfig skips validation when operator is false"() {
        testConfig.features.argocd.operator = false
        testConfig.features.argocd.env = [
                [name: "ENV_VAR_1", value: "value1"],
                [value: "value2"]  // Invalid entry, but should be ignored
        ] as List<Map<String, String>>

        // No exception should be thrown
        applicationConfigurator.initConfig(testConfig)
    }

    @Test
    void "should skip resourceInclusionsCluster setup when ArgoCD operator is not enabled"() {
        testConfig.features.argocd.operator = false

        // Calling the method should not make any changes to the config
        applicationConfigurator.initConfig(testConfig)

        assertThat(testLogger.getLogs().search("ArgoCD operator is not enabled. Skipping features.argocd.resourceInclusionsCluster setup."))
                .isNotEmpty()
    }

    @Test
    void "should validate and accept user-provided valid resourceInclusionsCluster URL"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = "https://valid-url.com"

        // Calling the method should accept the valid URL and not throw any exception
        applicationConfigurator.initConfig(testConfig)

        assertThat(testConfig.features.argocd.resourceInclusionsCluster).isEqualTo("https://valid-url.com")
        assertThat(testLogger.getLogs().search("Validating user-provided features.argocd.resourceInclusionsCluster URL: https://valid-url.com"))
                .isNotEmpty()
        assertThat(testLogger.getLogs().search("Found valid URL in features.argocd.resourceInclusionsCluster: https://valid-url.com"))
                .isNotEmpty()
    }

    @Test
    void "should throw exception for user-provided invalid resourceInclusionsCluster URL"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = "invalid-url"

        def exception = shouldFail(IllegalArgumentException) {
            applicationConfigurator.initConfig(testConfig)
        }

        assertThat(exception.message).contains("Invalid URL for 'features.argocd.resourceInclusionsCluster': invalid-url.")
    }

    @Test
    void "should set resourceInclusionsCluster using Kubernetes ENV variables when not provided by user"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = null

        // Set Kubernetes ENV variables
        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1")
                .and("KUBERNETES_SERVICE_PORT", "6443")
                .execute {
                    Config actualConfig = applicationConfigurator.initConfig(testConfig)

                    assertThat(actualConfig.features.argocd.resourceInclusionsCluster).isEqualTo("https://127.0.0.1:6443")

                    assertThat(testLogger.getLogs().search("Successfully set features.argocd.resourceInclusionsCluster via Kubernetes ENV to: https://127.0.0.1:6443"))
                            .isNotEmpty()
                }
    }

    @Test
    void "should throw exception when Kubernetes ENV variables are not set and resourceInclusionsCluster is null"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = null

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initConfig(testConfig)
        }

        assertThat(exception.message).contains("Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly.")
    }

    @Test
    void "should throw exception when Kubernetes ENV variables are not set and resourceInclusionsCluster is empty"() {
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = ''

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.initConfig(testConfig)
        }

        assertThat(exception.message).contains("Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly.")
    }

    @Test
    void "should throw exception for invalid Kubernetes constructed URL"() {
        // Set ArgoCD operator to true
        testConfig.features.argocd.operator = true
        testConfig.features.argocd.resourceInclusionsCluster = null

        // Set invalid Kubernetes ENV variables
        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "invalid_host")
                .and("KUBERNETES_SERVICE_PORT", "not_a_port")
                .execute {
                    def exception = shouldFail(RuntimeException) {
                        applicationConfigurator.initConfig(testConfig)
                    }

                    assertThat(exception.message).contains("Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true.")
                }

        assertThat(testLogger.getLogs().search("Constructed internal Kubernetes API Server URL: https://invalid_host:not_a_port")).isNotEmpty()
    }

    List<String> getAllFieldNames(Class clazz, String parentField = '', List<String> fieldNames = []) {
        clazz.declaredFields.each { field ->
            def currentField = parentField + field.name
            if (field.type instanceof Class
                    && !field.type.isArray()
                    && field.type.name.startsWith(Config.class.getPackageName())) {
                println "nested class $field.type, $currentField + '.', $fieldNames"
                getAllFieldNames(field.type, currentField + '.', fieldNames)
            } else {
                if (!field.name.startsWith('_') && !field.name.startsWith('$') && field.name != 'metaClass') {
                    fieldNames.add(currentField)
                }
            }
        }
        return fieldNames
    }

    List<String> getAllKeys(Map map, String parentKey = '', List<String> keysList = []) {
        map.each { key, value ->
            def currentKey = parentKey + key
            if (value instanceof Map && !value.isEmpty()) {
                getAllKeys(value, currentKey + '.', keysList)
            } else {
                keysList.add(currentKey)
            }
        }
        return keysList
    }
}