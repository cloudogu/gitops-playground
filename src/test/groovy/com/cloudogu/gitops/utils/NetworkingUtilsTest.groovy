package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat 

class NetworkingUtilsTest {

    Map config = [
            application: [
                    namePrefix: "foo-"
            ]
    ]
    
    K8sClientForTest k8sClient = new K8sClientForTest(config)
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    NetworkingUtils networkingUtils = new NetworkingUtils(k8sClient, commandExecutor)
    
    @Test
    void 'clusterBindAddress: returns bind address for external cluster'() {
        def internalNodeIp = "1.2.3.4"
        def localIp = "5.6.7.8"
        // getInternalNodeIp -> waitForNode()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '', 0))
        // getInternalNodeIp -> actual exec
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
        
        // getInternalNodeIp -> waitForNode(), don't care
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '', 0))
        // getInternalNodeIp -> actual exec
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', internalNodeIp, 0))

        def actualBindAddress = networkingUtils.findClusterBindAddress()
        
        assertThat(actualBindAddress).isEqualTo('localhost')
    }
    
    @Test
    void 'clusterBindAddress: fails when no potential bind address'() {
        def internalNodeIp = ''
        // getInternalNodeIp -> waitForNode()
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', '', 0))
        // getInternalNodeIp -> actual exec
        k8sClient.commandExecutorForTest.enqueueOutput(new CommandExecutor.Output('', internalNodeIp, 0))
        commandExecutor.enqueueOutput(new CommandExecutor.Output('',
                "1.0.0.0 via w.x.y.z dev someDevice src 1.2.3.4 uid 1000", 0))
        
        def exception = shouldFail(RuntimeException) {
            networkingUtils.findClusterBindAddress()
        }
        assertThat(exception.message).isEqualTo('Could not connect to kubernetes cluster: no cluster bind address')
    }
}
