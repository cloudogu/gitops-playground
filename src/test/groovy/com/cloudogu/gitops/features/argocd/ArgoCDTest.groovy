package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.GitClient
import com.cloudogu.gitops.utils.K8sClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import static org.mockito.Mockito.mock
import org.mockito.Mockito

import static org.assertj.core.api.Assertions.assertThat 

class ArgoCDTest {

    Map config = [
            application: [ 
                    remote: false,
                    username: 'something'
            ],
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

    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    CommandExecutorForTest gitCommands = new CommandExecutorForTest()
    K8sClient k8sClient = new K8sClient(k8sCommands)
    File controlAppTmpDir
    
    @Test
    void 'Pushes controlApp'() {}
    
    @Test
    void 'When monitoring disabled: Does not push path monitoring to control app'() {
        config.features['monitoring']['active'] = false
        createArgoCD().install()
        assertThat(new File(controlAppTmpDir.absolutePath + "/monitoring")).doesNotExist()
    }
    
    @Test
    void 'When monitoring enabled: Does not push path monitoring to control app'() {
        config.features['monitoring']['active'] = true
        createArgoCD().install()
        assertThat(new File(controlAppTmpDir.absolutePath + "/monitoring")).exists()
    }
    
    @Test
    void 'When vault disabled: Does not push path secrets to control app'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        assertThat(new File(controlAppTmpDir.absolutePath + "/secrets")).doesNotExist()
    }

    @Test
    void 'When vault enabled: Creates secrets for secretStores'() {
        createArgoCD().install()
        assertThat(k8sCommands.actualCommands).contains(
                'kubectl create secret generic vault-token -n argocd-staging --from-literal=token=something --dry-run=client -oyaml | kubectl apply -f-',
                'kubectl create secret generic vault-token -n argocd-production --from-literal=token=something --dry-run=client -oyaml | kubectl apply -f-'
        )
        assertThat(new File(controlAppTmpDir.absolutePath + "/secrets")).exists()
    }

    @Test
    void 'When vault enabled: Injects secret into example app'() {
    }
    
    @Test
    void 'When vault disabled: Does not deploy ExternalSecret'() {
    }
    
    @Test
    void 'Pushes example repo nginx-helm-jenkins for local'() {
    }
    
    @Test
    void 'Pushes example repo nginx-helm-jenkins for remote'() {
    }
    
    private ArgoCD createArgoCD() {
        // Actually copy files so we can assert but don't execute git clone, push, etc.
        Map gitConfig = [scmm: [ protocol: 'https', host: 'abc', username: '', password: '', internal: true]]
        GitClient client = new GitClient(gitConfig, new FileSystemUtils(), 
                gitCommands) {
            @Override
            protected Request.Builder createRequest() {
                return mock(Request.Builder, Mockito.RETURNS_DEEP_STUBS)
            }

            @Override
            protected OkHttpClient newHttpClient() {
                return mock(OkHttpClient.class, Mockito.RETURNS_DEEP_STUBS)
            }
        }
        
        ArgoCD argoCD = new ArgoCD(config, client, new FileSystemUtils(), k8sClient)
        controlAppTmpDir = argoCD.controlAppTmpDir
        return argoCD
    }
}
