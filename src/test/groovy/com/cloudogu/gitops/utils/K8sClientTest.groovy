package com.cloudogu.gitops.utils


import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

class K8sClientTest {
    
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    K8sClient k8sClient = new K8sClient(commandExecutor)
    
    @Test
    void 'Creates secret'() {
        k8sClient.createSecret('generic', 'my-secret', 'my-ns', 
                new Tuple2('key1', 'value1'), new Tuple2('key2', 'value2'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic my-secret -n my-ns --from-literal=key1=value1 --from-literal=key2=value2" +
                        " --dry-run=client -oyaml | kubectl apply -f-")
    }
    
    @Test
    void 'Creates secret without namespace'() {
        k8sClient.createSecret('generic', 'my-secret', new Tuple2('key1', 'value1'))

        assertThat(commandExecutor.actualCommands[0]).isEqualTo(
                "kubectl create secret generic my-secret --from-literal=key1=value1 --dry-run=client -oyaml" +
                        " | kubectl apply -f-")
    }
    
    @Test
    void 'Creates secret no secret when literals are missing'() {
        shouldFail(RuntimeException) {
            k8sClient.createSecret('generic', 'my-secret')
        }
    }
}
