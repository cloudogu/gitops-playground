package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat 

class ContentTest {

    Map config = [
            registry   : [
                    url                   : 'reg-url',
                    path                  : 'reg-path',
                    username              : 'reg-user',
                    password              : 'reg-pw',
                    createImagePullSecrets: false,
            ],
            application: [
                    namePrefix: "foo-",
            ],
    ]
    K8sClientForTest k8sClient = new K8sClientForTest(config)


    @Test
    void 'deploys image pull secrets'() {
        config['registry']['createImagePullSecrets'] = true

        createContent().install()

        assertRegistrySecrets('reg-user', 'reg-pw')
    }

    @Test
    void 'deploys image pull secrets from read-only vars'() {
        config['registry']['createImagePullSecrets'] = true
        config['registry']['readOnlyUsername'] = 'other-user'
        config['registry']['readOnlyPassword'] = 'other-pw'

        createContent().install()

        assertRegistrySecrets('other-user', 'other-pw')
    }

    @Test
    void 'deploys additional image pull secrets for proxy registry'() {
        config['registry']['createImagePullSecrets'] = true
        config['registry']['twoRegistries'] = true
        config['registry']['proxyUrl'] = 'proxy-url'
        config['registry']['proxyUsername'] = 'proxy-user'
        config['registry']['proxyPassword'] = 'proxy-pw'

        createContent().install()

        assertRegistrySecrets('reg-user', 'reg-pw')

        k8sClient.commandExecutorForTest.assertExecuted('kubectl create namespace foo-example-apps-staging')
        k8sClient.commandExecutorForTest.assertExecuted('kubectl create namespace foo-example-apps-production')
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-example-apps-staging' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-example-apps-production' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')

    }

    private void assertRegistrySecrets(String regUser, String regPw) {
        List expectedNamespaces = ["foo-example-apps-staging", "foo-example-apps-production"]
        expectedNamespaces.each {

            k8sClient.commandExecutorForTest.assertExecuted(
                    "kubectl create secret docker-registry registry -n ${it}" +
                            " --docker-server reg-url --docker-username $regUser --docker-password ${regPw}" +
                            ' --dry-run=client -oyaml | kubectl apply -f-')

            def patchCommand = k8sClient.commandExecutorForTest.assertExecuted(
                    "kubectl patch serviceaccount default -n ${it}")
            String patchFile = (patchCommand =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
            assertThat(parseActualYaml(new File(patchFile))['imagePullSecrets'] as List).hasSize(1)
            assertThat((parseActualYaml(new File(patchFile))['imagePullSecrets'] as List)[0] as Map).containsEntry('name', 'registry')
        }
    }

    private Content createContent() {
        new Content(new Configuration(config), k8sClient)
    }

    private parseActualYaml(File pathToYamlFile) {
        def ys = new YamlSlurper()
        return ys.parse(pathToYamlFile)
    }
}
