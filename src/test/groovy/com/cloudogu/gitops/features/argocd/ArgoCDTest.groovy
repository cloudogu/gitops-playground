package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.providers.scmmanager.ScmManagerMock
import com.cloudogu.gitops.utils.*
import groovy.io.FileType
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CloneCommand
import org.junit.jupiter.api.Test
import org.mockito.Spy
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable
import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode
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
    Config config = Config.fromMap(
            application: [
                    openshift           : false,
                    remote              : false,
                    insecure            : false,
                    password            : '123',
                    username            : 'something',
                    namePrefix          : '',
                    namePrefixForEnvVars: '',
                    gitName             : 'Cloudogu',
                    gitEmail            : 'hello@cloudogu.com',
                    namespaces          : [
                            dedicatedNamespaces: ["argocd", "monitoring", "ingress-nginx", "secrets"],
                            tenantNamespaces   : ["example-apps-staging", "example-apps-production"]
                    ]
            ],
            scm: [
                    scmManager: [
                            internal: true,
                            url     : 'https://abc'],
                    gitlab    : [
                            url: ''
                    ]
            ],
            multiTenant: [
                    scmManager          : [
                            url: ''
                    ],
                    gitlab              : [
                            url: ''
                    ],
                    useDedicatedInstance: false
            ],
            images: buildImages + [petclinic: 'petclinic-value'],
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
            content: [
                    examples: true
            ],
            features: [
                    argocd      : [
                            operator                 : false,
                            active                   : true,
                            configOnly               : true,
                            emailFrom                : 'argocd@example.org',
                            emailToUser              : 'app-team@example.org',
                            emailToAdmin             : 'infra@example.org',
                            resourceInclusionsCluster: ''
                    ],
                    mail        : [
                            mailhog: true,
                    ],
                    monitoring  : [
                            active: true,
                            helm  : [
                                    chart  : 'kube-prometheus-stack',
                                    version: '42.0.3'
                            ]
                    ],
                    ingressNginx: [
                            active: true
                    ],
                    secrets     : [
                            active: true,

                    ]
            ]
    )

    @Spy
    CommandExecutor test = new CommandExecutor()

    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    CommandExecutorForTest helmCommands = new CommandExecutorForTest()
    GitRepo argocdRepo
    String actualHelmValuesFile
    GitRepo clusterResourcesRepo
    GitRepo exampleAppsRepo
    GitRepo nginxHelmJenkinsRepo
    GitRepo nginxValidationRepo
    GitRepo brokenApplicationRepo
    GitRepo tenantBootstrap
    List<GitRepo> petClinicRepos = []
    CloneCommand gitCloneMock = mock(CloneCommand.class, RETURNS_DEEP_STUBS)
    String prefixPathCentral = '/multiTenant/central/'
    ArgoCD argocd

    @Test
    void 'Installs argoCD'() {
        def argocd = createArgoCD()

        // Simulate argocd Namespace does not exist
        k8sCommands.enqueueOutput(new CommandExecutor.Output('namespace not found', '', 1))

        argocd.install()

        k8sCommands.assertExecuted('kubectl create namespace argocd')

        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('ClusterIP')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('ClusterIP')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['argocdUrl']).isNull()

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['crds']).isNull()
        assertThat(parseActualYaml(actualHelmValuesFile)['global']).isNull()

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
        assertThat(BCrypt.checkpw(config.application.password as String,
                parseActualYaml(patchFile)['stringData']['admin.password'] as String))
                .as("Password hash missmatch").isTrue()

        // Check bootstrapping
        k8sCommands.assertExecuted("kubectl apply -f " +
                "${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml')}")
        k8sCommands.assertExecuted("kubectl apply -f " +
                "${Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml')}")

        def deleteCommand = k8sCommands.assertExecuted('kubectl delete secret -n argocd')
        assertThat(deleteCommand).contains('owner=helm', 'name=argocd')

        // Operator disabled
        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def rbacConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_RBAC_PATH)
        assertThat(argocdConfigPath.toFile()).doesNotExist()
        assertThat(rbacConfigPath.toFile()).doesNotExist()

        // Projects
        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'https://prometheus-community.github.io/helm-charts')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack')
        // The other project files should be validated here as well!

        // Applications
        def argocdYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/argocd.yaml')
        assertThat(argocdYaml['spec']['source']['directory']).isNull()
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('argocd/')
        // The other application files should be validated here as well!

        // ContentLoader examples
        assertThat(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')).exists()
        assertThat(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/example-apps.yaml')).exists()
    }

    @Test
    void 'Disables example content'() {
        config.content.examples = false

        def argocd = createArgoCD()
        argocd.install()

        assertThat(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')).doesNotExist()
        assertThat(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/example-apps.yaml')).doesNotExist()
    }

    @Test
    void 'Installs argoCD for remote and external Scmm'() {
        config.application.remote = true
        config.scm.scmManager.internal = false
        config.features.argocd.url = 'https://argo.cd'

        def argocd = createArgoCD()
        argocd.install()
        List filesWithInternalSCMM = findFilesContaining(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
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
    void 'When monitoring disabled: Does not push path monitoring to cluster resources'() {
        config.features.monitoring.active = false

        createArgoCD().install()

        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + ArgoCD.MONITORING_RESOURCES_PATH)).doesNotExist()
    }

    @Test
    void 'When monitoring enabled: Does push path monitoring to cluster resources'() {
        config.features.monitoring.active = true

        createArgoCD().install()

        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + ArgoCD.MONITORING_RESOURCES_PATH)).exists()

        assertValidDashboards()
    }

    void assertValidDashboards() {
        Files.walk(Path.of(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), "/misc/monitoring/"))
                .filter { it.toString() ==~ /.*-dashboard\.yaml/ }.each { Path path ->
            def dashboardConfigMap = null

            assertThatCode {
                dashboardConfigMap = parseActualYaml(path.toString())
            }.as("Invalid YAML in ${path.fileName}").doesNotThrowAnyException()

            assertThat(dashboardConfigMap.data as Map).hasSize(1)
                    .as('Expected only on dashboard json within map')
            assertThatCode {
                def dashboardJsonString = (dashboardConfigMap.data as Map).entrySet().first().value as String
                new JsonSlurper().parseText(dashboardJsonString)
            }.as("Invalid JSON in ${path.fileName}").doesNotThrowAnyException()
        }
    }

    @Test
    void 'When ingressNginx disabled: Does not push monitoring dashboard resources'() {
        config.features.monitoring.active = true
        config.features.ingressNginx.active = false
        createArgoCD().install()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + ArgoCD.MONITORING_RESOURCES_PATH)).exists()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/ingress-nginx-dashboard.yaml")).doesNotExist()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/ingress-nginx-dashboard-requests-handling.yaml")).doesNotExist()
    }

    @Test
    void 'When mailhog disabled: Does not include mail configurations into cluster resources'() {

        config.features.mail.active = false
        config.features.mail.mailhog = false
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['notifications']['enabled']).isEqualTo(false)
        assertThat(valuesYaml['argo-cd']['notifications']['notifiers']).isNull()
    }

    @Test
    void 'When mailhog enabled: Includes mail configurations into cluster resources'() {
        config.features.mail.active = true
        createArgoCD().install()
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['notifications']['enabled']).isEqualTo(true)
        assertThat(valuesYaml['argo-cd']['notifications']['notifiers']).isNotNull()
    }

    @Test
    void 'When emailaddress is set: Include given email addresses into configurations'() {
        config.features.mail.active = true
        config.features.argocd.emailFrom = 'argocd@example.com'
        config.features.argocd.emailToUser = 'app-team@example.com'
        config.features.argocd.emailToAdmin = 'argocd@example.com'
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
        config.features.mail.active = true

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
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpPort = 1010110
        config.features.mail.smtpUser = 'argo@example.com'
        config.features.mail.smtpPassword = '1101:ABCabc&/+*~'

        createArgoCD().install()
        def serviceEmail = new YamlSlurper().parseText(
                parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['notifiers']['service.email'] as String)

        assertThat(serviceEmail['host']).isEqualTo(config.features.mail.smtpAddress)
        assertThat(serviceEmail['port']).isEqualTo(config.features.mail.smtpPort)
        // username and password are both linked to the k8s secret. Secrets will be created at runtime, in this test
        assertThat(serviceEmail['username']).isEqualTo('$email-username')
        assertThat(serviceEmail['password']).isEqualTo('$email-password')

        def createMailSecretCommand = k8sCommands.assertExecuted('kubectl create secret generic argocd-notifications-secret -n argocd')
        assertThat(createMailSecretCommand).contains('email-username', config.features.mail.smtpUser as CharSequence)
        assertThat(createMailSecretCommand).contains('email-password', config.features.mail.smtpPassword as CharSequence)
    }

    @Test
    void 'When external emailservers username is set, check if kubernetes secret will be created'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpUser = 'argo@example.com'

        createArgoCD().install()

        def createMailSecretCommand = k8sCommands.assertExecuted('kubectl create secret generic argocd-notifications-secret -n argocd')
        assertThat(createMailSecretCommand).contains('email-username', config.features.mail.smtpUser as CharSequence)
    }

    @Test
    void 'When external emailservers password is set, check if kubernetes secret will be created'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpPassword = '1101:ABCabc&/+*~'

        createArgoCD().install()

        def createMailSecretCommand = k8sCommands.assertExecuted('kubectl create secret generic argocd-notifications-secret -n argocd')
        assertThat(createMailSecretCommand).contains('email-password', config.features.mail.smtpPassword as CharSequence)
    }


    @Test
    void 'When external Mailserver is set without port, user, password'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'

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
        config.features.mail.active = true
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
        config.features.secrets.active = false
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
        config.features.secrets.active = false
        createArgoCD().install()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/secrets")).doesNotExist()
    }

    @Test
    void 'Pushes example repos for local'() {
        config.application.remote = false
        def argocd = createArgoCD()

        def setUriMock = mock(CloneCommand.class, RETURNS_DEEP_STUBS)
        def checkoutMock = mock(CheckoutCommand.class, RETURNS_DEEP_STUBS)
        when(gitCloneMock.setURI(anyString())).thenReturn(setUriMock)
        when(setUriMock.setDirectory(any(File.class)).call().checkout()).thenReturn(checkoutMock)

        argocd.install()
        def valuesYaml = parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml')
        assertThat(valuesYaml['service']['type']).isEqualTo('ClusterIP')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')).doesNotContainKey('ingress')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')).doesNotContainKey('ingress')
        assertThat(valuesYaml).doesNotContainKey('resources')

        valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['service']['type']).isEqualTo('ClusterIP')
        assertThat(valuesYaml['nginx'] as Map).doesNotContainKey('ingress')
        assertThat(valuesYaml['nginx'] as Map).doesNotContainKey('resources')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[1]['spec']['type']))
                .isEqualTo('ClusterIP')
        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[0]['spec']['template']['spec']['containers'] as List)[0]['resources'])
                .isNull()

        assertThat(new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text).doesNotContain('resources:')

        // Assert Petclinic repo cloned
        verify(gitCloneMock).setURI('https://github.com/cloudogu/spring-petclinic.git')
        verify(setUriMock).setDirectory(argocd.remotePetClinicRepoTmpDir)
        verify(checkoutMock).setName('32c8653')

        assertPetClinicRepos('ClusterIP', 'LoadBalancer', '')
    }

    @Test
    void 'Pushes example repos for remote'() {
        config.application.remote = true
        config.features.exampleApps.petclinic.baseDomain = 'petclinic.local'
        config.features.exampleApps.nginx.baseDomain = 'nginx.local'

        createArgoCD().install()

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml').toString())
                .doesNotContain('ClusterIP')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production.nginx-helm.nginx.local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging.nginx-helm.nginx.local')

        assertThat(parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml').toString())
                .doesNotContain('ClusterIP')

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production.nginx-helm-umbrella.nginx.local')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[2]['spec']['rules'] as List)[0]['host'])
                .isEqualTo('broken-application.nginx.local')
        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[1]['spec']['type']))
                .isEqualTo('LoadBalancer')

        assertPetClinicRepos('LoadBalancer', 'ClusterIP', 'petclinic.local')
    }

    @Test
    void 'Prepares repos for air-gapped mode'() {
        config.features.monitoring.active = false
        config.application.mirrorRepos = true

        createArgoCD().install()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'https://prometheus-community.github.io/helm-charts')
    }

    @Test
    void 'Applies Prometheus ServiceMonitor CRD from file before installing (air-gapped mode)'() {
        config.features.monitoring.active = true
        config.application.mirrorRepos = true

        Path rootChartsFolder = Files.createTempDirectory(this.class.getSimpleName())
        config.application.localHelmChartFolder = rootChartsFolder.toString()

        Path crdPath = rootChartsFolder.resolve('kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml')
        Files.createDirectories(crdPath)

        createArgoCD().install()

        k8sCommands.assertExecuted("kubectl apply -f ${crdPath}")
    }

    @Test
    void 'Applies Prometheus ServiceMonitor CRD from GitHub before installing'() {
        config.features.monitoring.active = true

        createArgoCD().install()

        k8sCommands.assertExecuted("kubectl apply -f https://raw.githubusercontent.com/prometheus-community/helm-charts/kube-prometheus-stack-42.0.3/charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml")
    }

    @Test
    void 'If urlSeparatorHyphen is set, ensure that hostnames are build correctly '() {
        config.application.remote = true
        config.features.exampleApps.petclinic.baseDomain = 'petclinic-local'
        config.features.exampleApps.nginx.baseDomain = 'nginx-local'
        config.application.urlSeparatorHyphen = true

        createArgoCD().install()

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production-nginx-helm-umbrella-nginx-local')

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production-nginx-helm-nginx-local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging-nginx-helm-nginx-local')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[2]['spec']['rules'] as List)[0]['host'])
                .isEqualTo('broken-application-nginx-local')

        assertPetClinicRepos('LoadBalancer', 'ClusterIP', 'petclinic-local')
    }

    @Test
    void 'If urlSeparatorHyphen is NOT set, ensure that hostnames are build correctly '() {
        config.application.remote = true
        config.features.exampleApps.petclinic.baseDomain = 'petclinic.local'
        config.features.exampleApps.nginx.baseDomain = 'nginx.local'
        config.application.urlSeparatorHyphen = false

        createArgoCD().install()

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production.nginx-helm-umbrella.nginx.local')

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production.nginx-helm.nginx.local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging.nginx-helm.nginx.local')
        assertPetClinicRepos('LoadBalancer', 'ClusterIP', 'petclinic.local')
    }

    @Test
    void 'For internal SCMM: Use service address in gitops repos'() {
        def argocd = createArgoCD()
        argocd.install()
        List filesWithInternalSCMM = findFilesContaining(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        filesWithInternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
        assertThat(filesWithInternalSCMM).isNotEmpty()
    }

    @Test
    void 'For external SCMM: Use external address in gitops repos'() {
        config.scm.internal = false
        def argocd = createArgoCD()
        argocd.install()
        List filesWithInternalSCMM = findFilesContaining(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
        assertThat(filesWithInternalSCMM).isEmpty()

        List filesWithExternalSCMM = findFilesContaining(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
    }

    @Test
    void 'Pushes repos with empty name-prefix'() {
        def argocd = createArgoCD()
        argocd.install()

        assertArgoCdYamlPrefixes(argocd.scmmUrlInternal, '')
        assertJenkinsEnvironmentVariablesPrefixes('')
    }

    @Test
    void 'Creates Jenkinsfiles for two registries'() {
        config.registry.twoRegistries = true
        createArgoCD().install()

        assertJenkinsEnvironmentVariablesPrefixes('')
        assertJenkinsfileRegistryCredentials()
    }

    @Test
    void 'Pushes repos with name-prefix'() {
        config.application.namePrefix = 'abc-'
        config.application.namePrefixForEnvVars = 'ABC_'

        def argocd = createArgoCD()
        argocd.install()

        assertArgoCdYamlPrefixes(argocd.scmmUrlInternal, 'abc-')
        assertJenkinsEnvironmentVariablesPrefixes('ABC_')
    }

    @Test
    void 'SecurityContext null in Openshift'() {
        config.application.openshift = true
        createArgoCD().install()

        for (def petclinicRepo : petClinicRepos) {
            if (petclinicRepo.repoTarget.contains('argocd/petclinic-plain')) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, '/k8s/staging/deployment.yaml').text).contains('runAsUser: null')
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, '/k8s/staging/deployment.yaml').text).contains('runAsGroup: null')
            }
            if (petclinicRepo.repoTarget.contains('argocd/petclinic-helm')) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text).contains('runAsUser: null')
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text).contains('runAsGroup: null')
            }
        }
    }

    @Test
    void 'Skips CRDs for argo cd'() {
        config.application.skipCrds = true

        createArgoCD().install()

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['crds']['install']).isEqualTo(false)
    }

    @Test
    void 'disables serviceMonitor, when monitoring not active'() {
        config.application.skipCrds = true

        createArgoCD().createMonitoringCrd()

        k8sCommands.assertNotExecuted('kubectl apply -f https://raw.githubusercontent.com/prometheus-community/helm-charts/')
    }

    @Test
    void 'Write maven mirror into jenkinsfiles'() {
        config.jenkins.mavenCentralMirror = 'http://test'
        createArgoCD().install()

        for (def petclinicRepo : petClinicRepos) {
            assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(
                    'mvn.useMirrors([name: \'maven-central-mirror\', mirrorOf: \'central\', url:  env.MAVEN_CENTRAL_MIRROR])'
            )
        }
    }

    @Test
    void 'use custom maven image'() {
        config.images.maven = 'maven:latest'

        createArgoCD().install()

        for (def petclinicRepo : petClinicRepos) {
            if (petclinicRepo.repoTarget.contains('argocd/petclinic-plain')) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains('mvn = cesBuildLib.MavenInDocker.new(this, \'maven:latest\')')
            }
        }
    }

    @Test
    void 'use maven with proxy registry and credentials'() {
        config.images.maven = 'latest'
        config.registry.twoRegistries = true

        createArgoCD().install()

        for (def petclinicRepo : petClinicRepos) {
            if (petclinicRepo.repoTarget.contains('argocd/petclinic-plain')) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains('mvn = cesBuildLib.MavenInDocker.new(this, \'latest\', dockerRegistryProxyCredentials)')
            }
        }

    }


    //TODO Anna remove example apps
    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createArgoCD().install()

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml')['resources'] as Map)
                .containsKeys('limits', 'requests')

        assertThat(parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')['nginx']['resources'] as Map)
                .containsKeys('limits', 'requests')

        assertThat(new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text).contains('limits:', 'resources:')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[0]['spec']['template']['spec']['containers'] as List)[0]['resources'] as Map)
                .containsKeys('limits', 'requests')

        assertPetClinicRepos('ClusterIP', 'LoadBalancer', '')
    }

    @Test
    void 'ArgoCD with active network policies'() {
        config.application.netpols = true

        createArgoCD().install()

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['global']['networkPolicy']['create']).isEqualTo(true)
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), '/argocd/values.yaml').text.contains("namespace: monitoring"))
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), '/argocd/templates/allow-namespaces.yaml').text.contains("namespace: monitoring"))
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), '/argocd/templates/allow-namespaces.yaml').text.contains("namespace: default"))
    }

    @Test
    void 'set credentials for BuildImages'() {
        config.registry.twoRegistries = true

        createArgoCD().install()

        assertPetClinicRepos('ClusterIP', 'LoadBalancer', '')
    }


    private static Map parseBuildImagesMapFromString(String text) {
        def startIndex = text.indexOf('buildImages')
        if (startIndex != -1) {
            def bracketCount = 0
            def inBrackets = false
            def endIndex = startIndex

            for (i in startIndex..text.length() - 1) {
                if (text[i] == '[') {
                    bracketCount++
                    inBrackets = true
                } else if (text[i] == ']') {
                    bracketCount--
                }

                if (inBrackets && bracketCount == 0) {
                    endIndex = i + 1
                    break
                }
            }

            def matchedText = text.substring(startIndex + 'buildImages'.length(), endIndex).trim().replaceFirst(":", "")

            Binding binding = new Binding()
            binding.setVariable('dockerRegistryProxyCredentials', 'dockerRegistryProxyCredentials')
            def map = new GroovyShell(binding).evaluate(matchedText)

            return map as Map

        } else {
            return [:]
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

        assertAllYamlFiles(new File(argocdRepo.getAbsoluteLocalRepoTmpDir()), 'applications', 4) { Path file ->
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

        assertAllYamlFiles(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir()), 'misc', 9) { Path it ->
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
        List defaultRegistryEnvVars = ["env.${prefix}REGISTRY_URL", "env.${prefix}REGISTRY_PATH"]
        List twoRegistriesEnvVars = ["env.${prefix}REGISTRY_PROXY_URL"]

        assertThat(new File(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains("env.${prefix}K8S_VERSION")

        for (def petclinicRepo : petClinicRepos) {
            defaultRegistryEnvVars.each { expectedEnvVar ->
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(expectedEnvVar)
            }

            if (config.registry['twoRegistries']) {
                twoRegistriesEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(expectedEnvVar)
                }
            } else {
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
        def argoCD = new ArgoCDForTest(config, k8sCommands, helmCommands)
        return argoCD
    }

    void assertBuildImagesInJenkinsfileReplaced(File jenkinsfile) {
        def actualBuildImages = parseBuildImagesMapFromString(jenkinsfile.text)
        if (!actualBuildImages) {
            fail("Missing build images in Jenkinsfile ${jenkinsfile}")
        }

        if (config.registry.twoRegistries) {
            for (Map.Entry image : actualBuildImages as Map) {
                assertThat(image.value['image']).isEqualTo(buildImages[image.key])
                assertThat(image.value['credentialsId']).isEqualTo('dockerRegistryProxyCredentials')
            }
        } else {

            assertThat(buildImages.keySet()).containsExactlyInAnyOrderElementsOf(actualBuildImages.keySet())
            for (Map.Entry image : buildImages as Map) {
                assertThat(image.value).isEqualTo(actualBuildImages[image.key])
            }
        }

    }

    void assertPetClinicRepos(String expectedServiceType, String unexpectedServiceType, String ingressUrl) {
        boolean separatorHyphen = config.application.urlSeparatorHyphen
        boolean podResources = config.application.podResources

        for (GitRepo repo : petClinicRepos) {

            def tmpDir = repo.absoluteLocalRepoTmpDir
            def jenkinsfile = new File(tmpDir, 'Jenkinsfile')
            assertThat(jenkinsfile).exists()
            assertJenkinsfileRegistryCredentials()

            if (repo.repoTarget == 'argocd/petclinic-plain') {
                assertBuildImagesInJenkinsfileReplaced(jenkinsfile)

                assertThat(new File(tmpDir, 'Dockerfile').text).startsWith('FROM petclinic-value')

                assertThat(new File(tmpDir, 'k8s/production/service.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/staging/service.yaml').text).contains("type: ${expectedServiceType}")

                assertThat(new File(tmpDir, 'k8s/production/service.yaml').text).doesNotContain("type: ${unexpectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/staging/service.yaml').text).doesNotContain("type: ${unexpectedServiceType}")

                if (podResources) {
                    assertThat((parseActualYaml(tmpDir + '/k8s/production/deployment.yaml')['spec']['template']['spec']['containers'] as List)[0]['resources'] as Map)
                            .containsKeys('limits', 'requests')
                    assertThat((parseActualYaml(tmpDir + '/k8s/staging/deployment.yaml')['spec']['template']['spec']['containers'] as List)[0]['resources'] as Map)
                            .containsKeys('limits', 'requests')
                } else {
                    assertThat((parseActualYaml(tmpDir + '/k8s/production/deployment.yaml')['spec']['template']['spec']['containers'] as List)[0] as Map)
                            .doesNotContainKey('resources')
                    assertThat((parseActualYaml(tmpDir + '/k8s/staging/deployment.yaml')['spec']['template']['spec']['containers'] as List)[0] as Map)
                            .doesNotContainKey('resources')
                }

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

            } else if (repo.repoTarget == 'argocd/petclinic-helm') {
                assertBuildImagesInJenkinsfileReplaced(jenkinsfile)
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).doesNotContain("type: ${unexpectedServiceType}")

                if (podResources) {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['resources'] as Map).containsKeys('limits', 'requests')
                } else {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['resources']).isNull()
                }

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

            } else if (repo.repoTarget == 'exercises/petclinic-helm') {
                // Does not contain the gitops build lib call, so no build images to replace
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).contains("type: ${expectedServiceType}")
                assertThat(new File(tmpDir, 'k8s/values-shared.yaml').text).doesNotContain("type: ${unexpectedServiceType}")

                if (podResources) {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['resources'] as Map).containsKeys('limits', 'requests')
                } else {
                    assertThat(parseActualYaml(tmpDir + '/k8s/values-shared.yaml')['resources']).isNull()
                }

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
        List defaultRegistryExpectedLines = [
                'String pathPrefix = !dockerRegistryPath?.trim() ? "" : "${dockerRegistryPath}/"',
                'imageName = "${dockerRegistryBaseUrl}/${pathPrefix}${application}:${imageTag}"'
        ]
        List twoRegistriesExpectedLines = [
                'docker.withRegistry("https://${dockerRegistryProxyBaseUrl}", dockerRegistryProxyCredentials) {']

        for (def petclinicRepo : petClinicRepos) {
            String jenkinsfile = new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text

            defaultRegistryExpectedLines.each { expectedEnvVar ->
                assertThat(jenkinsfile).contains(expectedEnvVar)
            }

            if (config.registry['twoRegistries']) {
                twoRegistriesExpectedLines.each { expectedEnvVar ->
                    assertThat(jenkinsfile).contains(expectedEnvVar)
                }
            } else {
                twoRegistriesExpectedLines.each { expectedEnvVar ->
                    assertThat(jenkinsfile).doesNotContain(expectedEnvVar)
                }
            }
        }
    }

    @Test
    void 'Prepares ArgoCD repo with Operator configuration file'() {
        def argocd = setupOperatorTest()

        argocd.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def rbacConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_RBAC_PATH)

        assertThat(argocdConfigPath.toFile()).exists()
        assertThat(rbacConfigPath.toFile()).exists()

        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())
        assertThat(yaml['apiVersion']).isEqualTo('argoproj.io/v1beta1')
        assertThat(yaml['kind']).isEqualTo('ArgoCD')
    }

    @Test
    void 'No files for operator when operator is false'() {
        createArgoCD().install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def rbacConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_RBAC_PATH)

        assertThat(argocdConfigPath.toFile()).doesNotExist()
        assertThat(rbacConfigPath.toFile()).doesNotExist()
    }

    @Test
    void 'Deploys with operator without OpenShift configuration'() {
        def argoCD = setupOperatorTest(openshift: false)

        argoCD.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        k8sCommands.assertExecuted("kubectl apply -f ${argocdConfigPath}")

        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())
        assertThat(yaml['spec']['rbac']).isNull()
        assertThat(yaml['spec']['sso']).isNull()

        def argocdYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/argocd.yaml')
        assertThat(argocdYaml['spec']['source']['directory']['recurse'] as Boolean).isTrue()
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('operator/')
        // Here we should assert all <#if argocd.isOperator> in YAML 😐️
    }

    @Test
    void 'RBACs with operator using RbacDefinition outputs'() {
        config.application.namePrefix = "testPrefix-"

        LinkedHashSet<String> expectedNamespaces = [
                "testPrefix-monitoring",
                "testPrefix-secrets",
                "testPrefix-ingress-nginx",
                "testPrefix-example-apps-staging",
                "testPrefix-example-apps-production"
        ]
        // have to prepare activeNamespaces for unit-test, Application.groovy is setting this in integration way
        config.application.namespaces.dedicatedNamespaces = new LinkedHashSet<String>([
                "monitoring",
                "secrets",
                "ingress-nginx",
                "example-apps-staging",
                "example-apps-production"
        ])

        def argoCD = setupOperatorTest(openshift: false)
        argoCD.install()

        File rbacPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_RBAC_PATH).toFile()


        expectedNamespaces.each { String ns ->
            File roleFile = new File(rbacPath, "role-argocd-${ns}.yaml")
            File bindingFile = new File(rbacPath, "rolebinding-argocd-${ns}.yaml")

            assertThat(roleFile).exists()
            assertThat(bindingFile).exists()

            Map<String, Object> roleYaml = new YamlSlurper().parse(roleFile) as Map
            Map<String, Object> bindingYaml = new YamlSlurper().parse(bindingFile) as Map

            assertThat(roleYaml["kind"]).isEqualTo("Role")
            assertThat(roleYaml["metadata"]["name"]).isEqualTo("argocd")
            assertThat(roleYaml["metadata"]["namespace"]).isEqualTo(ns)

            assertThat(bindingYaml["kind"]).isEqualTo("RoleBinding")
            assertThat(bindingYaml["metadata"]["name"]).isEqualTo("argocd")
            assertThat(bindingYaml["metadata"]["namespace"]).isEqualTo(ns)

            List<Map<String, Object>> subjects = bindingYaml["subjects"] as List<Map<String, Object>>
            assertThat(subjects).isNotEmpty()
            assertThat(subjects*.kind).containsOnly("ServiceAccount")
            assertThat(subjects*.namespace).containsOnly("testPrefix-argocd")
            assertThat(subjects*.name).containsExactlyInAnyOrder(
                    "argocd-argocd-server",
                    "argocd-argocd-application-controller",
                    "argocd-applicationset-controller"
            )

            Map<String, Object> roleRef = bindingYaml["roleRef"] as Map
            assertThat(roleRef).isNotNull()
            assertThat(roleRef["name"]).isEqualTo("argocd")
            assertThat(roleRef["kind"]).isEqualTo("Role")
        }
    }


    @Test
    void 'Deploys with operator with OpenShift configuration'() {
        def argoCD = setupOperatorTest(openshift: true)

        argoCD.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        k8sCommands.assertExecuted("kubectl apply -f ${argocdConfigPath}")

        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())
        assertThat(yaml['spec']['sso']).isNotNull()
        assertThat(yaml['spec']['sso']['dex']['openShiftOAuth']).isEqualTo(true)
        assertThat(yaml['spec']['sso']['provider']).isEqualTo('dex')
        assertThat(yaml['spec']['rbac']).isNotNull()
        assertThat(yaml['spec']['server']['route']['enabled']).isEqualTo(true)

        k8sCommands.assertNotExecuted("kubectl patch service argocd-server -n argocd")
    }

    @Test
    void 'Correctly sets resourceInclusions from config'() {
        def argoCD = setupOperatorTest()

        // Set the config to a custom resourceInclusionsCluster value
        config.features.argocd.resourceInclusionsCluster = 'https://192.168.0.1:6443'

        argoCD.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())

        def expectedClusterUrl = 'https://192.168.0.1:6443'

        // Retrieve and parse the resourceInclusions string into structured YAML
        def resourceInclusionsString = yaml['spec']['resourceInclusions'] as String
        def parsedResourceInclusions = new YamlSlurper().parseText(resourceInclusionsString)

        // Iterate over the parsed resource inclusions and check the 'clusters' field
        parsedResourceInclusions.each { resource ->
            assertThat(resource as Map).containsKey('clusters')
            assertThat(resource['clusters'] as List<String>).contains(expectedClusterUrl)
        }
    }

    @Test
    void 'resourceInclusionsCluster from config file trumps ENVs'() {
        def argoCD = setupOperatorTest()

        // Set the config to a custom internalKubernetesApiUrl value
        config.application.internalKubernetesApiUrl = 'https://192.168.0.1:6443'

        // Set environment variables for Kubernetes API server
        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "100.125.0.1")
                .and("KUBERNETES_SERVICE_PORT", "443")
                .execute {
                    argoCD.install()
                }

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())

        def expectedClusterUrlFromConfig = "https://192.168.0.1:6443"

        // Retrieve and parse the resourceInclusions string into structured YAML
        def resourceInclusionsString = yaml['spec']['resourceInclusions'] as String
        def parsedResourceInclusions = new YamlSlurper().parseText(resourceInclusionsString)

        // Ensure that the clusters field uses the config value, not the env variables
        parsedResourceInclusions.each { resource ->
            assertThat(resource as Map).containsKey('clusters')
            assertThat(resource['clusters'] as List<String>).contains(expectedClusterUrlFromConfig)
            // Make sure the environment variable value does not appear
            assertThat(resource['clusters'] as List<String>).doesNotContain("https://100.125.0.1:443")
        }
    }

    @Test
    void 'Sets env variables in ArgoCD components when provided'() {
        def argoCD = setupOperatorTest()

        // Set environment variables for ArgoCD
        config.features.argocd.env = [
                [name: "ENV_VAR_1", value: "value1"],
                [name: "ENV_VAR_2", value: "value2"]
        ] as List<Map>

        argoCD.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())

        def expectedEnv = [
                [name: "ENV_VAR_1", value: "value1"],
                [name: "ENV_VAR_2", value: "value2"]
        ]

        // Check that the env variables are added to the relevant components
        assertThat(yaml['spec']['applicationSet']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['notifications']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['controller']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['repo']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['server']['env']).isEqualTo(expectedEnv)
    }

    @Test
    void 'Does not set env variables when none are provided'() {
        def argoCD = setupOperatorTest()

        // Ensure env is an empty list (default)
        config.features.argocd.env = []

        argoCD.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())

        // Check that the env variables are not present
        assertThat(yaml['spec']['applicationSet'] as Map).doesNotContainKey('env')
        assertThat(yaml['spec']['notifications'] as Map).doesNotContainKey('env')
        assertThat(yaml['spec']['controller'] as Map).doesNotContainKey('env')
        assertThat(yaml['spec']['redis'] as Map).doesNotContainKey('env')
        assertThat(yaml['spec']['repo'] as Map).doesNotContainKey('env')
        assertThat(yaml['spec']['server'] as Map).doesNotContainKey('env')
    }

    @Test
    void 'Sets single env variable in ArgoCD components when provided'() {
        def argoCD = setupOperatorTest()

        // Set a single environment variable for ArgoCD
        config.features.argocd.env = [
                [name: "ENV_VAR_SINGLE", value: "singleValue"]
        ] as List<Map>

        argoCD.install()

        def argocdConfigPath = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH)
        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())

        def expectedEnv = [
                [name: "ENV_VAR_SINGLE", value: "singleValue"]
        ]

        // Check that the single env variable is added to the relevant components
        assertThat(yaml['spec']['applicationSet']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['notifications']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['controller']['env']).isEqualTo(expectedEnv)
        assertThat(yaml['spec']['server']['env']).isEqualTo(expectedEnv)
    }

    @Test
    void 'Creates all necessary namespaces'() {
        def argoCD = createArgoCD()
        simulateNamespaceCreation()

        argoCD.install()

        config.application.namespaces.getActiveNamespaces().each { namespace ->
            k8sCommands.assertExecuted("kubectl create namespace ${namespace}")
        }
    }

    @Test
    void 'Operator config sets server insecure to true when insecure is set'() {
        config.application.insecure = true
        def argoCD = setupOperatorTest()

        argoCD.install()

        def yaml = parseActualYaml(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH).toString())
        assertThat(yaml['spec']['server']['insecure']).isEqualTo(true)
    }

    @Test
    void 'Operator config sets server_insecure to false when insecure is not set'() {
        def argoCD = setupOperatorTest()

        argoCD.install()

        def yaml = parseActualYaml(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_CONFIG_PATH).toString())
        assertThat(yaml['spec']['server']['insecure']).isEqualTo(false)
    }

    @Test
    void 'Generates correct ingress yaml with expected host when insecure is true and not on OpenShift'() {
        config.application.insecure = true
        config.features.argocd.url = "http://argocd.localhost"
        def argoCD = setupOperatorTest(openshift: false)

        argoCD.install()

        def ingressFile = new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), "operator/ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should be generated for insecure mode on non-OpenShift")
                .exists()

        def ingressYaml = parseActualYaml(ingressFile.toString())

        def rules = ingressYaml['spec']['rules'] as List<Map>
        def host = rules[0]['host']
        assertThat(host)
                .as("Ingress host should match configured ArgoCD hostname")
                .isEqualTo(new URL(config.features.argocd.url).host)
    }

    @Test
    void 'Does not generate ingress yaml when insecure is false'() {
        config.application.insecure = false
        def argoCD = setupOperatorTest(openshift: false)

        argoCD.install()

        def ingressFile = new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), "operator/ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated when insecure is false")
                .doesNotExist()
    }

    @Test
    void 'Does not generate ingress yaml when running on OpenShift'() {
        config.application.insecure = true
        def argoCD = setupOperatorTest(openshift: true)

        argoCD.install()

        def ingressFile = new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), "operator/ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated on OpenShift")
                .doesNotExist()
    }

    @Test
    void 'Does not generate ingress yaml when insecure is false and OpenShift is true'() {
        config.application.insecure = false
        def argoCD = setupOperatorTest(openshift: true)

        argoCD.install()

        def ingressFile = new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), "operator/ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated when both flags are false")
                .doesNotExist()
    }

    @Test
    void 'Central Bootstrapping for Tenant Applications'() {
        setupDedicatedInstanceMode()
        def ingressFile = new File(argocdRepo.getAbsoluteLocalRepoTmpDir(), "operator/ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated when both flags are false")
                .doesNotExist()
    }


    @Test
    void 'GOP DedicatedInstances Central templating works correctly'() {
        setupDedicatedInstanceMode()
        //Central Applications
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/argocd.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/bootstrap.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/projects.yaml")).exists()
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}applications/example-apps.yaml")).doesNotExist()

        def argocdYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/argocd.yaml")
        def bootstrapYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/bootstrap.yaml")
        def clusterResourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/cluster-resources.yaml")
        def projectsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}applications/projects.yaml")

        assertThat(argocdYaml['metadata']['name']).isEqualTo('testPrefix-argocd')
        assertThat(argocdYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(argocdYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('operator/')

        assertThat(bootstrapYaml['metadata']['name']).isEqualTo('testPrefix-bootstrap')
        assertThat(bootstrapYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(bootstrapYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(bootstrapYaml['spec']['source']['repoURL']).isEqualTo("scmm.testhost/scm/repo/testPrefix-argocd/argocd")

        assertThat(clusterResourcesYaml['metadata']['name']).isEqualTo('testPrefix-cluster-resources')
        assertThat(clusterResourcesYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(clusterResourcesYaml['spec']['project']).isEqualTo('testPrefix')

        assertThat(projectsYaml['metadata']['name']).isEqualTo('testPrefix-projects')
        assertThat(projectsYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(projectsYaml['spec']['project']).isEqualTo('testPrefix')

        //Central Project
        assertThat(new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "${prefixPathCentral}projects/tenant.yaml")).exists()

        def tenantProject = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), "${prefixPathCentral}projects/tenant.yaml")

        assertThat(tenantProject['metadata']['name']).isEqualTo('testPrefix')
        assertThat(tenantProject['metadata']['namespace']).isEqualTo('argocd')
        def sourceRepos = (List<String>) tenantProject['spec']['sourceRepos']
        assertThat(sourceRepos[0]).isEqualTo('scmm.testhost/scm/repo/testPrefix-argocd/argocd')
        assertThat(sourceRepos[1]).isEqualTo('scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources')
    }

    @Test
    void 'Cluster Resource Misc templating'() {
        setupDedicatedInstanceMode()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/argocd/misc.yaml")).exists()
        def miscYaml = new YamlSlurper().parse(Path.of clusterResourcesRepo.getAbsoluteLocalRepoTmpDir(), "/argocd/misc.yaml")
        assertThat(miscYaml['metadata']['name']).isEqualTo('testPrefix-misc')
        assertThat(miscYaml['metadata']['namespace']).isEqualTo('argocd')
    }

    @Test
    void 'generate example-apps bootstrapping application via ArgoApplication when true'() {
        setupDedicatedInstanceMode()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/bootstrap.yaml")).exists()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/argocd-application-example-apps-testPrefix-argocd.yaml")).exists()
    }

    @Test
    void 'not generating example-apps bootstrapping application via ArgoApplication when false'() {
        config.content.examples = false
        setupDedicatedInstanceMode()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/bootstrap.yaml")).exists()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/argocd-application-example-apps-testPrefix-argocd.yaml")).doesNotExist()
    }

    @Test
    void 'Append namespaces to Argocd argocd-default-cluster-config secrets'() {
        config.application.namespaces.dedicatedNamespaces = new LinkedHashSet(['dedi-test1', 'dedi-test2', 'dedi-test3'])
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['tenant-test1', 'tenant-test2', 'tenant-test3'])
        setupDedicatedInstanceMode()
        k8sCommands.assertExecuted('kubectl get secret argocd-default-cluster-config -nargocd -ojsonpath={.data.namespaces}')
        k8sCommands.assertExecuted('kubectl patch secret argocd-default-cluster-config -n argocd --patch-file=/tmp/gitops-playground-patch-')
    }

    @Test
    void 'RBACs generated correctly'() {
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['testprefix-tenant-test1', 'testprefix-tenant-test2', 'testprefix-tenant-test3'])
        setupDedicatedInstanceMode()

        File rbacFolder = new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "/operator/rbac")
        File rbacTenantFolder = new File(argocdRepo.getAbsoluteLocalRepoTmpDir() + "/operator/rbac/tenant")
        assertThat(rbacFolder).exists()
        assertThat(rbacTenantFolder).exists()

        assertThat(rbacFolder.listFiles().count { it.isFile() }).isEqualTo(14)
        assertThat(rbacTenantFolder.listFiles().count { it.isFile() }).isEqualTo(6)

        rbacFolder.eachFile { file ->
            if (file.name.startsWith("role-") && file.name.contains('dedi')) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['metadata']['namespace']).isIn(config.application.namespaces.getActiveNamespaces())
            }
            if (file.name.startsWith("rolebinding-") && file.name.contains('dedi')) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['subjects']['namespace']).isEqualTo(["argocd", "argocd", "argocd"])
            }
        }

        rbacTenantFolder.eachFile { file ->
            if (file.name.startsWith("role-")) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['metadata']['namespace']).isIn(config.application.namespaces.tenantNamespaces)
            }

            if (file.name.startsWith("rolebinding-")) {
                def rbacFile = new YamlSlurper().parse(Path.of file.path)
                assertThat(rbacFile['subjects']['namespace']).isEqualTo(["testPrefix-argocd", "testPrefix-argocd", "testPrefix-argocd"])
            }
        }

    }

    @Test
    void 'Operator RBAC includes node access rules when not on OpenShift'() {
        config.application.namePrefix = "testprefix-"

        def argoCD = setupOperatorTest(openshift: false)
        argoCD.install()

        print config.toMap()

        File rbacDir = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_RBAC_PATH).toFile()
        File roleFile = new File(rbacDir, "role-argocd-testprefix-monitoring.yaml")

        Map yaml = new YamlSlurper().parse(roleFile) as Map
        List<Map<String, Object>> rules = yaml["rules"] as List<Map<String, Object>>

        assertThat(rules).anyMatch { rule ->
            List<String> resources = rule["resources"] as List<String>
            resources.contains("nodes") && resources.contains("nodes/metrics")
        }
    }

    @Test
    void 'Operator RBAC does not include node access rules when on OpenShift'() {
        config.application.namePrefix = "testprefix-"

        def argoCD = setupOperatorTest(openshift: true)
        argoCD.install()

        File rbacDir = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), ArgoCD.OPERATOR_RBAC_PATH).toFile()
        File roleFile = new File(rbacDir, "role-argocd-testprefix-monitoring.yaml")
        println roleFile

        Map yaml = new YamlSlurper().parse(roleFile) as Map
        List<Map<String, Object>> rules = yaml["rules"] as List<Map<String, Object>>

        assertThat(rules).noneMatch { rule ->
            List<String> resources = rule["resources"] as List<String>
            resources.contains("nodes") && resources.contains("nodes/metrics")
        }
    }

    @Test
    void 'If not using mirror, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = false

        createArgoCD().install()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        clusterRessourcesYaml['spec']['sourceRepos']

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'https://charts.external-secrets.io',
                'https://codecentric.github.io/helm-charts',
                'https://prometheus-community.github.io/helm-charts',
                'https://kubernetes.github.io/ingress-nginx',
                'https://helm.releases.hashicorp.com',
                'https://charts.jetstack.io'
        )
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/mailhog',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/ingress-nginx',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager'
        )

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/kube-prometheus-stack.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/mailhog.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/ingress-nginx.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/external-secrets.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/vault.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/cert-manager.git'
        )
    }

    @Test
    void 'If using mirror, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true

        createArgoCD().install()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        clusterRessourcesYaml['spec']['sourceRepos']

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/mailhog',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/ingress-nginx',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager'

        )
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/kube-prometheus-stack.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/mailhog.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/ingress-nginx.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/external-secrets.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/vault.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/cert-manager.git'
        )
    }

    @Test
    void 'If using mirror with GitLab, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true
        config.scm.scmProviderType = 'GITLAB'
        config.scm.gitlab.url = 'https://testGitLab.com/testgroup/'
        createArgoCD().install()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        clusterRessourcesYaml['spec']['sourceRepos']

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'https://testGitLab.com/testgroup/3rd-party-dependencies/kube-prometheus-stack.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/mailhog.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/ingress-nginx.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/external-secrets.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/vault.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/cert-manager.git'
        )
    }


    @Test
    void 'If using mirror with GitLab with prefix, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true
        config.scm.scmProviderType = 'GITLAB'
        config.application.namePrefix = 'test1-'

        createArgoCD().install()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        clusterRessourcesYaml['spec']['sourceRepos']

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'https://testGitLab.com/testgroup/3rd-party-dependencies/kube-prometheus-stack.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/mailhog.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/ingress-nginx.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/external-secrets.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/vault.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/cert-manager.git'
        )

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/mailhog',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/ingress-nginx',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager'
        )
    }

    @Test
    void 'If using mirror with name-prefix, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true
        config.application.namePrefix = 'test1-'

        createArgoCD().install()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/cluster-resources.yaml')
        clusterRessourcesYaml['spec']['sourceRepos']


        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/mailhog',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/ingress-nginx',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager'
        )

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/kube-prometheus-stack.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/mailhog.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/ingress-nginx.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/external-secrets.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/vault.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/cert-manager.git'
        )
    }

    void setupDedicatedInstanceMode() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.scmManager.url = 'scmm.testhost/scm'
        config.multiTenant.scmManager.username = 'testUserName'
        config.multiTenant.scmManager.password = 'testPassword'
        config.multiTenant.useDedicatedInstance = true
        this.argocd = setupOperatorTest()
        argocd.install()
    }

    protected ArgoCD setupOperatorTest(Map options = [:]) {
        config.features.argocd.operator = true
        config.features.argocd.resourceInclusionsCluster = 'https://192.168.0.1:6443'
        config.application.openshift = options.openshift ?: false

        def argoCD = createArgoCD()

        if (config.multiTenant.useDedicatedInstance) {
            config.content.examples ? setupMockResponsesFor(MockReponses.MULTI_TENANT_WITH_EXAMPLES) : setupMockResponsesFor(MockReponses.MULTI_TENANT)
        } else {
            setupMockResponsesFor(MockReponses.SINGLE_TENANT)
        }

        return argoCD
    }

    enum MockReponses {
        SINGLE_TENANT,
        MULTI_TENANT,
        MULTI_TENANT_WITH_EXAMPLES
    }

    //Mock Responses for Testing
    void setupMockResponsesFor(MockReponses mockReponses) {
        switch (mockReponses) {
            case MockReponses.SINGLE_TENANT -> {
                k8sCommands.enqueueOutputs([
                        queueUpAllNamespacesExist(),
                        new CommandExecutor.Output('', '', 0), // Monitoring CRDs applied
                        new CommandExecutor.Output('', '', 0), // ArgoCD Secret applied
                        new CommandExecutor.Output('', '', 0), // Labeling ArgoCD Secret
                        new CommandExecutor.Output('', '', 0), // ArgoCD operator YAML applied
                        new CommandExecutor.Output('', 'Available', 0), // ArgoCD resource reached desired phase
                ].flatten() as Queue<CommandExecutor.Output>)
            }
            case MockReponses.MULTI_TENANT_WITH_EXAMPLES -> mockReponseMultiTenant()
            case MockReponses.MULTI_TENANT -> mockReponseMultiTenant()
        }
    }

    void mockReponseMultiTenant() {
        k8sCommands.enqueueOutputs([
                queueUpAllNamespacesExist(),
                new CommandExecutor.Output('', '', 0), // Monitoring CRDs applied

                new CommandExecutor.Output('', '', 0), // ArgoCD SCM Secret applied
                new CommandExecutor.Output('', '', 0), // Labeling ArgoCD SCM Secret
                new CommandExecutor.Output('', '', 0), // ArgoCD SCM central Secret applied
                new CommandExecutor.Output('', '', 0), // Labeling ArgoCD central SCM Secret

                new CommandExecutor.Output('', '', 0), // ArgoCD operator YAML applied
                new CommandExecutor.Output('', 'Available', 0), // ArgoCD resource reached desired phase

                new CommandExecutor.Output('', '', 0), // ArgoCD argocd-cluster password secret
                new CommandExecutor.Output('', '', 0), // ArgoCD argocd-secret

                new CommandExecutor.Output('', '', 0), // argocd-default-cluster-config patched
                new CommandExecutor.Output('', '', 0), // ArgoCD argocd-secret
                new CommandExecutor.Output('', 'dGVzdG5hbWVzcGFjZTEsdGVzdG5hbWVzcGFjZTI=', 0), // getting argocd-default-cluster-config from central Argocd
                new CommandExecutor.Output('', '', 0), // setting argocd-default-cluster-config from central Argocd
        ].flatten() as Queue<CommandExecutor.Output>)
    }

    private void simulateNamespaceCreation() {
        Queue<CommandExecutor.Output> outputs = new LinkedList<CommandExecutor.Output>()
        config.application.namespaces.getActiveNamespaces().each { namespace ->
            outputs.add(new CommandExecutor.Output("${namespace} not found", "", 1))
            outputs.add(new CommandExecutor.Output("${namespace} created", "", 0))
        }
        k8sCommands.enqueueOutputs(outputs)
    }

    private Queue<CommandExecutor.Output> queueUpAllNamespacesExist() {
        return new LinkedList<CommandExecutor.Output>(
                config.application.namespaces.getActiveNamespaces().collect { namespace -> new CommandExecutor.Output(namespace, "", 0) }
        )
    }

    private static void mockPrefixActiveNamespaces(Config config) {
        def prefix = config.application.namePrefix ?: ""

        config.application.namespaces.with {
            dedicatedNamespaces = new LinkedHashSet<>(
                    dedicatedNamespaces.collect { (prefix + it).toString() }
            )
            tenantNamespaces = new LinkedHashSet<>(
                    tenantNamespaces.collect { (prefix + it).toString() }
            )
        }
    }


    class ArgoCDForTest extends ArgoCD {
        ScmManagerMock tenantMock

       // Convenience ctor: create the mock in the arg list (no pre-super statements)
        ArgoCDForTest(Config config, CommandExecutorForTest k8sCommands, CommandExecutorForTest helmCommands) {
            this(
                    config,
                    k8sCommands,
                    helmCommands,
                    new ScmManagerMock(
                            inClusterBase: new URI("http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm"),
                            namePrefix: config.application.namePrefix
                    )
            )
        }

        // Real ctor: can use the mock AFTER super(...)
        ArgoCDForTest(Config config, CommandExecutorForTest k8sCommands, CommandExecutorForTest helmCommands,
                      ScmManagerMock tm) {
            super(
                    config,
                    new K8sClientForTest(config, k8sCommands),
                    new HelmClient(helmCommands),
                    new FileSystemUtils(),
                    new TestGitRepoFactory(config, new FileSystemUtils()),
                    new GitHandlerForTests(config, tm)
            )
            this.tenantMock = tm
            mockPrefixActiveNamespaces(config)
        }

        @Override
        protected initCentralRepos() {
            super.initCentralRepos()
            if (config.multiTenant.useDedicatedInstance) {
                argocdRepo = argocdRepoInitializationAction.repo
                clusterResourcesRepo = clusterResourcesInitializationAction.repo
            }
        }

        @Override
        protected initTenantRepos() {
            super.initTenantRepos()

            if (config.multiTenant.useDedicatedInstance) {
                tenantBootstrap = tenantBootstrapInitializationAction.repo
            } else {
                argocdRepo = argocdRepoInitializationAction.repo
                actualHelmValuesFile = Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), HELM_VALUES_PATH)
                clusterResourcesRepo = clusterResourcesInitializationAction.repo
            }
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