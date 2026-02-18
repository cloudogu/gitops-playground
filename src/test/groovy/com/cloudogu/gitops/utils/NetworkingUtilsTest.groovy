package com.cloudogu.gitops.utils

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.Test

import com.cloudogu.gitops.config.Config

class NetworkingUtilsTest {

    Config config = new Config(application: new Config.ApplicationSchema(namePrefix: "foo-"))

    K8sClientForTest k8sClient = new K8sClientForTest(config)
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    NetworkingUtils networkingUtils = new NetworkingUtils(k8sClient, commandExecutor)

    @Test
    void 'clusterBindAddress: returns bind address for external cluster'() {
        def internalNodeIp = "1.2.3.4"
        def localIp = "5.6.7.8"
        // waitForInternalNodeIp -> waitForNode()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', 'node/something', 0))
        // waitForInternalNodeIp -> actual exec
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', internalNodeIp, 0))
        commandExecutor.enqueueOutput(new CommandExecutor.Output('',
                "1.0.0.0 via w.x.y.z dev someDevice src ${localIp} uid 1000", 0))

        def actualBindAddress = networkingUtils.findClusterBindAddress()

        assertThat(actualBindAddress).isEqualTo(internalNodeIp)
    }

    @Test
    void 'clusterBindAddress: returns localhost when node IP and local IP are equal'() {
        def internalNodeIp = networkingUtils.localAddress
        assertThat(internalNodeIp).isNotEmpty()

        // waitForInternalNodeIp -> waitForNode(), don't care
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', 'node/something', 0))
        // waitForInternalNodeIp -> actual exec
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', internalNodeIp, 0))

        def actualBindAddress = networkingUtils.findClusterBindAddress()

        assertThat(actualBindAddress).isEqualTo('localhost')
    }

    @Test
    void 'clusterBindAddress: fails when no potential bind address'() {
        def internalNodeIp = ''
        // waitForInternalNodeIp -> waitForNode()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', 'node/something', 0))
        // waitForInternalNodeIp -> actual exec
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', internalNodeIp, 0))
        commandExecutor.enqueueOutput(new CommandExecutor.Output('',
                "1.0.0.0 via w.x.y.z dev someDevice src 1.2.3.4 uid 1000", 0))

        def exception = shouldFail(RuntimeException) {
            networkingUtils.findClusterBindAddress()
        }
        assertThat(exception.message).isEqualTo('Failed to retrieve internal node IP')
    }

    @Test
    void 'get hosts'() {
        assertThat(NetworkingUtils.getHost("https://example.com")).isEqualTo("example.com")
        assertThat(NetworkingUtils.getHost("http://example.com")).isEqualTo("example.com")
        assertThat(NetworkingUtils.getHost("")).isEqualTo("")
        assertThat(NetworkingUtils.getHost("example.com")).isEqualTo("example.com")

        // Legacy! The function is misleading.
        //assertThat(NetworkingUtils.getHost("http://example.com/bla")).isEqualTo("example.com")
        //assertThat(NetworkingUtils.getHost("http://example.com:9090/bla")).isEqualTo("example.com")
        //assertThat(NetworkingUtils.getHost("example.com/bla")).isEqualTo("example.com")
        //assertThat(NetworkingUtils.getHost("example.com:9090/bla")).isEqualTo("example.com")
        assertThat(NetworkingUtils.getHost("http://example.com/bla")).isEqualTo("example.com/bla")
        assertThat(NetworkingUtils.getHost("http://example.com:9090/bla")).isEqualTo("example.com:9090/bla")
        assertThat(NetworkingUtils.getHost("example.com/bla")).isEqualTo("example.com/bla")
        assertThat(NetworkingUtils.getHost("example.com:9090/bla")).isEqualTo("example.com:9090/bla")

        // More legacy, known bugs. We should get rid of this method and scmm.host and scmm.protocol altogether!
        // assertThat(NetworkingUtils.getHost("ftp://example.com")).isEqualTo("example.com")
    }

    @Test
    void 'get protocols'() {
        assertThat(NetworkingUtils.getProtocol("https://example.com")).isEqualTo("https")
        assertThat(NetworkingUtils.getProtocol("http://example.com")).isEqualTo("http")
        assertThat(NetworkingUtils.getProtocol("ftp://example.com")).isEqualTo("")
        assertThat(NetworkingUtils.getProtocol("example.com")).isEqualTo("")
        assertThat(NetworkingUtils.getProtocol("")).isEqualTo("")
    }
}
