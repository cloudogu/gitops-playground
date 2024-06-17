package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.verify 

class IngressNginxTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
                    podResources: false
            ],
            features:[
                    ingressNginx: [
                            active: true,
                            helm  : [
                                    chart: 'ingress-nginx',
                                    repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                    version: '4.8.2',
                                    values : [:]
                            ],
                    ],
            ],
    ]

    DeploymentStrategy deploymentStrategy = mock(DeploymentStrategy)

    Path temporaryYamlFile

    @Test
    void 'Helm release is installed'() {
        createIngressNginx().install()

        /* Assert one default value */
        def actual = parseActualYaml()
        assertThat(actual['controller']['replicaCount']).isEqualTo(2)
        
        verify(deploymentStrategy).deployFeature('https://kubernetes.github.io/ingress-nginx', 'ingress-nginx',
                'ingress-nginx', '4.8.2','ingress-nginx',
                'ingress-nginx', temporaryYamlFile)
        assertThat(parseActualYaml()['controller']['resources']).isNull()
    }
    
    @Test
    void 'Sets pod resource limits and requests'() {
        config.application['podResources'] = true

        createIngressNginx().install()
        
        assertThat(parseActualYaml()['controller']['resources'] as Map).containsKeys('limits', 'requests')
    }

    @Test
    void 'When Ingress-Nginx is not enabled, ingress-nginx-helm-values yaml has no content'() {
        config.features['ingressNginx']['active'] = false

        createIngressNginx().install()

        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'additional helm values merged with default values'() {
        config['features']['ingressNginx']['helm']['values'] = [
                controller: [
                        replicaCount: 42,
                        span: '7,5',
                   ]
        ]

        createIngressNginx().install()
        def actual = parseActualYaml()

        assertThat(actual['controller']['replicaCount']).isEqualTo(42)
        assertThat(actual['controller']['span']).isEqualTo('7,5')
    }


    private IngressNginx createIngressNginx() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        new IngressNginx(new Configuration(config), new FileSystemUtils() {
            @Override
            Path createTempFile() {
                def ret = super.createTempFile()
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")) // Path after template invocation

                return ret
            }
        }, deploymentStrategy)
    }

    private Map parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile) as Map
    }

}
