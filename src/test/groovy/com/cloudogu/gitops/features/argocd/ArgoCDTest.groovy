package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.ScmmRepo
import groovy.io.FileType
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class ArgoCDTest {
    Map config = [
            application: [
                    remote  : false,
                    password: '123',
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
                            active: true,
                            configOnly: true
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
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    CommandExecutorForTest gitCommands = new CommandExecutorForTest()
    File argocdRepoTmpDir
    String actualHelmValuesFile
    File clusterResourcesTmpDir
    File exampleAppsTmpDir
    File nginxHelmJenkinsTmpDir

    @Test
    void 'Installs argoCD'() {
        createArgoCD().install()
        
        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(argocdRepoTmpDir, ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('NodePort')

        // check repoTemplateSecretName
        assertCommand(k8sCommands, 'kubectl create secret generic argocd-repo-creds-scmm -n argocd')
        assertCommand(k8sCommands, 'kubectl label secret argocd-repo-creds-scmm -n argocd')
        
        // Check dependency build and helm install
        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo('helm repo add argo https://argoproj.github.io/argo-helm')
        assertThat(helmCommands.actualCommands[1].trim()).isEqualTo(
                "helm dependency build ${Path.of(argocdRepoTmpDir.absolutePath, 'argocd/')}".toString())
        assertThat(helmCommands.actualCommands[2].trim()).isEqualTo(
                "helm upgrade -i argocd ${Path.of(argocdRepoTmpDir.absolutePath, 'argocd/')} --namespace argocd".toString())
        
        // Check patched PW
        def patchCommand = assertCommand(k8sCommands, 'kubectl patch secret argocd-secret -n argocd')
        String patchFile = (patchCommand =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
        assertThat(BCrypt.checkpw(config['application']['password'] as String,
                parseActualYaml(patchFile)['stringData']['admin.password'] as String))
                .as("Password hash missmatch").isTrue()
        
        // Check bootstrapping
        assertCommand(k8sCommands, "kubectl apply -f " +
                "${Path.of(argocdRepoTmpDir.absolutePath, 'projects/argocd.yaml')}")
        assertCommand(k8sCommands, "kubectl apply -f " +
                "${Path.of(argocdRepoTmpDir.absolutePath, 'applications/bootstrap.yaml')}")

        def deleteCommand = assertCommand(k8sCommands, 'kubectl delete secret -n argocd')
        assertThat(deleteCommand).contains('owner=helm', 'name=argocd')

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('NodePort')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['argocdUrl'])
                .isEqualTo( 'https://localhost:9092')
    }

    @Test
    void 'Installs argoCD for remote and external Scmm'() {
        config.application['remote'] = true
        config.scmm['internal'] = false
        config.features['argocd']['url'] = 'https://argo.cd'
        
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining(argocdRepoTmpDir, ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isEmpty()
        List filesWithExternalSCMM = findFilesContaining(argocdRepoTmpDir, "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('LoadBalancer')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['argocdUrl'])
                .isEqualTo( 'https://argo.cd')
    }

    @Test
    void 'When monitoring disabled: Does not push path monitoring to cluster resources'() {
        config.features['monitoring']['active'] = false
        createArgoCD().install()
        assertThat(new File(clusterResourcesTmpDir.absolutePath + "/monitoring")).doesNotExist()
    }

    @Test
    void 'When monitoring enabled: Does push path monitoring to cluster resources'() {
        config.features['monitoring']['active'] = true
        createArgoCD().install()
        assertThat(new File(clusterResourcesTmpDir.absolutePath + "/misc/monitoring")).exists()
    }

    @Test
    void 'When vault enabled: Pushes external secret, and mounts into example app'() {
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsTmpDir.absolutePath, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH)
        
        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(2) 
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(2)
        
        assertThat(new File(nginxHelmJenkinsTmpDir.absolutePath + "/k8s/staging/external-secret.yaml")).exists()
        assertThat(new File(nginxHelmJenkinsTmpDir.absolutePath + "/k8s/production/external-secret.yaml")).exists()
        assertThat(new File(clusterResourcesTmpDir.absolutePath + "/misc/secrets")).exists()
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
    void 'When vault disabled: Does not push path "secrets" to cluster resources'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        assertThat(new File(clusterResourcesTmpDir.absolutePath + "/misc/secrets")).doesNotExist()
    }

    @Test
    void 'Pushes example repos for local'() {
        config.application['remote'] = false
        createArgoCD().install()
        def valuesYaml = parseActualYaml(nginxHelmJenkinsTmpDir, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH)
        assertThat(valuesYaml['service']['type']).isEqualTo('NodePort')
        
        valuesYaml = parseActualYaml(exampleAppsTmpDir, ArgoCD.NGINX_HELM_DEPENDENCY_VALUES_PATH)
        assertThat(valuesYaml['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void 'Pushes example repos for remote'() {
        config.application['remote'] = true
        createArgoCD().install()
        assertThat(parseActualYaml(nginxHelmJenkinsTmpDir, ArgoCD.NGINX_HELM_JENKINS_VALUES_PATH).toString())
                .doesNotContain('NodePort')
        assertThat(parseActualYaml(exampleAppsTmpDir, ArgoCD.NGINX_HELM_DEPENDENCY_VALUES_PATH).toString())
                .doesNotContain('NodePort')
    }

    @Test
    void 'For internal SCMM: Use service address in gitops repos'() {
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining(clusterResourcesTmpDir, ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        filesWithInternalSCMM = findFilesContaining(exampleAppsTmpDir, ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
    }

    @Test
    void 'For external SCMM: Use external address in gitops repos'() {
        config.scmm['internal'] = false
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining(clusterResourcesTmpDir, ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isEmpty()
        filesWithInternalSCMM = findFilesContaining(exampleAppsTmpDir, ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isEmpty()
        
        List filesWithExternalSCMM = findFilesContaining(clusterResourcesTmpDir, "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
        filesWithExternalSCMM = findFilesContaining(exampleAppsTmpDir, "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
    }

    List findFilesContaining(File folder, String stringToSearch) {
        List result = []
        folder.eachFileRecurse(FileType.FILES) {
            if (it.text.contains(stringToSearch)) {
                result += it
            }
        }
        return result
    }
    
    ArgoCD createArgoCD() {
        def argoCD = new ArgoCDForTest(config)
        argocdRepoTmpDir = argoCD.argocdRepoTmpDir
        actualHelmValuesFile = Path.of(argocdRepoTmpDir.absolutePath, ArgoCD.HELM_VALUES_PATH)

        clusterResourcesTmpDir = argoCD.clusterResourcesTmpDir
        exampleAppsTmpDir = argoCD.exampleAppsTmpDir
        nginxHelmJenkinsTmpDir = argoCD.nginxHelmJenkinsTmpDir
        return argoCD
    }

    private String assertCommand(CommandExecutorForTest commands, String commandStartsWith) {
        def createSecretCommand = commands.actualCommands.find {
            it.startsWith(commandStartsWith)
        }
        assertThat(createSecretCommand).as("Expected command to have been executed, but was not: ${commandStartsWith}")
                .isNotNull()
        return createSecretCommand
    }

    class ArgoCDForTest extends ArgoCD {

        ArgoCDForTest(Map config) {
            super(config)
            this.k8sClient = new K8sClient(k8sCommands)
            this.helmClient = new HelmClient(helmCommands)
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

    private Map parseActualYaml(File folder, String file) {
        return parseActualYaml(Path.of(folder.absolutePath, file).toString())
    }
    
    private Map parseActualYaml(String pathToYamlFile) {
        File yamlFile = new File(pathToYamlFile)
        def ys = new YamlSlurper()
        return ys.parse(yamlFile) as Map
    }
}
