package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.*
import groovy.io.FileType
import groovy.yaml.YamlSlurper
import jakarta.inject.Provider
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CloneCommand
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class ArgoCDTest {
    Map config = [
            application: [
                    remote  : false,
                    insecure: false,
                    password: '123',
                    username: 'something',
                    namePrefix : '',
                    namePrefixForEnvVars : '',
            ],
            scmm       : [
                    internal: true,
                    protocol: 'https',
                    host: 'abc',
                    username: '',
                    password: ''
            ],
            images     : [
                    kubectl    : 'kubectl-value',
                    helm       : 'helm-value',
                    kubeval    : 'kubeval-value',
                    helmKubeval: 'helmKubeval-value',
                    yamllint   : 'yamllint-value'
            ],
            repositories : [
                    springBootHelmChart: [
                            url: 'https://github.com/cloudogu/spring-boot-helm-chart.git',
                            ref: '0.3.0'
                    ],
                    springPetclinic: [
                            url: 'https://github.com/cloudogu/spring-petclinic.git',
                            ref: '32c8653'
                    ],
                    gitopsBuildLib: [
                            url: "https://github.com/cloudogu/gitops-build-lib.git",
                    ],
                    cesBuildLib: [
                            url: 'https://github.com/cloudogu/ces-build-lib.git',
                    ]
            ],
            features   : [
                    argocd    : [
                            active: true,
                            configOnly: true,
                            emailFrom : 'argocd@example.org',
                            emailToUser : 'app-team@example.org',
                            emailToAdmin : 'infra@example.org'
                    ],
                    mail   : [
                            mailhog: true,
                            externalMailserver : '',
                            externalMailserverPort : '',
                            externalMailserverUser : '',
                            externalMailserverPassword : ''
                    ],
                    monitoring: [
                            active: true
                    ],
                    secrets   : [
                            active: true,
                            vault: [
                                    url: ''
                            ]
                    ],
                    exampleApps: [
                            petclinic: [
                                    baseDomain: ''
                            ],
                            nginx: [
                                    baseDomain: ''
                            ]
                    ]
            ]
    ]

    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    ScmmRepo argocdRepo
    String actualHelmValuesFile
    ScmmRepo clusterResourcesRepo
    ScmmRepo exampleAppsRepo
    ScmmRepo nginxHelmJenkinsRepo
    ScmmRepo nginxValidationRepo
    ScmmRepo brokenApplicationRepo
    File remotePetClinicRepoTmpDir
    List<ScmmRepo> petClinicRepos = []
    CloneCommand gitCloneMock = mock(CloneCommand.class, RETURNS_DEEP_STUBS)

    @Test
    void 'Installs argoCD'() {
        createArgoCD().install()
        
        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('NodePort')

        // check repoTemplateSecretName
        assertCommand(k8sCommands, 'kubectl create secret generic argocd-repo-creds-scmm -n argocd')
        assertCommand(k8sCommands, 'kubectl label secret argocd-repo-creds-scmm -n argocd')
        
        // Check dependency build and helm install
        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo('helm repo add argo https://argoproj.github.io/argo-helm')
        assertThat(helmCommands.actualCommands[1].trim()).isEqualTo(
                "helm dependency build ${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'argocd/')}".toString())
        assertThat(helmCommands.actualCommands[2].trim()).isEqualTo(
                "helm upgrade -i argocd ${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'argocd/')} --namespace argocd".toString())
        
        // Check patched PW
        def patchCommand = assertCommand(k8sCommands, 'kubectl patch secret argocd-secret -n argocd')
        String patchFile = (patchCommand =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
        assertThat(BCrypt.checkpw(config['application']['password'] as String,
                parseActualYaml(patchFile)['stringData']['admin.password'] as String))
                .as("Password hash missmatch").isTrue()
        
        // Check bootstrapping
        assertCommand(k8sCommands, "kubectl apply -f " +
                "${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml')}")
        assertCommand(k8sCommands, "kubectl apply -f " +
                "${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml')}")

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
        List filesWithInternalSCMM = findFilesContaining(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isEmpty()
        List filesWithExternalSCMM = findFilesContaining(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()

        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['server']['service']['type']).isEqualTo('LoadBalancer')
        assertThat(valuesYaml['argo-cd']['notifications']['argocdUrl']).isEqualTo( 'https://argo.cd')
        assertThat(valuesYaml['argo-cd']['server']['ingress']['enabled']).isEqualTo(true)
        assertThat((valuesYaml['argo-cd']['server']['ingress']['hosts'] as List)[0]).isEqualTo('argo.cd')
    }

    @Test
    void 'disables tls verification when using --insecure'() {
        config.application['insecure'] = true

        createArgoCD().install()


        def repositories = parseActualYaml(actualHelmValuesFile)['argo-cd']['configs']['repositories']

        for (def repo in ["argocd", "example-apps", "cluster-resources", "nginx-helm-jenkins", "nginx-helm-umbrella"]) {
            assertThat(repositories[repo]['insecure']).isEqualTo("true") // must be a string so that it can be passed to `|b64enc`
        }
    }

    @Test
    void 'When monitoring disabled: Does not push path monitoring to cluster resources'() {
        config.features['monitoring']['active'] = false
        createArgoCD().install()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/monitoring")).doesNotExist()
    }

    @Test
    void 'When monitoring enabled: Does push path monitoring to cluster resources'() {
        config.features['monitoring']['active'] = true
        createArgoCD().install()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/monitoring")).exists()
    }

    @Test
    void 'When mailhog disabled: Does not include mail configurations into cluster resources'() {
        config.features['mail']['mailhog'] = null
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['notifications']['enabled']).isEqualTo(false)
        assertThat(valuesYaml['argo-cd']['notifications']['notifiers']).isNull()
    }

    @Test
    void 'When mailhog enabled: Includes mail configurations into cluster resources'() {
        config.features['mail']['active'] = true
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['notifications']['enabled']).isEqualTo(true)
        assertThat(valuesYaml['argo-cd']['notifications']['notifiers']).isNotNull()
    }

    @Test
    void 'When emailaddress is set: Include given email addresses into configurations'() {
        config.features['mail']['active'] = true
        config.features['argocd']['emailFrom'] = 'argocd@example.com'
        config.features['argocd']['emailToUser'] = 'app-team@example.com'
        config.features['argocd']['emailToAdmin'] = 'argocd@example.com'
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        def argocdYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/argocd.yaml')
        def defaultYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/default.yaml')
        def exampleAppsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['from']).isEqualTo("argocd@example.com")
        assertThat(clusterRessourcesYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('argocd@example.com')
        assertThat(argocdYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.on-sync-status-unknown.email']).isEqualTo('argocd@example.com')
        assertThat(defaultYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('argocd@example.com')
        assertThat(exampleAppsYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('app-team@example.com')
    }

    @Test
    void 'When emailaddress is NOT set: Use default email addresses in configurations'() {
        config.features['mail']['active'] = true

        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        def argocdYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/argocd.yaml')
        def defaultYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/default.yaml')
        def exampleAppsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['from']).isEqualTo("argocd@example.org")
        assertThat(clusterRessourcesYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('infra@example.org')
        assertThat(argocdYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.on-sync-status-unknown.email']).isEqualTo('infra@example.org')
        assertThat(defaultYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('infra@example.org')
        assertThat(exampleAppsYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('app-team@example.org')
    }

    @Test
    void 'When external Mailserver is set'() {
        config.features['mail']['active'] = true
        config.features['mail']['externalMailserver'] = 'smtp.example.com'
        config.features['mail']['externalMailserverPort'] = '1010110'
        config.features['mail']['externalMailserverUser'] = 'argo@example.com'
        config.features['mail']['externalMailserverPassword'] = '1101ABCabc&/+*~'
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['host']).isEqualTo("smtp.example.com")
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['port']).isEqualTo(1010110)
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['username']).isEqualTo("argo@example.com")
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['password']).isEqualTo("1101ABCabc&/+*~")
    }

    @Test
    void 'When external Mailserver is NOT set'() {
        config.features['mail']['active'] = true
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['host'])doesNotHaveToString('mailhog.*monitoring.svc.cluster.local')
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['port']).isEqualTo(1025)
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String))doesNotHaveToString('username')
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String))doesNotHaveToString('password')
    }

    @Test
    void 'When vault enabled: Pushes external secret, and mounts into example app'() {
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir(), 'k8s/values-shared.yaml')
        
        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(2) 
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(2)
        
        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/staging/external-secret.yaml")).exists()
        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/production/external-secret.yaml")).exists()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/secrets")).exists()
    }

    @Test
    void 'When vault disabled: Does not push ExternalSecret and not mount into example app'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir(), 'k8s/values-shared.yaml')
        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(1)
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(1)
        assertThat((valuesYaml['extraVolumeMounts'] as List)[0]['name']).isEqualTo('index')
        assertThat((valuesYaml['extraVolumes'] as List)[0]['name']).isEqualTo('index')

        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/staging/external-secret.yaml")).doesNotExist()
        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/production/external-secret.yaml")).doesNotExist()
    }

    @Test
    void 'When vault disabled: Does not push path "secrets" to cluster resources'() {
        config.features['secrets']['active'] = false
        createArgoCD().install()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/secrets")).doesNotExist()
    }

    @Test
    void 'Pushes example repos for local'() {
        config.application['remote'] = false
        
        def setUriMock = mock(CloneCommand.class, RETURNS_DEEP_STUBS)
        def checkoutMock = mock(CheckoutCommand.class, RETURNS_DEEP_STUBS)
        when(gitCloneMock.setURI(anyString())).thenReturn(setUriMock)
        when(setUriMock.setDirectory(any(File.class)).call().checkout()).thenReturn(checkoutMock)
        
        createArgoCD().install()
        def valuesYaml = parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml')
        assertThat(valuesYaml['service']['type']).isEqualTo('NodePort')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')).doesNotContainKey('ingress')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')).doesNotContainKey('ingress')


        valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['service']['type']).isEqualTo('NodePort')
        assertThat(valuesYaml['nginx'] as Map).doesNotContainKey('ingress')

        // Assert Petclinic repo cloned
        verify(gitCloneMock).setURI('https://github.com/cloudogu/spring-petclinic.git')
        verify(setUriMock).setDirectory(remotePetClinicRepoTmpDir)
        verify(checkoutMock).setName('32c8653')

        assertPetClinicRepos('NodePort', 'LoadBalancer', '')
    }

    @Test
    void 'Pushes example repos for remote'() {
        config.application['remote'] = true
        config.features['exampleApps']['petclinic']['baseDomain'] = 'petclinic.local'
        config.features['exampleApps']['nginx']['baseDomain'] = 'nginx.local'
        createArgoCD().install()
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml').toString())
                .doesNotContain('NodePort')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production.nginx-helm.nginx.local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging.nginx-helm.nginx.local')

        assertThat(parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml').toString())
                .doesNotContain('NodePort')

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production.nginx-helm-umbrella.nginx.local')
        
        assertPetClinicRepos('LoadBalancer', 'NodePort', 'petclinic.local')
    }

    @Test
    void 'For internal SCMM: Use service address in gitops repos'() {
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        filesWithInternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
    }

    @Test
    void 'For external SCMM: Use external address in gitops repos'() {
        config.scmm['internal'] = false
        createArgoCD().install()
        List filesWithInternalSCMM = findFilesContaining(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isEmpty()
        filesWithInternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isEmpty()
        
        List filesWithExternalSCMM = findFilesContaining(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
        filesWithExternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
    }

    @Test
    void 'Pushes repos with empty name-prefix'() {
        createArgoCD().install()

        assertArgoCdYamlPrefixes(ArgoCD.SCMM_URL_INTERNAL, '')
        assertJenkinsEnvironmentVariablesPrefixes('')
    }

    @Test
    void 'Pushes repos with name-prefix'(){
        config.application['namePrefix'] = 'abc-'
        config.application['namePrefixForEnvVars'] = 'ABC_'
        createArgoCD().install()

        assertArgoCdYamlPrefixes(ArgoCD.SCMM_URL_INTERNAL, 'abc-')
        assertJenkinsEnvironmentVariablesPrefixes('ABC_')
    }

    @Test
    void 'configures custom nginx image'() {
        config.images['nginx'] = 'localhost:5000/nginx/nginx:latest'
        createArgoCD().install()

        def yaml = parseActualYaml(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir + '/k8s/values-shared.yaml')
        def image = yaml['image']
        assertThat(image['registry']).isEqualTo('localhost:5000')
        assertThat(image['repository']).isEqualTo('nginx/nginx')
        assertThat(image['tag']).isEqualTo('latest')

        yaml = parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')
        def deployment = yaml[1]
        assertThat(deployment['kind']).as("Did not correctly fetch deployment from broken-application.yaml").isEqualTo("Deployment(z)")
        assertThat((deployment['spec']['template']['spec']['containers'] as List)[0]['image']).isEqualTo('localhost:5000/nginx/nginx:latest')

        def yamlString = new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text
        assertThat(yamlString).startsWith("""image:
  registry: localhost:5000
  repository: nginx/nginx
  tag: latest
""")
    }

    private void assertArgoCdYamlPrefixes(String scmmUrl, String expectedPrefix) {
        assertAllYamlFiles(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), 'projects', 4) { Path file ->
            def yaml = parseActualYaml(file.toString())
            List<String> sourceRepos = yaml['spec']['sourceRepos'] as List<String>
            // Some projects might not have sourceRepos
            if (sourceRepos) {
                sourceRepos.each {
                    if (it.startsWith(scmmUrl)) {
                        assertThat(it)
                                .as("$file sourceRepos have name prefix")
                                .startsWith("${scmmUrl}/repo/${expectedPrefix}argocd")
                    }
                }
            }

            String metadataNamespace = yaml['metadata']['namespace'] as String
            if (metadataNamespace) {
                assertThat(metadataNamespace)
                        .as("$file metadata.namespace has name prefix")
                        .isEqualTo("${expectedPrefix}argocd".toString())
            }

            List<String> sourceNamespaces = yaml['spec']['sourceNamespaces'] as List<String>
            if (sourceNamespaces) {
                sourceNamespaces.each {
                    if (it != '*') {
                        assertThat(it)
                                .as("$file spec.sourceNamespace has name prefix")
                                .startsWith("${expectedPrefix}")
                    }
                }
            }
        }

        assertAllYamlFiles(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), 'applications', 5) { Path file ->
            def yaml = parseActualYaml(file.toString())
            assertThat(yaml['spec']['source']['repoURL'] as String)
                    .as("$file repoURL have name prefix")
                    .startsWith("${scmmUrl}/repo/${expectedPrefix}argocd")

            assertThat(yaml['metadata']['namespace'])
                    .as("$file metadata.namspace has name prefix")
                    .isEqualTo("${expectedPrefix}argocd".toString())

            assertThat(yaml['spec']['destination']['namespace'])
                    .as("$file spec.destination.namspace has name prefix")
                    .isEqualTo("${expectedPrefix}argocd".toString())
        }

        assertAllYamlFiles(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'argocd', 7) { Path it ->
            def yaml = parseActualYaml(it.toString())
            List yamlDocuments = yaml instanceof List ? yaml : [yaml]
            for (def document in yamlDocuments) {
                assertThat(document['spec']['source']['repoURL'] as String)
                        .as("$it repoURL have name prefix")
                        .startsWith("${scmmUrl}/repo/${expectedPrefix}argocd")
            }
        }

        assertAllYamlFiles(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), 'argocd', 1) { Path it ->
            def yaml = parseActualYaml(it.toString())

            assertThat(yaml['spec']['source']['repoURL'] as String)
                    .as("$it repoURL has name prefix")
                    .startsWith("${scmmUrl}/repo/${expectedPrefix}argocd")


            def metadataNamespace = yaml['metadata']['namespace'] as String
            if (metadataNamespace) {
                assertThat(metadataNamespace)
                        .as("$it metadata.namespace has name prefix")
                        .startsWith("${expectedPrefix}")
            }

            def destinationNamespace = yaml['spec']['destination']['namespace']
            if (destinationNamespace) {
                assertThat(destinationNamespace as String)
                        .as("$it spec.destination.namespace has name prefix")
                        .startsWith("${expectedPrefix}")
            }
        }

        assertAllYamlFiles(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), 'misc', 6) { Path it ->
            def yaml = parseActualYaml(it.toString())
            List yamlDocuments = yaml instanceof List ? yaml : [yaml]
            for (def document in yamlDocuments) {
                def metadataNamespace = document['metadata']['namespace'] as String
                assertThat(metadataNamespace)
                        .as("$it metadata.namespace has name prefix")
                        .startsWith("${expectedPrefix}")
            }
        }
    }

    private static void assertAllYamlFiles(File rootDir, String childDir, Integer numberOfFiles, Closure cl) {
        def nFiles = Files.walk(Path.of(rootDir.absolutePath, childDir))
                .filter { it.toString() ==~ /.*\.yaml/ }
                .collect(Collectors.toList())
                .each(cl)
                .size()
        assertThat(nFiles).isEqualTo(numberOfFiles)
    }

    private void assertJenkinsEnvironmentVariablesPrefixes(String prefix) {
        assertThat(new File(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains("env.${prefix}K8S_VERSION")
        for (def petclinicRepo : petClinicRepos) {
            assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains("env.${prefix}REGISTRY_URL")
            assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains("env.${prefix}REGISTRY_PATH")
        }
    }

    private static List findFilesContaining(File folder, String stringToSearch) {
        List result = []
        folder.eachFileRecurse(FileType.FILES) {
            if (it.text.contains(stringToSearch)) {
                result += it
            }
        }
        return result
    }
    
    ArgoCD createArgoCD() {
        def fileSystemUtils = new FileSystemUtils()
        def argoCD = new ArgoCDForTest(
                new Configuration(config),
                new K8sClient(k8sCommands, fileSystemUtils, new Provider<Configuration>() {
                    @Override
                    Configuration get() {
                        new Configuration(config)
                    }
                }),
                new HelmClient(helmCommands),
                fileSystemUtils
        )
        argocdRepo = argoCD.argocdRepoInitializationAction.repo
        actualHelmValuesFile = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.HELM_VALUES_PATH)
        clusterResourcesRepo = argoCD.clusterResourcesInitializationAction.repo
        exampleAppsRepo = argoCD.exampleAppsInitializationAction.repo
        nginxHelmJenkinsRepo = argoCD.nginxHelmJenkinsInitializationAction.repo
        nginxValidationRepo = argoCD.nginxValidationInitializationAction.repo
        brokenApplicationRepo = argoCD.brokenApplicationInitializationAction.repo
        remotePetClinicRepoTmpDir = argoCD.remotePetClinicRepoTmpDir
        petClinicRepos = argoCD.petClinicInitializationActions.collect {  it.repo }
        return argoCD
    }

    private static String assertCommand(CommandExecutorForTest commands, String commandStartsWith) {
        def createSecretCommand = commands.actualCommands.find {
            it.startsWith(commandStartsWith)
        }
        assertThat(createSecretCommand).as("Expected command to have been executed, but was not: ${commandStartsWith}")
                .isNotNull()
        return createSecretCommand
    }

    void assertBuildImagesInJenkinsfileReplaced(File jenkinsfile) {
        def actualBuildImages = ''
        def insideBuildImagesBlock = false
        
        jenkinsfile.eachLine { line ->
            if (line =~ /\s*buildImages\s*:\s*\[/) {
                insideBuildImagesBlock = true
                return
            }

            if (insideBuildImagesBlock) {
                actualBuildImages += "${line.trim()}\n" 

                if (line =~ /]/) {
                    insideBuildImagesBlock = false
                }
            }
        }
        if (!actualBuildImages) {
            fail("Missing build images in Jenkinsfile ${jenkinsfile}")
        }

        for (Map.Entry image : config.images as Map) {
            assertThat(actualBuildImages).contains("${image.key}: '${image.value}'")
        }
    }

    void assertPetClinicRepos(String expectedServiceType, String unexpectedServiceType, String ingressUrl) {
        for (ScmmRepo repo : petClinicRepos) {

            def tmpDir = repo.absoluteLocalRepoTmpDir
            def jenkinsfile = new File(tmpDir, 'Jenkinsfile')
            assertThat(jenkinsfile).exists()

            if (repo.scmmRepoTarget == 'argocd/petclinic-plain') {
                assertBuildImagesInJenkinsfileReplaced(jenkinsfile)
                assertThat(new File(tmpDir, 'k8s/production/service.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/staging/service.yaml').text).contains("type: ${expectedServiceType}")
                
                assertThat(new File(tmpDir, 'k8s/production/service.yaml').text).doesNotContain("type: ${unexpectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/staging/service.yaml').text).doesNotContain("type: ${unexpectedServiceType}")
                if (!ingressUrl) {
                    assertThat(new File(tmpDir, 'k8s/staging/ingress.yaml')).doesNotExist()
                    assertThat(new File(tmpDir, 'k8s/production/ingress.yaml')).doesNotExist()
                } else {
                    assertThat((parseActualYaml(tmpDir + '/k8s/staging/ingress.yaml')['spec']['rules'] as List)[0]['host'] as String).isEqualTo("staging.petclinic-plain.$ingressUrl".toString())
                    assertThat((parseActualYaml(tmpDir + '/k8s/production/ingress.yaml')['spec']['rules'] as List)[0]['host'] as String).isEqualTo("production.petclinic-plain.$ingressUrl".toString())
                }
            } else if (repo.scmmRepoTarget == 'argocd/petclinic-helm') {
                assertBuildImagesInJenkinsfileReplaced(jenkinsfile)
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).doesNotContain("type: ${unexpectedServiceType}")
                if (!ingressUrl) {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['ingress']['enabled']).isEqualTo(false)
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-production.yaml')).doesNotContainKey('ingress')
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-staging.yaml')).doesNotContainKey('ingress')
                } else {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['ingress']['enabled']).isEqualTo(true)
                    assertThat((parseActualYaml(tmpDir + '/k8s/values-production.yaml')['ingress']['hosts'] as List)[0]['host'] as String).isEqualTo("production.petclinic-helm.$ingressUrl".toString())
                    assertThat((parseActualYaml(tmpDir + '/k8s/values-staging.yaml')['ingress']['hosts'] as List)[0]['host'] as String).isEqualTo("staging.petclinic-helm.$ingressUrl".toString())
                }
            } else if (repo.scmmRepoTarget == 'exercises/petclinic-helm') {
                // Does not contain the gitops build lib call, so no build images to replace
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).doesNotContain("type: ${unexpectedServiceType}")
                if (!ingressUrl) {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['ingress']['enabled']).isEqualTo(false)
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-production.yaml')).doesNotContainKey('ingress')
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-staging.yaml')).doesNotContainKey('ingress')
                } else {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['ingress']['enabled']).isEqualTo(true)
                    assertThat((parseActualYaml(tmpDir + '/k8s/values-production.yaml')['ingress']['hosts'] as List)[0]['host'] as String).isEqualTo("production.exercise-petclinic-helm.$ingressUrl".toString())
                    assertThat((parseActualYaml(tmpDir + '/k8s/values-staging.yaml')['ingress']['hosts'] as List)[0]['host'] as String).isEqualTo("staging.exercise-petclinic-helm.$ingressUrl".toString())
                }
            } else {
                fail("Unkown petclinic repo: $repo")
            }
        }
    }

    class ArgoCDForTest extends ArgoCD {
        ArgoCDForTest(Configuration config, K8sClient k8sClient, HelmClient helmClient, FileSystemUtils fileSystemUtils) {
            super(config, k8sClient, helmClient, fileSystemUtils, new TestScmmRepoProvider(config, fileSystemUtils))
        }

        @Override
        protected CloneCommand gitClone() {
            return gitCloneMock
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
