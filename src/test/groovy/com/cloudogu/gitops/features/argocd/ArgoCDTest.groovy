package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.GitClient
import com.cloudogu.gitops.utils.K8sClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.mockito.Mockito

import static org.assertj.core.api.Assertions.assertThat 

class ArgoCDTest {

    Map config = [
            application: [
                    remote  : false,
                    username: 'something'
            ],
            scmm       : [
                    internal: true,
            ],
            features   : [
                    argocd    : [
                            active: true
                    ],
                    monitoring: [
                            active: true
                    ],
                    secrets   : [
                            active: true
                    ]
            ]
    ]

    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    CommandExecutorForTest gitCommands = new CommandExecutorForTest()
    File controlAppTmpDir
    
    @Test
    void 'Pushes controlApp'() {
        
    }

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
    
    @Test
    void 'For internal SCMM: Use service address in control-app repo'() {
        "http://scmm-scm-manager.default.svc.cluster.local/scm"
    }
    
    @Test
    void 'For internal SCMM: Use external address in control-app repo'() {
    }

    private ArgoCD createArgoCD() {
        def argoCD = new ArgoCDForTest(config)
        controlAppTmpDir = argoCD.controlAppTmpDir
        return argoCD
    }
    
    class ArgoCDForTest extends ArgoCD {

        ArgoCDForTest(Map config) {
            super(config)
            this.k8sClient = new K8sClient(k8sCommands)
        }
        
        @Override
        protected GitClient createRepo(String localSrcDir, String scmmRepoTarget, File absoluteLocalRepoTmpDir) {
            // Actually copy files so we can assert but don't execute git clone, push, etc.
            Map gitConfig = [scmm: [protocol: 'https', host: 'abc', username: '', password: '', internal: true]]
            
            GitClient repo = new GitClient(gitConfig, localSrcDir, scmmRepoTarget, absoluteLocalRepoTmpDir.absolutePath) {
                @Override
                protected Request.Builder createRequest() {
                    return Mockito.mock(Request.Builder, Mockito.RETURNS_DEEP_STUBS)
                }

                @Override
                protected OkHttpClient newHttpClient() {
                    return Mockito.mock(OkHttpClient.class, Mockito.RETURNS_DEEP_STUBS)
                }
            }
            repo.commandExecutor = gitCommands
            // We could add absoluteLocalRepoTmpDir here for assertion later 
            return repo
        }
    }
}
