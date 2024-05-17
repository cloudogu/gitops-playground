package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClientForTest
import com.cloudogu.gitops.utils.TestScmmRepoProvider
import groovy.io.FileType
import groovy.yaml.YamlSlurper
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
    Map buildImages = [
            kubectl    : 'kubectl-value',
            helm       : 'helm-value',
            kubeval    : 'kubeval-value',
            helmKubeval: 'helmKubeval-value',
            yamllint   : 'yamllint-value'
    ]
    Map config = [
            application : [
                    remote              : false,
                    insecure            : false,
                    password            : '123',
                    username            : 'something',
                    namePrefix          : '',
                    namePrefixForEnvVars: '',
                    gitName             : 'Cloudogu',
                    gitEmail            : 'hello@cloudogu.com',
                    urlSeparatorHyphen  : false,
                    mirrorRepos         : false
            ],
            jenkins     : [
                    mavenCentralMirror: '',
            ],
            scmm        : [
                    internal: true,
                    protocol: 'https',
                    host    : 'abc',
                    username: '',
                    password: ''
            ],
            images      : buildImages + [ petclinic  : 'petclinic-value' ],
            registry: [
                    twoRegistries: false,
            ],
            repositories: [
                    springBootHelmChart: [
                            url: 'https://github.com/cloudogu/spring-boot-helm-chart.git',
                            ref: '0.3.0'
                    ],
                    springPetclinic    : [
                            url: 'https://github.com/cloudogu/spring-petclinic.git',
                            ref: '32c8653'
                    ],
                    gitopsBuildLib     : [
                            url: "https://github.com/cloudogu/gitops-build-lib.git",
                    ],
                    cesBuildLib        : [
                            url: 'https://github.com/cloudogu/ces-build-lib.git',
                    ]
            ],
            features    : [
                    argocd     : [
                            active      : true,
                            configOnly  : true,
                            emailFrom   : 'argocd@example.org',
                            emailToUser : 'app-team@example.org',
                            emailToAdmin: 'infra@example.org'
                    ],
                    mail       : [
                            mailhog     : true,
                            smtpAddress : '',
                            smtpPort    : '',
                            smtpUser    : '',
                            smtpPassword: ''
                    ],
                    monitoring : [
                            active: true,
                            helm  : [
                                    chart: 'kube-prometheus-stack',
                                    version: '42.0.3'
                                    ]
                    ],
                    secrets    : [
                            active: true,
                            vault : [
                                    url: ''
                            ]
                    ],
                    exampleApps: [
                            petclinic: [
                                    baseDomain: ''
                            ],
                            nginx    : [
                                    baseDomain: ''
                            ]
                    ]
            ]
    ]

    CommandExecutorForTest k8sCommands
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

        k8sCommands.assertExecuted('kubectl create namespace argocd')

        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), ArgoCD.SCMM_URL_INTERNAL)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('NodePort')

        // check repoTemplateSecretName
        k8sCommands.assertExecuted('kubectl create secret generic argocd-repo-creds-scmm -n argocd')
        k8sCommands.assertExecuted('kubectl label secret argocd-repo-creds-scmm -n argocd')

        // Check dependency build and helm install
        assertThat(helmCommands.actualCommands[0].trim()).isEqualTo('helm repo add argo https://argoproj.github.io/argo-helm')
        assertThat(helmCommands.actualCommands[1].trim()).isEqualTo(
                "helm dependency build ${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'argocd/')}".toString())
        assertThat(helmCommands.actualCommands[2].trim()).isEqualTo(
                "helm upgrade -i argocd ${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'argocd/')} --create-namespace --namespace argocd".toString())

        // Check patched PW
        def patchCommand = k8sCommands.assertExecuted('kubectl patch secret argocd-secret -n argocd')
        String patchFile = (patchCommand =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
        assertThat(BCrypt.checkpw(config['application']['password'] as String,
                parseActualYaml(patchFile)['stringData']['admin.password'] as String))
                .as("Password hash missmatch").isTrue()

        // Check bootstrapping
        k8sCommands.assertExecuted("kubectl apply -f " +
                "${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml')}")
        k8sCommands.assertExecuted("kubectl apply -f " +
                "${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml')}")

        def deleteCommand = k8sCommands.assertExecuted('kubectl delete secret -n argocd')
        assertThat(deleteCommand).contains('owner=helm', 'name=argocd')

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('NodePort')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['argocdUrl'])
                .isEqualTo('https://localhost:9092')


        def namespacesYaml = clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/namespaces.yaml"
        assertThat(new File(namespacesYaml)).exists()
        def yaml = parseActualYaml(namespacesYaml)
        assertThat(yaml[0]['metadata']['name']).isEqualTo('example-apps-staging')
        assertThat(yaml[1]['metadata']['name']).isEqualTo('example-apps-production')

        Map repos = parseActualYaml(actualHelmValuesFile)['argo-cd']['configs']['repositories'] as Map
        assertThat(repos['prometheus']['url']).isEqualTo('https://prometheus-community.github.io/helm-charts')

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'https://prometheus-community.github.io/helm-charts')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack')
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
        assertThat(valuesYaml['argo-cd']['notifications']['argocdUrl']).isEqualTo('https://argo.cd')
        assertThat(valuesYaml['argo-cd']['server']['ingress']['enabled']).isEqualTo(true)
        assertThat(valuesYaml['argo-cd']['server']['ingress']['hostname']).isEqualTo('argo.cd')
    }

    @Test
    void 'disables tls verification when using --insecure'() {
        config.application['insecure'] = true

        createArgoCD().install()


        def repositories = parseActualYaml(actualHelmValuesFile)['argo-cd']['configs']['repositories']

        for (def repo in ["argocd", "example-apps", "cluster-resources", "nginx-helm-jenkins", "nginx-helm-umbrella"]) {
            assertThat(repositories[repo]['insecure']).isEqualTo("true")
            // must be a string so that it can be passed to `|b64enc`
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
        config.features['mail']['smtpAddress'] = 'smtp.example.com'
        config.features['mail']['smtpPort'] = '1010110'
        config.features['mail']['smtpUser'] = 'argo@example.com'
        config.features['mail']['smtpPassword'] = '1101:ABCabc&/+*~'

        createArgoCD().install()
        def serviceEmail = new YamlSlurper().parseText(
                parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['notifiers']['service.email'] as String)

        assertThat(serviceEmail['host']).isEqualTo(config.features['mail']['smtpAddress'])
        assertThat(serviceEmail['port'] as String).isEqualTo(config.features['mail']['smtpPort'])
        // username and password are both linked to the k8s secret. Secrets will be created at runtime, in this test
        assertThat(serviceEmail['username']).isEqualTo('$email-username')
        assertThat(serviceEmail['password']).isEqualTo('$email-password')

        def createMailSecretCommand = k8sCommands.assertExecuted('kubectl create secret generic argocd-notifications-secret -n argocd')
        assertThat(createMailSecretCommand).contains('email-username', config.features['mail']['smtpUser'] as CharSequence)
        assertThat(createMailSecretCommand).contains('email-password', config.features['mail']['smtpPassword'] as CharSequence)
    }

    @Test
    void 'When external emailservers username is set, check if kubernetes secret will be created'() {
        config.features['mail']['active'] = true
        config.features['mail']['smtpAddress'] = 'smtp.example.com'
        config.features['mail']['smtpUser'] = 'argo@example.com'

        createArgoCD().install()

        def createMailSecretCommand = k8sCommands.assertExecuted('kubectl create secret generic argocd-notifications-secret -n argocd')
        assertThat(createMailSecretCommand).contains('email-username', config.features['mail']['smtpUser'] as CharSequence)
    }

    @Test
    void 'When external emailservers password is set, check if kubernetes secret will be created'() {
        config.features['mail']['active'] = true
        config.features['mail']['smtpAddress'] = 'smtp.example.com'
        config.features['mail']['smtpPassword'] = '1101:ABCabc&/+*~'

        createArgoCD().install()

        def createMailSecretCommand = k8sCommands.assertExecuted('kubectl create secret generic argocd-notifications-secret -n argocd')
        assertThat(createMailSecretCommand).contains('email-password', config.features['mail']['smtpPassword'] as CharSequence)
    }


    @Test
    void 'When external Mailserver is set without port, user, password'() {
        config.features['mail']['active'] = true
        config.features['mail']['smtpAddress'] = 'smtp.example.com'

        createArgoCD().install()
        def serviceEmail = new YamlSlurper().parseText(
                parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['notifiers']['service.email'] as String)

        k8sCommands.assertNotExecuted('kubectl create secret generic argocd-notifications-secret')
        
        assertThat(serviceEmail['host']).isEqualTo("smtp.example.com")
        assertThat(serviceEmail as Map).doesNotContainKey('port')
        assertThat(serviceEmail as Map).doesNotContainKey('username')
        assertThat(serviceEmail as Map).doesNotContainKey('password')
    }

    @Test
    void 'When external Mailserver is NOT set'() {
        config.features['mail']['active'] = true
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['host']) doesNotHaveToString('mailhog.*monitoring.svc.cluster.local')
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['port']).isEqualTo(1025)
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)) doesNotHaveToString('username')
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)) doesNotHaveToString('password')
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
    void 'Prepares repos for air-gapped mode'() {
        config['features']['monitoring']['active'] = false
        config.application['mirrorRepos'] = true

        createArgoCD().install()

        Map repos = parseActualYaml(actualHelmValuesFile)['argo-cd']['configs']['repositories'] as Map
        assertThat(repos['prometheus']['url']).isEqualTo('http://scmm-scm-manager.default.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack')

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'https://prometheus-community.github.io/helm-charts')
    }

    @Test
    void 'Applies Prometheus ServiceMonitor CRD from file before installing (air-gapped mode)'() {
        config['features']['monitoring']['active'] = true
        config.application['mirrorRepos'] = true

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application['localHelmChartFolder'] = rootChartsFolder.toString()

        Path crdPath = rootChartsFolder.resolve('kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml')
        Files.createDirectories(crdPath)

        createArgoCD().install()

        k8sCommands.assertExecuted('kubectl create namespace monitoring')
        k8sCommands.assertExecuted("kubectl apply -f ${crdPath}")
    }

    @Test
    void 'Applies Prometheus ServiceMonitor CRD from GitHub before installing'() {
        config['features']['monitoring']['active'] = true

        createArgoCD().install()

        k8sCommands.assertExecuted('kubectl create namespace monitoring')
        k8sCommands.assertExecuted("kubectl apply -f https://raw.githubusercontent.com/prometheus-community/helm-charts/kube-prometheus-stack-42.0.3/charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml")
    }

    @Test
    void 'If urlSeparatorHyphen is set, ensure that hostnames are build correctly '() {
        config.application['remote'] = true
        config.features['exampleApps']['petclinic']['baseDomain'] = 'petclinic-local'
        config.features['exampleApps']['nginx']['baseDomain'] = 'nginx-local'
        config.application['urlSeparatorHyphen'] = true

        createArgoCD().install()

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production-nginx-helm-umbrella-nginx-local')

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production-nginx-helm-nginx-local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging-nginx-helm-nginx-local')
        assertPetClinicRepos('LoadBalancer', 'NodePort', 'petclinic-local', true)
    }

    @Test
    void 'If urlSeparatorHyphen is NOT set, ensure that hostnames are build correctly '() {
        config.application['remote'] = true
        config.features['exampleApps']['petclinic']['baseDomain'] = 'petclinic.local'
        config.features['exampleApps']['nginx']['baseDomain'] = 'nginx.local'
        config.application['urlSeparatorHyphen'] = false

        createArgoCD().install()

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production.nginx-helm-umbrella.nginx.local')

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production.nginx-helm.nginx.local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging.nginx-helm.nginx.local')
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
    void 'Creates Jenkinsfiles for two registries'() {
        config.registry['twoRegistries'] = true
        createArgoCD().install()

        assertJenkinsEnvironmentVariablesPrefixes('')
        assertJenkinsfileRegistryCredentials()
    }

    @Test
    void 'Pushes repos with name-prefix'() {
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

    @Test
    void 'Write maven mirror into jenkinsfiles'() {
        config.jenkins['mavenCentralMirror'] = 'http://test'
        createArgoCD().install()


        for (def petclinicRepo : petClinicRepos) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(
                        'mvn.useMirrors([name: \'maven-central-mirror\', mirrorOf: \'central\', url:  env.MAVEN_CENTRAL_MIRROR])'
                )
        }
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

        assertAllYamlFiles(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), 'misc', 7) { Path it ->
            def yaml = parseActualYaml(it.toString())
            List yamlDocuments = yaml instanceof List ? yaml : [yaml]
            for (def document in yamlDocuments) {
                // Check all YAMLs objects for proper namespace, but namespaces because they dont have namespace attributes 
                if (document && document['kind'] != 'Namespace') {
                    def metadataNamespace = document['metadata']['namespace'] as String
                    assertThat(metadataNamespace)
                            .as("$it metadata.namespace has name prefix")
                            .startsWith("${expectedPrefix}")
                }
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
        List singleRegistryEnvVars = ["env.${prefix}REGISTRY_URL", "env.${prefix}REGISTRY_URL"]
        List twoRegistriesEnvVars = ["env.${prefix}REGISTRY_PULL_URL", "env.${prefix}REGISTRY_PULL_PATH",
                                   "env.${prefix}REGISTRY_PUSH_URL", "env.${prefix}REGISTRY_PUSH_PATH" ]
        
        assertThat(new File(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains("env.${prefix}K8S_VERSION")
        
        for (def petclinicRepo : petClinicRepos) {
            if (config.registry['twoRegistries']) {
                twoRegistriesEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(expectedEnvVar)
                }
                singleRegistryEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).doesNotContain(expectedEnvVar)
                }
            } else {
                singleRegistryEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(expectedEnvVar)
                }
                twoRegistriesEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).doesNotContain(expectedEnvVar)
                }
            }
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
        def argoCD = new ArgoCDForTest(config, helmCommands)
        k8sCommands = (argoCD.k8sClient as K8sClientForTest).commandExecutorForTest
        argocdRepo = argoCD.argocdRepoInitializationAction.repo
        actualHelmValuesFile = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.HELM_VALUES_PATH)
        clusterResourcesRepo = argoCD.clusterResourcesInitializationAction.repo
        exampleAppsRepo = argoCD.exampleAppsInitializationAction.repo
        nginxHelmJenkinsRepo = argoCD.nginxHelmJenkinsInitializationAction.repo
        nginxValidationRepo = argoCD.nginxValidationInitializationAction.repo
        brokenApplicationRepo = argoCD.brokenApplicationInitializationAction.repo
        remotePetClinicRepoTmpDir = argoCD.remotePetClinicRepoTmpDir
        petClinicRepos = argoCD.petClinicInitializationActions.collect { it.repo }
        return argoCD
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

        for (Map.Entry image : buildImages as Map) {
            assertThat(actualBuildImages).contains("${image.key}: '${image.value}'")
        }
    }

    void assertPetClinicRepos(String expectedServiceType, String unexpectedServiceType, String ingressUrl, boolean separatorHyphen = false) {
        for (ScmmRepo repo : petClinicRepos) {

            def tmpDir = repo.absoluteLocalRepoTmpDir
            def jenkinsfile = new File(tmpDir, 'Jenkinsfile')
            assertThat(jenkinsfile).exists()
            assertJenkinsfileRegistryCredentials()

            if (repo.scmmRepoTarget == 'argocd/petclinic-plain') {
                assertBuildImagesInJenkinsfileReplaced(jenkinsfile)
                
                assertThat(new File(tmpDir, 'Dockerfile').text).startsWith('FROM petclinic-value')
                
                assertThat(new File(tmpDir, 'k8s/production/service.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/staging/service.yaml').text).contains("type: ${expectedServiceType}")

                assertThat(new File(tmpDir, 'k8s/production/service.yaml').text).doesNotContain("type: ${unexpectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/staging/service.yaml').text).doesNotContain("type: ${unexpectedServiceType}")

                if (!ingressUrl) {
                    assertThat(new File(tmpDir, 'k8s/staging/ingress.yaml')).doesNotExist()
                    assertThat(new File(tmpDir, 'k8s/production/ingress.yaml')).doesNotExist()
                } else {
                    String ingressHostProduction = (parseActualYaml(tmpDir + '/k8s/production/ingress.yaml')['spec']['rules'] as List)[0]['host']
                    String ingressHostStaging = (parseActualYaml(tmpDir + '/k8s/staging/ingress.yaml')['spec']['rules'] as List)[0]['host']
                    if (separatorHyphen) {
                        assertThat(ingressHostStaging).isEqualTo("staging-petclinic-plain-$ingressUrl".toString())
                        assertThat(ingressHostProduction).isEqualTo("production-petclinic-plain-$ingressUrl".toString())
                    } else {
                        assertThat(ingressHostStaging).isEqualTo("staging.petclinic-plain.$ingressUrl".toString())
                        assertThat(ingressHostProduction).isEqualTo("production.petclinic-plain.$ingressUrl".toString())
                    }
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
                    String ingressHostProduction = (parseActualYaml(tmpDir + '/k8s/values-production.yaml')['ingress']['hosts'] as List)[0]['host']
                    String ingressHostStaging = (parseActualYaml(tmpDir + '/k8s/values-staging.yaml')['ingress']['hosts'] as List)[0]['host']

                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['ingress']['enabled']).isEqualTo(true)
                    if (separatorHyphen) {
                        assertThat(ingressHostProduction).isEqualTo("production-petclinic-helm-$ingressUrl".toString())
                        assertThat(ingressHostStaging).isEqualTo("staging-petclinic-helm-$ingressUrl".toString())
                    } else {
                        assertThat(ingressHostProduction).isEqualTo("production.petclinic-helm.$ingressUrl".toString())
                        assertThat(ingressHostStaging).isEqualTo("staging.petclinic-helm.$ingressUrl".toString())
                    }
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
                    String ingressHostProduction = (parseActualYaml(tmpDir + '/k8s/values-production.yaml')['ingress']['hosts'] as List)[0]['host']
                    String ingressHostStaging = (parseActualYaml(tmpDir + '/k8s/values-staging.yaml')['ingress']['hosts'] as List)[0]['host']

                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['ingress']['enabled']).isEqualTo(true)
                    if (separatorHyphen) {
                        assertThat(ingressHostProduction).isEqualTo("production-exercise-petclinic-helm-$ingressUrl".toString())
                        assertThat(ingressHostStaging).isEqualTo("staging-exercise-petclinic-helm-$ingressUrl".toString())
                    } else {
                        assertThat(ingressHostProduction).isEqualTo("production.exercise-petclinic-helm.$ingressUrl".toString())
                        assertThat(ingressHostStaging).isEqualTo("staging.exercise-petclinic-helm.$ingressUrl".toString())
                    }
                }
            } else {
                fail("Unkown petclinic repo: $repo")
            }
        }
    }

    void assertJenkinsfileRegistryCredentials() {
        List singleRegistryExpectedLines = [
                'docker.withRegistry("http://${dockerRegistryBaseUrl}", dockerRegistryCredentials) {',
                'String pathPrefix = !dockerRegistryPath?.trim() ? "" : "${dockerRegistryPath}/"',
                'imageName = "${dockerRegistryBaseUrl}/${pathPrefix}${application}:${imageTag}"'
        ]
        List twoRegistriesExpectedLines = [ 
                'String pathPrefix = !dockerRegistryPushPath?.trim() ? "" : "${dockerRegistryPushPath}/"',
                'imageName = "${dockerRegistryPushBaseUrl}/${pathPrefix}${application}:${imageTag}"',
                'docker.withRegistry("http://${dockerRegistryPullBaseUrl}", dockerRegistryPullCredentials) {',
                'docker.withRegistry("http://${dockerRegistryPushBaseUrl}", dockerRegistryPushCredentials) {' ]
        
        for (def petclinicRepo : petClinicRepos) {
            String jenkinsfile = new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text
            if (config.registry['twoRegistries']) {
                twoRegistriesExpectedLines.each { expectedEnvVar ->
                    assertThat(jenkinsfile).contains(expectedEnvVar)
                }
                singleRegistryExpectedLines.each { expectedEnvVar ->
                    assertThat(jenkinsfile).doesNotContain(expectedEnvVar)
                }
            } else {
                singleRegistryExpectedLines.each { expectedEnvVar ->
                    assertThat(jenkinsfile).contains(expectedEnvVar)
                }
                twoRegistriesExpectedLines.each { expectedEnvVar ->
                    assertThat(jenkinsfile).doesNotContain(expectedEnvVar)
                }
            }
        }
    }

    class ArgoCDForTest extends ArgoCD {
        ArgoCDForTest(Map config, CommandExecutorForTest helmCommands) {
            super(new Configuration(config), new K8sClientForTest(config), new HelmClient(helmCommands), new FileSystemUtils(),
                    new TestScmmRepoProvider(new Configuration(config), new FileSystemUtils()))
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
