package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.ScmmRepo
import groovy.io.FileType
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class ArgoCDTest {
    Map config = [
            application: [
                    remote  : false,
                    username: 'something'
            ],
            scmm       : [
                    internal: true,
                    protocol: 'https',
                    host: 'abc',
                    username: '',
                    password: ''
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
    File nginxHelmJenkinsTmpDir

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
    void 'When vault enabled: Pushes external secret, and mounts into example app'() {
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsTmpDir.absolutePath, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH)
        
        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(2) 
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(2)
        
        assertThat(new File(nginxHelmJenkinsTmpDir.absolutePath + "/k8s/staging/external-secret.yaml")).exists()
        assertThat(new File(nginxHelmJenkinsTmpDir.absolutePath + "/k8s/production/external-secret.yaml")).exists()
    }

    @Test
    void 'When vault disabled: Does not push ExternalSecret and not mount into example app'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsTmpDir.absolutePath, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH)
        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(1)
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(1)
        assertThat((valuesYaml['extraVolumeMounts'] as List)[0]['name']).isEqualTo('index')
        assertThat((valuesYaml['extraVolumes'] as List)[0]['name']).isEqualTo('index')

        assertThat(new File(nginxHelmJenkinsTmpDir.absolutePath + "/k8s/staging/external-secret.yaml")).doesNotExist()
        assertThat(new File(nginxHelmJenkinsTmpDir.absolutePath + "/k8s/production/external-secret.yaml")).doesNotExist()
    }

    @Test
    void 'When vault disabled: Does not push path "secrets" to control app'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        assertThat(new File(controlAppTmpDir.absolutePath + "/secrets")).doesNotExist()
    }

    @Test
    void 'Pushes example repo nginx-helm-jenkins for local'() {
        config.application['remote'] = false
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsTmpDir.absolutePath, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH)
        assertThat(valuesYaml['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void 'Pushes example repo nginx-helm-jenkins for remote'() {
        config.application['remote'] = true
        createArgoCD().install()
        Map valuesYaml = (new YamlSlurper().parse(Path.of nginxHelmJenkinsTmpDir.absolutePath, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH)) as Map
        assertThat(valuesYaml.toString()).doesNotContain('NodePort')
    }

    @Test
    void 'For internal SCMM: Use service address in control-app repo'() {
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining( "http://scmm-scm-manager.default.svc.cluster.local/scm")
        assertThat(filesWithInternalSCMM).isNotEmpty()
    }

    @Test
    void 'For external SCMM: Use external address in control-app repo'() {
        config.scmm['internal'] = false
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining( "http://scmm-scm-manager.default.svc.cluster.local/scm")
        assertThat(filesWithInternalSCMM).isEmpty()
        List filesWithExternalSCMM = findFilesContaining( "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
    }


    List findFilesContaining(String stringToSearch) {
        List result = []
        controlAppTmpDir.eachFileRecurse(FileType.FILES) {
            if (it.text.contains(stringToSearch)) {
                result += it
            }
        }
        return result
    }
    
    ArgoCD createArgoCD() {
        def argoCD = new ArgoCDForTest(config)
        controlAppTmpDir = argoCD.controlAppTmpDir
        nginxHelmJenkinsTmpDir = argoCD.nginxHelmJenkinsTmpDir
        return argoCD
    }

    class ArgoCDForTest extends ArgoCD {

        ArgoCDForTest(Map config) {
            super(config)
            this.k8sClient = new K8sClient(k8sCommands)
        }

        @Override
        protected ScmmRepo createRepo(String localSrcDir, String scmmRepoTarget, File absoluteLocalRepoTmpDir) {
            // Actually copy files so we can assert but don't execute git clone, push, etc.
            ScmmRepo repo = new ScmmRepo(config, localSrcDir, scmmRepoTarget, absoluteLocalRepoTmpDir.absolutePath)
            repo.commandExecutor = gitCommands
            // We could add absoluteLocalRepoTmpDir here for assertion later 
            return repo
        }
    }
}
