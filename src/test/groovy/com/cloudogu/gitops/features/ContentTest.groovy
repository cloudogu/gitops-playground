package com.cloudogu.gitops.features

import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ContentTest {

    Config config = new Config(
            application: new Config.ApplicationSchema(
                    namePrefix: 'foo-'),
            registry: new Config.RegistrySchema(
                    url: 'reg-url',
                    path: 'reg-path',
                    username: 'reg-user',
                    password: 'reg-pw',
                    createImagePullSecrets: false,))
    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    K8sClientForTest k8sClient = new K8sClientForTest(config, k8sCommands)

    @Test
    void 'deploys image pull secrets'() {
        config.registry.createImagePullSecrets = true

        createContent().install()

        assertRegistrySecrets('reg-user', 'reg-pw')
    }

    @Test
    void 'deploys image pull secrets from read-only vars'() {
        config.registry.createImagePullSecrets = true
        config.registry.readOnlyUsername = 'other-user'
        config.registry.readOnlyPassword = 'other-pw'

        createContent().install()

        assertRegistrySecrets('other-user', 'other-pw')
    }

    @Test
    void 'deploys additional image pull secrets for proxy registry'() {
        config.registry.createImagePullSecrets = true
        config.registry.twoRegistries = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'

        // Simulate argocd Namespace does not exist
        k8sCommands.enqueueOutput(new CommandExecutor.Output('namespace not found', '', 1)) // Namespace not exit
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 1)) // Namespace not exit


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
        new Content(config, k8sClient)
    }

    private parseActualYaml(File pathToYamlFile) {
        def ys = new YamlSlurper()
        return ys.parse(pathToYamlFile)
    }


}
