package com.cloudogu.gitops.tools

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.deployment.Deployer
import com.cloudogu.gitops.infrastructure.deployment.DeploymentStrategy.RepoType
import com.cloudogu.gitops.infrastructure.helm.HelmClient
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

import java.nio.file.Path

import static com.cloudogu.gitops.config.Config.*
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.verify

@ExtendWith(MockitoExtension.class)
class RegistryTest {

    K8sClientForTest k8sClient
    Path temporaryYamlFile
    HelmClient helmClient

    @Mock
    Deployer deployer

    @Test
    void 'is disabled when external registry is configured'() {
        boolean enabled = createRegistry().install()
        assertThat(enabled).isEqualTo(false)
    }

    @Test
    void 'is installed'() {
        def registryConfig = new RegistrySchema(active: true, internal: true)

        createRegistry(registryConfig).install()

        assertThat(parseActualYaml()['service']['nodePort']).isEqualTo(DEFAULT_REGISTRY_PORT)
        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')

        verify(deployer).deployFeature(
                anyString(),
                eq('registry'),
                eq('docker-registry'),
                anyString(),
                eq('foo-registry'),
                eq('docker-registry'),
                any(Path),
                eq(RepoType.HELM),
                eq(true)
        )
    }

    @Test
    void 'inject custom value into chart'() {
        def registryConfig = new RegistrySchema(active: true,
                helm: new HelmConfigWithValues(chart: 'test',
                        values: [service    : [type: 'NodePortTest'],
                                 customValue: 'testinjectionValue']))

        createRegistry(registryConfig).install()
        assertThat(parseActualYaml()['service'] as String).contains('NodePortTest')
        assertThat(parseActualYaml()['customValue'] as String).contains('testinjectionValue')
    }

    private Registry createRegistry(RegistrySchema registryConfig = new RegistrySchema()) {
        def config = new Config(application: new ApplicationSchema(namePrefix: 'foo-'),
                registry: registryConfig)
        k8sClient = new K8sClientForTest()

		FileSystemUtils fileUtil = new FileSystemUtils() {
			@Override
			Path writeTempFile(Map mergeMap) {
				def ret = super.writeTempFile(mergeMap)
				temporaryYamlFile = Path.of(ret.toString().replace(".ftl", ""))
				// Path after template invocation
				return ret
			}
		}
		AirGappedUtils airGappedUtils =  new AirGappedUtils(config,null,fileUtil,helmClient, null)
		// We use the real FileSystemUtils and not a mock to make sure file editing works as expected
		new Registry(config,fileUtil, k8sClient, airGappedUtils, deployer)
	}

	private Map parseActualYaml() {
		def ys = new YamlSlurper()
		return ys.parse(temporaryYamlFile) as Map
	}

}