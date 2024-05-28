package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat 

class IngressNginxTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
            ],
            features:[
                    ingressNginx: [
                            active: false,
                            helm  : [
                                    chart: 'ingress-nginx',
                                    repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                    version: '4.8.2',
                                    values : [:]
                            ],
                    ],
            ],
    ]

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(commandExecutor)
    Path temporaryYamlFile

    @Test
    void 'When Ingress-Nginx is enabled, ingressClassResource is set to true'() {
        config.features['ingressNginx']['active'] = true

        createIngressNginx().install()

        def expected = new YamlSlurper().parseText(new TemplatingEngine().template(new File(IngressNginx.HELM_VALUES_PATH), [:]))
        def actual = parseActualYaml()

        assertThat(expected).isEqualTo(actual)
        assertThat((actual['controller']['replicaCount'])).isEqualTo(2)
    }

    @Test
    void 'When Ingress-Nginx is not enabled, ingress-nginx-helm-values yaml has no content'() {
        config.features['ingressNginx']['active'] = false

        createIngressNginx().install()

        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'additional helm values merged with default values'() {
        config.features['ingressNginx']['active'] = true
        config['features']['ingressNginx']['helm']['values'] = [
                controller: [
                        replicaCount: 42,
                        span: '7,5',
                   ]
        ]

        createIngressNginx().install()
        def actual = parseActualYaml()

        assertThat((actual['controller']['replicaCount'])).isEqualTo(42)
        assertThat((actual['controller']['span'])).isEqualTo('7,5')
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
        }, new HelmStrategy(new Configuration(config), helmClient))
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }

}
