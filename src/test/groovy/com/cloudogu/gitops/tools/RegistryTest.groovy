package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.ContextBuilder
import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType
import com.cloudogu.gitops.infrastructure.helm.HelmClient
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.transform.CompileDynamic
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

import java.nio.file.Path

import static com.cloudogu.gitops.config.Config.*
import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.verify

@CompileDynamic
@ExtendWith(MockitoExtension)
class RegistryTest {

    K8sClientForTest k8sClient
    Path temporaryYamlFile
    HelmClient helmClient
    DeploymentContext deploymentContext

    @Mock
    Deployer deployer

    @Mock
    RepositoryWorkspace repositoryWorkspace

    @Test
    void 'is disabled when external registry is configured'() {
        def registryConfig = new RegistrySchema()

        assertFalse(createRegistry(registryConfig).isEnabled(createContext(registryConfig)))
    }

    @Test
    void 'is installed'() {
        def registryConfig = new RegistrySchema(active: true, internal: true)

        install(createRegistry(registryConfig), registryConfig)

        assertThat(parseActualYaml()['service']['nodePort']).isEqualTo(DEFAULT_REGISTRY_PORT)
        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')

        verify(deployer).deployFeature(anyString(),
                eq('registry'),
                eq('docker-registry'),
                anyString(),
                eq('foo-registry'),
                eq('docker-registry'),
                any(Path),
                eq(RepoType.HELM),
                eq(true),
                eq(deploymentContext),
                eq(repositoryWorkspace))

        verify(repositoryWorkspace).commitAndPushClusterResourcesChanges('Update registry GitOps resources')
    }

    @Test
    void 'inject custom value into chart'() {
        def registryConfig = new RegistrySchema(active: true,
                internal: true,
                helm: new HelmConfigWithValues(chart: 'test',
                        values: [service    : [type: 'NodePortTest'],
                                 customValue: 'testinjectionValue']))

        install(createRegistry(registryConfig), registryConfig)

        assertThat(parseActualYaml()['service'] as String).contains('NodePortTest')
        assertThat(parseActualYaml()['customValue'] as String).contains('testinjectionValue')

        verify(repositoryWorkspace).commitAndPushClusterResourcesChanges('Update registry GitOps resources')
    }

    private Registry createRegistry(RegistrySchema registryConfig = new RegistrySchema()) {
        def config = createConfig(registryConfig)
        k8sClient = new K8sClientForTest()

        FileSystemUtils fileUtil = new FileSystemUtils() {
            @Override
            Path writeTempFile(Map mergeMap) {
                def ret = super.writeTempFile(mergeMap)
                temporaryYamlFile = Path.of(ret.toString().replace('.ftl', ''))
                // Path after template invocation
                return ret
            }
        }

        AirGappedUtils airGappedUtils = new AirGappedUtils(null, fileUtil, helmClient, null)

        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        return new Registry(fileUtil, k8sClient, airGappedUtils, deployer, new RegistryToolConfigMapper())
    }

    private boolean install(Registry registry, RegistrySchema registryConfig) {
        deploymentContext = createContext(registryConfig)
        return registry.execute(deploymentContext, repositoryWorkspace)
    }

    private DeploymentContext createContext(RegistrySchema registryConfig) {
        return new ContextBuilder(createConfig(registryConfig)).build()
    }

    private Config createConfig(RegistrySchema registryConfig) {
        return new Config(application: new ApplicationSchema(namePrefix: 'foo-'),
                registry: registryConfig)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }
}
