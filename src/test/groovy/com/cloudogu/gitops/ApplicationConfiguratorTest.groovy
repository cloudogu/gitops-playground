package com.cloudogu.gitops

import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.cloudogu.gitops.config.schema.JsonSchemaValidator
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
import static org.mockito.Mockito.never
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

/**
 * If you would like to run this test in the IDE, add the following JVM options. The same is done in pom.xml
 * --add-opens java.base/java.util=ALL-UNNAMED
 */
class ApplicationConfiguratorTest {

    static final String EXPECTED_REGISTRY_URL = 'http://my-reg'
    static final int EXPECTED_REGISTRY_INTERNAL_PORT = 33333
    static final boolean EXPECTED_ARGOCD = false
    static final String EXPECTED_VAULT_MODE = 'prod'
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
                    pullUrl: "pull-$EXPECTED_REGISTRY_URL",
                    pushUrl: "push-$EXPECTED_REGISTRY_URL",
                    internalPort: EXPECTED_REGISTRY_INTERNAL_PORT,
                    path        : null
            ],
            jenkins    : [
                    url     : EXPECTED_JENKINS_URL
                    ],
            scmm       : [
                    url     : EXPECTED_SCMM_URL,
                    ],
            features    : [
                    argocd : [
                            active    : EXPECTED_ARGOCD
                    ],
                    secrets : [
                            vault : [
                                    mode : EXPECTED_VAULT_MODE
                            ]
                    ],
                    mail: [:],
                    monitoring: [:],
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
        applicationConfigurator = new ApplicationConfigurator(networkingUtils, fileSystemUtils, new JsonSchemaValidator(new JsonSchemaGenerator()))
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

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig['registry']['internalPort']).isEqualTo(EXPECTED_REGISTRY_INTERNAL_PORT)
        assertThat(actualConfig['registry']['url']).isEqualTo(EXPECTED_REGISTRY_URL)
        assertThat(actualConfig['registry']['path']).isEqualTo('')
        assertThat(actualConfig['registry']['internal']).isEqualTo(false)
        assertThat(actualConfig['registry']['twoRegistries']).isEqualTo(true)
        
        assertThat(actualConfig['features']['argocd']['active']).isEqualTo(EXPECTED_ARGOCD)

        assertThat(actualConfig['jenkins']['url']).isEqualTo(EXPECTED_JENKINS_URL)
        assertThat(actualConfig['jenkins']['internal']).isEqualTo(false)
        
        assertThat(actualConfig['features']['secrets']['vault']['mode']).isEqualTo(EXPECTED_VAULT_MODE)
        // Default value
        assertThat(actualConfig['features']['secrets']['externalSecrets']).isNotNull()
        // Dynamic vaule (depends on vault mode)
        assertThat(actualConfig['features']['secrets']['active']).isEqualTo(true)
    }
    
    @Test
    void "Is able to skip setting internal config"() {
        Map actualConfig = applicationConfigurator.setConfig(testConfig, true)

        verify(networkingUtils, never()).findClusterBindAddress()
        // Dynamic vaule (depends on vault mode)
        assertThat(actualConfig['features']['secrets']['active']).isEqualTo(false)
    }
    

    @Test
    void "uses k8s services for jenkins and scmm if running as k8s job"() {
        testConfig.jenkins['url'] = ''
        testConfig.scmm['url'] = ''

        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "127.0.0.1").execute {
            Map actualConfig = applicationConfigurator.setConfig(testConfig)

            assertThat(actualConfig.scmm['url']).isEqualTo("http://scmm-scm-manager.default.svc.cluster.local:80/scm")
            assertThat(actualConfig.jenkins['url']).isEqualTo("http://jenkins.default.svc.cluster.local:80")
        }
    }

    @Test
    void 'Fails if jenkins is internal and scmm is external'() {
        testConfig.jenkins['url'] = ''
        testConfig.scmm['url'] = 'external'

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.setConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('When setting jenkins URL, scmm URL must also be set and the other way round')
    }
    
    @Test
    void 'Fails if jenkins is external and scmm is internal'() {
        testConfig.jenkins['url'] = 'external'
        testConfig.scmm['url'] = ''
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.setConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('When setting jenkins URL, scmm URL must also be set and the other way round')
    }
    
    @Test
    void 'Fails if monitoring local is not set'() {
        testConfig['application']['mirrorRepos'] = true
        testConfig['application']['localHelmChartFolder'] = ''
        
        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.setConfig(testConfig)
        }
        assertThat(exception.message).isEqualTo('Missing config for localHelmChartFolder.\n' +
                'Either run inside the official container image or setting env var LOCAL_HELM_CHART_FOLDER=\'charts\' ' +
                'after running \'scripts/downloadHelmCharts.sh\' from the repo')
    }
    
    @Test
    void 'Ignores empty localHemlChartFolder, if mirrorRepos is not set'() {
        testConfig['application']['mirrorRepos'] = false
        testConfig['application']['localHelmChartFolder'] = ''
        
        applicationConfigurator.setConfig(testConfig)
        // no exceptions means success
    }
    
    @Test
    void "uses default localhost url for jenkins and scmm if nothing specified"() {
        testConfig.jenkins['url'] = ''
        testConfig.scmm['url'] = ''

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.scmm['url']).isEqualTo("http://localhost:9091/scm")
        assertThat(actualConfig.jenkins['url']).isEqualTo("http://localhost:9090")
    }

    @Test
    void "Certain properties are read from env"() {
        withEnvironmentVariable('SPRING_BOOT_HELM_CHART_REPO', 'value1').execute {
            Map actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils, new JsonSchemaValidator(new JsonSchemaGenerator())).setConfig(testConfig)
            assertThat(actualConfig['repositories']['springBootHelmChart']['url']).isEqualTo('value1')
        }
        withEnvironmentVariable('SPRING_PETCLINIC_REPO', 'value2').execute {
            Map actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils, new JsonSchemaValidator(new JsonSchemaGenerator())).setConfig(testConfig)
            assertThat(actualConfig['repositories']['springPetclinic']['url']).isEqualTo('value2')
        }
        withEnvironmentVariable('GITOPS_BUILD_LIB_REPO', 'value3').execute {
            Map actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils, new JsonSchemaValidator(new JsonSchemaGenerator())).setConfig(testConfig)
            assertThat(actualConfig['repositories']['gitopsBuildLib']['url']).isEqualTo('value3')
        }
        withEnvironmentVariable('CES_BUILD_LIB_REPO', 'value4').execute {
            Map actualConfig = new ApplicationConfigurator(networkingUtils, fileSystemUtils, new JsonSchemaValidator(new JsonSchemaGenerator())).setConfig(testConfig)
            assertThat(actualConfig['repositories']['cesBuildLib']['url']).isEqualTo('value4')
        }
    }

    @Test
    void 'cli overwrites config file'() {
        def configFile = File.createTempFile("gitops-playground", '.yaml')
        configFile.deleteOnExit()
        configFile.text = """
images:
  kubectl: "localhost:30000/kubectl"
  helm: "localhost:30000/helm"
        """

        applicationConfigurator.setConfig(almostEmptyConfig)
        applicationConfigurator
                .setConfig(configFile)
        def config = applicationConfigurator
                .setConfig([
                        images: [
                                kubectl: null, // do not overwrite default value
                                helm   : "localhost:30000/cli/helm",
                        ]
                ])

        assertThat(config['images']['kubectl']).isEqualTo('localhost:30000/kubectl')
        assertThat(config['images']['helm']).isEqualTo('localhost:30000/cli/helm')
    }

    @Test
    void "config is deeply immutable"() {
        // Avoids failing due to compile static 🤷‍♂️
        ApplicationConfigurator configurator = applicationConfigurator
        shouldFail(UnsupportedOperationException) {
            configurator.config['application']['remote'] = true
        }
        shouldFail(UnsupportedOperationException) {
            configurator.setConfig(testConfig)['application']['remote'] = true
        }
    }

    @Test
    void "config file has only fields that are present in default values"() {
        Map defaultConfig = applicationConfigurator.setConfig(almostEmptyConfig)
        
        def fields = getAllFieldNames(Schema.class).sort()
        def keys = getAllKeys(defaultConfig).sort()

        assertThat(fields).isSubsetOf(keys)
    }
    
    @Test
    void "base url: evaluates for all tools"() {
        testConfig.application['baseUrl'] = 'http://localhost'
        
        testConfig.features['argocd']['active'] = true
        testConfig.features['mail']['mailhog'] = true
        testConfig.features['monitoring']['active'] = true
        testConfig.features['secrets']['active'] = true

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.features['argocd']['url']).isEqualTo("http://argocd.localhost")
        assertThat(actualConfig.features['mail']['mailhogUrl']).isEqualTo("http://mailhog.localhost")
        assertThat(actualConfig.features['monitoring']['grafanaUrl']).isEqualTo("http://grafana.localhost")
        assertThat(actualConfig.features['secrets']['vault']['url']).isEqualTo("http://vault.localhost")
        assertThat(actualConfig.features['exampleApps']['petclinic']['baseDomain']).isEqualTo("petclinic.localhost")
        assertThat(actualConfig.features['exampleApps']['nginx']['baseDomain']).isEqualTo("nginx.localhost")
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

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.features['argocd']['url']).isEqualTo("http://argocd-localhost")
        assertThat(actualConfig.features['mail']['mailhogUrl']).isEqualTo("http://mailhog-localhost")
        assertThat(actualConfig.features['monitoring']['grafanaUrl']).isEqualTo("http://grafana-localhost")
        assertThat(actualConfig.features['secrets']['vault']['url']).isEqualTo("http://vault-localhost")
        assertThat(actualConfig.features['exampleApps']['petclinic']['baseDomain']).isEqualTo("petclinic-localhost")
        assertThat(actualConfig.features['exampleApps']['nginx']['baseDomain']).isEqualTo("nginx-localhost")
        assertThat(actualConfig.scmm['ingress']).isEqualTo("scmm-localhost")
    }

    @Test
    void "base url: also works when port is included "() {
        testConfig.application['baseUrl'] = 'http://localhost:8080'
        testConfig.features['argocd']['active'] = true

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.features['argocd']['url']).isEqualTo("http://argocd.localhost:8080")
    }

    @Test
    void "base url: also works when port is included and use url-hyphens is set"() {
        testConfig.application['baseUrl'] = 'http://localhost:6502'
        testConfig.features['argocd']['active'] = true
        testConfig.application['urlSeparatorHyphen'] = true

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.features['argocd']['url']).isEqualTo("http://argocd-localhost:6502")
    }


    @Test
    void "base url: does not evaluate for inactive tools"() {
        testConfig.features['argocd']['active'] = false
        testConfig.features['mail']['active'] = false
        testConfig.features['monitoring']['active'] = false
        testConfig.features['secrets']['active'] = false

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.features['argocd']['url']).isEqualTo("")
        assertThat(actualConfig.features['mail']['mailhogUrl']).isEqualTo("")
        assertThat(actualConfig.features['monitoring']['grafanaUrl']).isEqualTo("")
        assertThat(actualConfig.features['secrets']['vault']['url']).isEqualTo("")
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

        Map actualConfig = applicationConfigurator.setConfig(testConfig)

        assertThat(actualConfig.features['argocd']['url']).isEqualTo("argocd")
        assertThat(actualConfig.features['mail']['mailhogUrl']).isEqualTo("mailhog")
        assertThat(actualConfig.features['monitoring']['grafanaUrl']).isEqualTo("grafana")
        assertThat(actualConfig.features['secrets']['vault']['url']).isEqualTo("vault")
        assertThat(actualConfig.features['exampleApps']['petclinic']['baseDomain']).isEqualTo("petclinic")
        assertThat(actualConfig.features['exampleApps']['nginx']['baseDomain']).isEqualTo("nginx")
    }

    @Test
    void "Sets namePrefix"() {
        testConfig.application['namePrefix'] = 'my-prefix'

        Map actualConfig = applicationConfigurator.setConfig(testConfig)
        assertThat(actualConfig.application['namePrefix'].toString()).isEqualTo('my-prefix-')
        assertThat(actualConfig.application['namePrefixForEnvVars'].toString()).isEqualTo('MY_PREFIX_')
    }

    @Test
    void "Sets namePrefix when ending in hyphen"() {
        testConfig.application['namePrefix'] = 'my-prefix-'

        Map actualConfig = applicationConfigurator.setConfig(testConfig)
        assertThat(actualConfig.application['namePrefix'].toString()).isEqualTo('my-prefix-')
        assertThat(actualConfig.application['namePrefixForEnvVars'].toString()).isEqualTo('MY_PREFIX_')
    }
    
    @Test
    void "Registry: Sets to internal when no URL set"() {
        testConfig.registry['url'] = null
        testConfig.registry['pullUrl'] = null
        testConfig.registry['pushUrl'] = null
        
        Map actualConfig = applicationConfigurator.setConfig(testConfig)
        
        assertThat(actualConfig['registry']['url'].toString()).isEqualTo('localhost:33333')
        assertThat(actualConfig['registry']['internalPort']).isEqualTo(EXPECTED_REGISTRY_INTERNAL_PORT)
        assertThat(actualConfig['registry']['internal']).isEqualTo(true)
    }
    
    @Test
    void "Registry: Sets to external when single URL set"() {
        testConfig.registry['pullUrl'] = null
        testConfig.registry['pushUrl'] = null
        
        Map actualConfig = applicationConfigurator.setConfig(testConfig)
        
        assertThat(actualConfig['registry']['internal']).isEqualTo(false)
    }
    
    @Test
    void "Registry: Sets to external when pull and push URL set"() {
        testConfig.registry['url'] = null
        
        Map actualConfig = applicationConfigurator.setConfig(testConfig)
        
        assertThat(actualConfig['registry']['internal']).isEqualTo(false)
    }
    
    @Test
    void "Registry: Fails when pushUrl is set but not pullUrl"() {
        testConfig.registry['pullUrl'] = null

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.setConfig(testConfig)
        }
        assertThat(exception.message)
                .isEqualTo("Always set pull AND push URL. pullUrl=, pushUrl=push-${EXPECTED_REGISTRY_URL}".toString())
    }
    
    @Test
    void "Registry: Fails when pullUrl is set but not pushUrl"() {
        testConfig.registry['pushUrl'] = null

        def exception = shouldFail(RuntimeException) {
            applicationConfigurator.setConfig(testConfig)
        }
        assertThat(exception.message)
                .isEqualTo("Always set pull AND push URL. pullUrl=pull-${EXPECTED_REGISTRY_URL}, pushUrl=".toString())
    }
    
    List<String> getAllFieldNames(Class clazz, String parentField = '', List<String> fieldNames = []) {
        clazz.declaredFields.each { field ->
            def currentField = parentField + field.name
            if (field.type instanceof Class
                    && !field.type.isArray()
                    && field.type.name.startsWith(Schema.class.getPackageName())) {
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
