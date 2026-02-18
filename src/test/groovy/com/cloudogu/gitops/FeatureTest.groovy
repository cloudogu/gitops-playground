package com.cloudogu.gitops

import org.junit.jupiter.api.Test

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.K8sClientForTest

class FeatureTest {
    Config config = new Config(
            application: new Config.ApplicationSchema(
                    namePrefix: "foo-")
    )

    K8sClientForTest k8sClient = new K8sClientForTest( config)

    @Test
    void 'Image pull secrets are create automatically'() {
        config.registry.createImagePullSecrets = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'
        config.registry.url = 'url'
        config.registry.readOnlyUsername = 'ROuser'
        config.registry.readOnlyPassword = 'ROpw'
        config.registry.username = 'user'
        config.registry.password = 'pw'

        createFeatureWithImage().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-my-ns' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
    }

    protected FeatureWithImageForTest createFeatureWithImage() {
        Feature feature = new FeatureWithImageForTest()
        feature.config = config
        feature.k8sClient = k8sClient
        feature.namespace = 'foo-my-ns'
        feature
    }

    @Test
    void 'Image pull secrets: Falls back to using readOnly credentials and URL '() {
        config.registry.createImagePullSecrets = true
        config.registry.url = 'url'
        config.registry.readOnlyUsername = 'ROuser'
        config.registry.readOnlyPassword = 'ROpw'
        config.registry.username = 'user'
        config.registry.password = 'pw'

        createFeatureWithImage().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-my-ns' +
                        ' --docker-server url --docker-username ROuser --docker-password ROpw')
    }

    @Test
    void 'Image pull secrets: Falls back to using credentials and URL '() {
        config.registry.createImagePullSecrets = true
        config.registry.url = 'url'
        config.registry.username = 'user'
        config.registry.password = 'pw'

        createFeatureWithImage().install()

        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-my-ns' +
                        ' --docker-server url --docker-username user --docker-password pw')
    }

    class FeatureWithImageForTest extends Feature implements FeatureWithImage {

        String namespace
        Config config
        K8sClient k8sClient

        @Override
        boolean isEnabled() {
            return true
        }
    }
}
