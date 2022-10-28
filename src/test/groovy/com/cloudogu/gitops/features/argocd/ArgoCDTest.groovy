package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.GitClient
import com.cloudogu.gitops.utils.K8sClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito

import static org.assertj.core.api.Assertions.assertThat 

class ArgoCDTest {

    Map config = [
            features: [
                    argocd : [
                            active: true
                    ],
                    monitoring : [
                            active: true
                    ],
                    secrets: [
                            active: true
                    ]
            ]
    ]

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    K8sClient k8sClient = new K8sClient(commandExecutor)
    private File controlAppTmpDir
    
    @Test
    void 'When vault enabled: Delete secretStores'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        assertThat(new File(controlAppTmpDir.absolutePath + '/' + "applications/secrets")).doesNotExist()
    }

    @Test
    void 'When vault enabled: Creates secrets for secretStores  '() {
        createArgoCD().install()
        assertThat(commandExecutor.actualCommands).contains(
                'kubectl create secret generic vault-token -n argocd-staging --from-literal=VAULT_TOKEN=root',
                'kubectl create secret generic vault-token -n argocd-production --from-literal=VAULT_TOKEN=root'
        )
    }

    private ArgoCD createArgoCD() {
        GitClient client = Mockito.mock(GitClient)
        ArgoCD argoCD = new ArgoCD(config, client, k8sClient)
        controlAppTmpDir = argoCD.controlAppTmpDir
        return argoCD
    }
}
