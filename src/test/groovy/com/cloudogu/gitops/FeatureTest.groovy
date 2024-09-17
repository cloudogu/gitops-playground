package com.cloudogu.gitops

import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.K8sClientForTest
import org.junit.jupiter.api.Test 

class FeatureTest {
    Map config = [
            registry: [
                    createImagePullSecrets: false
            ],
            application: [
                    namePrefix: "foo-"
            ]
    ]
    K8sClientForTest k8sClient = new K8sClientForTest(config)

    @Test
    void 'Image pull secrets are create automatically'() {
        config['registry']['createImagePullSecrets'] = true
        config['registry']['twoRegistries'] = true
        config['registry']['proxyUrl'] = 'proxy-url'
        config['registry']['proxyUsername'] = 'proxy-user'
        config['registry']['proxyPassword'] = 'proxy-pw'

        Feature feature = new FeatureWithImageForTest()
        feature.config = config
        feature.k8sClient = k8sClient
        feature.namespace = 'my-ns'

        feature.install()
        
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-my-ns' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
    }

    class FeatureWithImageForTest extends Feature implements FeatureWithImage {

        String namespace
        Map config
        K8sClient k8sClient

        @Override
        boolean isEnabled() {
            return true
        }
    }
}
