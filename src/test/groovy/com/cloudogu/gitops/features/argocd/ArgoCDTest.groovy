package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.TestGitProvider
import com.cloudogu.gitops.utils.git.TestGitRepoFactory
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.*
import groovy.io.FileType
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Spy
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable
import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.fail
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode


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
                            internal: true],
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
            content: [
                    examples: true,
                    variables: [
                            images: [
                                    buildImages + [petclinic: 'petclinic-value']
                            ]
                    ]
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
//    GitRepo argocdRepo
    String actualHelmValuesFile
    GitRepo clusterResourcesRepo
    List<GitRepo> petClinicRepos = []
    String prefixPathCentral = '/multiTenant/central/'
    ArgoCD argocd
    ArgoCDRepoLayout repoLayout

    @Test
    void 'Installs argoCD'() {
        // Simulate argocd Namespace does not exist
        k8sCommands.enqueueOutput(new CommandExecutor.Output('namespace not found', '', 1))

        def argocd = createArgoCD()
        argocd.install()
        this.clusterResourcesRepo = (argocd as ArgoCDForTest).clusterResourcesRepo

        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"


        k8sCommands.assertExecuted('kubectl create namespace argocd')

        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(
                new File(repoLayout.rootDir()),
                clusterResourcesRepo.gitProvider.url
        )
        assertThat(filesWithInternalSCMM).isNotEmpty()
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('ClusterIP')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['argocdUrl']).isNull()

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['crds']).isNull()
        assertThat(parseActualYaml(actualHelmValuesFile)['global']).isNull()

        // check repoTemplateSecretName
        k8sCommands.assertExecuted('kubectl create secret generic argocd-repo-creds-scm -n argocd')
        k8sCommands.assertExecuted('kubectl label secret argocd-repo-creds-scm -n argocd')

        // Check dependency build and helm install (Chart liegt jetzt unter argocd/argocd)
        assertThat(helmCommands.actualCommands[0].trim())
                .isEqualTo('helm repo add argo https://argoproj.github.io/argo-helm')
        assertThat(helmCommands.actualCommands[1].trim())
                .isEqualTo("helm dependency build ${repoLayout.helmDir()}")
        assertThat(helmCommands.actualCommands[2].trim())
                .isEqualTo("helm upgrade -i argocd ${repoLayout.helmDir()} --create-namespace --namespace argocd")

        // Check patched PW
        def patchCommand = k8sCommands.assertExecuted('kubectl patch secret argocd-secret -n argocd')
        String patchFile = (patchCommand =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
        assertThat(BCrypt.checkpw(config.application.password as String,
                parseActualYaml(patchFile)['stringData']['admin.password'] as String))
                .as("Password hash missmatch").isTrue()

        // Check bootstrapping (liegt jetzt unter argocd/projects und argocd/applications)
        k8sCommands.assertExecuted("kubectl apply -f ${Path.of(repoLayout.projectsDir(), 'argocd.yaml')}")
        k8sCommands.assertExecuted("kubectl apply -f ${Path.of(repoLayout.applicationsDir(), 'bootstrap.yaml')}")

        def deleteCommand = k8sCommands.assertExecuted('kubectl delete secret -n argocd')
        assertThat(deleteCommand).contains('owner=helm', 'name=argocd')

        // Operator disabled -> operator Ordner sollte fehlen
        assertThat(Path.of(repoLayout.operatorConfigFile()).toFile()).doesNotExist()
        assertThat(Path.of(repoLayout.operatorRbacDir()).toFile()).doesNotExist()

        // Projects (jetzt unter argocd/projects)
        def clusterRessourcesYaml = new YamlSlurper().parse(
                Path.of(repoLayout.projectsDir(), 'cluster-resources.yaml')
        )
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains(
                'https://prometheus-community.github.io/helm-charts'
        )
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain(
                'http://scmm-scm-manager.default.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack'
        )

        // Applications (jetzt unter argocd/applications)
        def argocdYaml = new YamlSlurper().parse(
                Path.of(repoLayout.applicationsDir(), 'argocd.yaml')
        )
        assertThat(argocdYaml['spec']['source']['directory']).isNull()

        // Neuer Pfad: Chart liegt unter argocd/argocd (nicht mehr nur argocd/)
        assertThat(argocdYaml['spec']['source']['path'] as String)
                .isIn('argocd/argocd', 'argocd/argocd/')
    }


    @Test
    void 'Installs argoCD for remote and external Scmm'() {
        config.application.remote = true
        config.scm.scmManager.internal = false
        config.scm.scmManager.url = "https://abc"
        config.features.argocd.url = 'https://argo.cd'
        String scmmUrlInternal = "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm"

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(
                new File(repoLayout.rootDir()),
                scmmUrlInternal
        )
        assertThat(filesWithInternalSCMM).isEmpty()
        List filesWithExternalSCMM = findFilesContaining(new File(repoLayout.rootDir()), "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()

        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"
        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['server']['service']['type']).isEqualTo('LoadBalancer')
        assertThat(valuesYaml['argo-cd']['notifications']['argocdUrl']).isEqualTo('https://argo.cd')
        assertThat(valuesYaml['argo-cd']['server']['ingress']['enabled']).isEqualTo(true)
        assertThat(valuesYaml['argo-cd']['server']['ingress']['hostname']).isEqualTo('argo.cd')
    }

    @Test
    void 'When monitoring disabled: Does not push path monitoring to cluster resources'() {
        config.features.monitoring.active = false

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        assertThat(new File(repoLayout.monitoringDir())).doesNotExist()
    }

    @Test
    void 'When monitoring enabled: Does push path monitoring to cluster resources'() {
        config.features.monitoring.active = true

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        assertThat(new File(repoLayout.monitoringDir())).exists()
        assertValidDashboards(repoLayout.monitoringDir())
    }

    void assertValidDashboards(String monitoringPath) {
        Files.walk(Path.of(monitoringPath))
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

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        assertThat(new File(repoLayout.monitoringDir())).exists()
        assertThat(new File(repoLayout.monitoringDir() + "/misc/dashboard/ingress-nginx-dashboard.yaml")).doesNotExist()
        assertThat(new File(repoLayout.monitoringDir() + "/misc/dashboard/ingress-nginx-dashboard-requests-handling.yaml")).doesNotExist()
    }

    @Test
    void 'When mailhog disabled: Does not include mail configurations into cluster resources'() {
        config.features.mail.active = false
        config.features.mail.mailhog = false

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"

        def valuesYaml = parseActualYaml(actualHelmValuesFile)
        assertThat(valuesYaml['argo-cd']['notifications']['enabled']).isEqualTo(false)
        assertThat(valuesYaml['argo-cd']['notifications']['notifiers']).isNull()
    }

    @Test
    void 'When mailhog enabled: Includes mail configurations into cluster resources'() {
        config.features.mail.active = true

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"
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

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"
        def valuesYaml = parseActualYaml(actualHelmValuesFile)

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), "cluster-resources.yaml")
        def argocdYaml = new YamlSlurper().parse(Path.of repoLayout.applicationsDir(), 'argocd.yaml')
        def defaultYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), 'default.yaml')

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['from']).isEqualTo("argocd@example.com")
        assertThat(clusterRessourcesYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('argocd@example.com')
        assertThat(argocdYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.on-sync-status-unknown.email']).isEqualTo('argocd@example.com')
        assertThat(defaultYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('argocd@example.com')
    }

    @Test
    void 'When emailaddress is NOT set: Use default email addresses in configurations'() {
        config.features.mail.active = true

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"
        def valuesYaml = parseActualYaml(actualHelmValuesFile)

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), "cluster-resources.yaml")
        def argocdYaml = new YamlSlurper().parse(Path.of repoLayout.applicationsDir(), 'argocd.yaml')
        def defaultYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), 'default.yaml')

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['from']).isEqualTo("argocd@example.org")
        assertThat(clusterRessourcesYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('infra@example.org')
        assertThat(argocdYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.on-sync-status-unknown.email']).isEqualTo('infra@example.org')
        assertThat(defaultYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('infra@example.org')
    }

    @Test
    void 'When external Mailserver is set'() {
        config.features.mail.active = true
        config.features.mail.smtpAddress = 'smtp.example.com'
        config.features.mail.smtpPort = 1010110
        config.features.mail.smtpUser = 'argo@example.com'
        config.features.mail.smtpPassword = '1101:ABCabc&/+*~'

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"

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

        def argocd = createArgoCD()
        argocd.install()

        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"

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

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"
        def valuesYaml = parseActualYaml(actualHelmValuesFile)

        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['host']) doesNotHaveToString('mailhog.*monitoring.svc.cluster.local')
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)['port']).isEqualTo(1025)
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)) doesNotHaveToString('username')
        assertThat(new YamlSlurper().parseText(valuesYaml['argo-cd']['notifications']['notifiers']['service.email'] as String)) doesNotHaveToString('password')
    }

    @Test
    void 'When vault disabled: Does not push path "secrets" to cluster resources'() {
        config.features.secrets.active = false

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        assertThat(new File(repoLayout.vaultDir())).doesNotExist()
    }

    @Test
    void 'Prepares repos for air-gapped mode'() {
        config.features.monitoring.active = false
        config.application.mirrorRepos = true

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), "cluster-resources.yaml")

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
    void 'Pushes repos with empty name-prefix'() {
        def argocd = createArgoCD()
        argocd.install()
        this.clusterResourcesRepo = (argocd as ArgoCDForTest).clusterResourcesRepo
        repoLayout = argocd.repoLayout()


        assertArgoCdYamlPrefixes(clusterResourcesRepo.gitProvider.url, '', repoLayout)
    }

    @Test
    void 'Creates Jenkinsfiles for two registries'() {
        config.registry.twoRegistries = true
        createArgoCD().install()

        assertJenkinsfileRegistryCredentials()
    }

    @Test
    void 'Pushes repos with name-prefix'() {
        config.application.namePrefix = 'abc-'

        def argocd = createArgoCD()
        argocd.install()
        this.clusterResourcesRepo = (argocd as ArgoCDForTest).clusterResourcesRepo
        repoLayout = argocd.repoLayout()

        assertArgoCdYamlPrefixes(clusterResourcesRepo.gitProvider.url, config.application.namePrefix, repoLayout)
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

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"

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
    void 'ArgoCD with active network policies'() {
        config.application.netpols = true

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()
        this.actualHelmValuesFile = "${repoLayout.helmDir()}/values.yaml"

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['global']['networkPolicy']['create']).isEqualTo(true)
        assertThat(new File(repoLayout.argocdRoot(), '/argocd/values.yaml').text.contains("namespace: monitoring"))
        assertThat(new File(repoLayout.argocdRoot(), '/argocd/templates/allow-namespaces.yaml').text.contains("namespace: monitoring"))
        assertThat(new File(repoLayout.argocdRoot(), '/argocd/templates/allow-namespaces.yaml').text.contains("namespace: default"))
    }

    private void assertArgoCdYamlPrefixes(String scmmUrl, String expectedPrefix, ArgoCDRepoLayout repoLayout) {

        assertAllYamlFiles(new File(repoLayout.argocdRoot()), 'projects', 3) { Path file ->
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

        assertAllYamlFiles(new File(repoLayout.argocdRoot()), 'applications', 3) { Path file ->
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

        assertAllYamlFiles(new File(repoLayout.rootDir()), 'apps', 7) { Path it ->
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
        def argoCD = ArgoCDForTest.newWithAutoProviders(config, k8sCommands, helmCommands)
        return argoCD
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
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
        def rbacConfigPath = Path.of(repoLayout.operatorRbacDir())

        assertThat(argocdConfigPath.toFile()).exists()
        assertThat(rbacConfigPath.toFile()).exists()

        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())
        assertThat(yaml['apiVersion']).isEqualTo('argoproj.io/v1beta1')
        assertThat(yaml['kind']).isEqualTo('ArgoCD')
    }

    @Test
    void 'No files for operator when operator is false'() {
        def argocd = createArgoCD()

        argocd.install()
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
        def rbacConfigPath = Path.of(repoLayout.operatorRbacDir())

        assertThat(argocdConfigPath.toFile()).doesNotExist()
        assertThat(rbacConfigPath.toFile()).doesNotExist()
    }

    @Test
    void 'Deploys with operator without OpenShift configuration'() {
        def argocd = setupOperatorTest(openshift: false)

        argocd.install()
        repoLayout = argocd.repoLayout()
        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())

        k8sCommands.assertExecuted("kubectl apply -f ${argocdConfigPath}")

        def yaml = parseActualYaml(argocdConfigPath.toFile().toString())
        assertThat(yaml['spec']['rbac']).isNull()
        assertThat(yaml['spec']['sso']).isNull()

        def argocdYaml = new YamlSlurper().parse(Path.of repoLayout.applicationsDir(), 'argocd.yaml')
        assertThat(argocdYaml['spec']['source']['directory']['recurse'] as Boolean).isTrue()
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('argocd/operator/')
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

        def argocd = setupOperatorTest(openshift: false)
        argocd.install()
        repoLayout = argocd.repoLayout()

        File rbacPath = Path.of(repoLayout.operatorRbacDir()).toFile()

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
        def argocd = setupOperatorTest(openshift: true)

        argocd.install()
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
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
        def argocd = setupOperatorTest()

        // Set the config to a custom resourceInclusionsCluster value
        config.features.argocd.resourceInclusionsCluster = 'https://192.168.0.1:6443'

        argocd.install()
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
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
        def argocd = setupOperatorTest()

        // Set the config to a custom internalKubernetesApiUrl value
        config.application.internalKubernetesApiUrl = 'https://192.168.0.1:6443'

        // Set environment variables for Kubernetes API server
        withEnvironmentVariable("KUBERNETES_SERVICE_HOST", "100.125.0.1")
                .and("KUBERNETES_SERVICE_PORT", "443")
                .execute {
                    argocd.install()
                }

        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
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
        def argocd = setupOperatorTest()

        // Set environment variables for ArgoCD
        config.features.argocd.env = [
                [name: "ENV_VAR_1", value: "value1"],
                [name: "ENV_VAR_2", value: "value2"]
        ] as List<Map>

        argocd.install()
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
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
        def argocd = setupOperatorTest()

        // Ensure env is an empty list (default)
        config.features.argocd.env = []

        argocd.install()
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
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
        def argocd = setupOperatorTest()

        // Set a single environment variable for ArgoCD
        config.features.argocd.env = [
                [name: "ENV_VAR_SINGLE", value: "singleValue"]
        ] as List<Map>

        argocd.install()
        repoLayout = argocd.repoLayout()

        def argocdConfigPath = Path.of(repoLayout.operatorConfigFile())
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
        def argocd = setupOperatorTest()
        argocd.install()
        repoLayout = argocd.repoLayout()

        def yaml = parseActualYaml(Path.of(repoLayout.operatorConfigFile()).toString())
        assertThat(yaml['spec']['server']['insecure']).isEqualTo(true)
    }

    @Test
    void 'Operator config sets server_insecure to false when insecure is not set'() {
        def argocd = setupOperatorTest()
        argocd.install()
        repoLayout = argocd.repoLayout()

        def yaml = parseActualYaml(Path.of(repoLayout.operatorConfigFile()).toString())
        assertThat(yaml['spec']['server']['insecure']).isEqualTo(false)
    }

    @Test
    void 'Generates correct ingress yaml with expected host when insecure is true and not on OpenShift'() {
        config.application.insecure = true
        config.features.argocd.url = "http://argocd.localhost"
        def argocd = setupOperatorTest(openshift: false)
        argocd.install()
        repoLayout = argocd.repoLayout()

        def ingressFile = new File(repoLayout.operatorDir(), "ingress.yaml")
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
        def argocd = setupOperatorTest(openshift: false)
        argocd.install()
        repoLayout = argocd.repoLayout()

        def ingressFile = new File(repoLayout.operatorDir(), "ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated when insecure is false")
                .doesNotExist()
    }

    @Test
    void 'Does not generate ingress yaml when running on OpenShift'() {
        config.application.insecure = true
        def argocd = setupOperatorTest(openshift: true)
        argocd.install()
        repoLayout = argocd.repoLayout()

        def ingressFile = new File(repoLayout.operatorDir(), "ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated on OpenShift")
                .doesNotExist()
    }

    @Test
    void 'Does not generate ingress yaml when insecure is false and OpenShift is true'() {
        config.application.insecure = false
        def argocd = setupOperatorTest(openshift: true)
        argocd.install()
        repoLayout = argocd.repoLayout()

        def ingressFile = new File(repoLayout.operatorDir(), "ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated when both flags are false")
                .doesNotExist()
    }

    @Test
    void 'Central Bootstrapping for Tenant Applications'() {
        setupDedicatedInstanceMode()

        def ingressFile = new File(repoLayout.operatorDir(), "ingress.yaml")
        assertThat(ingressFile)
                .as("Ingress file should not be generated when both flags are false")
                .doesNotExist()
    }

    @Test
    void 'GOP DedicatedInstances Central templating works correctly'() {
        setupDedicatedInstanceMode()
        //Central Applications
        assertThat(new File(repoLayout.argocdRoot() + "${prefixPathCentral}applications/argocd.yaml")).exists()
        assertThat(new File(repoLayout.argocdRoot() + "${prefixPathCentral}applications/bootstrap.yaml")).exists()
        assertThat(new File(repoLayout.argocdRoot() + "${prefixPathCentral}applications/projects.yaml")).exists()
        assertThat(new File(repoLayout.argocdRoot() + "${prefixPathCentral}applications/example-apps.yaml")).doesNotExist()

        def argocdYaml = new YamlSlurper().parse(Path.of repoLayout.argocdRoot(), "${prefixPathCentral}applications/argocd.yaml")
        def bootstrapYaml = new YamlSlurper().parse(Path.of repoLayout.argocdRoot(), "${prefixPathCentral}applications/bootstrap.yaml")
        def clusterResourcesYaml = new YamlSlurper().parse(Path.of repoLayout.argocdRoot(), "${prefixPathCentral}applications/cluster-resources.yaml")
        def projectsYaml = new YamlSlurper().parse(Path.of repoLayout.argocdRoot(), "${prefixPathCentral}applications/projects.yaml")

        assertThat(argocdYaml['metadata']['name']).isEqualTo('testPrefix-argocd')
        assertThat(argocdYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(argocdYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('argocd/operator/')

        assertThat(bootstrapYaml['metadata']['name']).isEqualTo('testPrefix-bootstrap')
        assertThat(bootstrapYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(bootstrapYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(bootstrapYaml['spec']['source']['repoURL']).isEqualTo("scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources.git")

        assertThat(clusterResourcesYaml['metadata']['name']).isEqualTo('testPrefix-cluster-resources')
        assertThat(clusterResourcesYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(clusterResourcesYaml['spec']['project']).isEqualTo('testPrefix')

        assertThat(projectsYaml['metadata']['name']).isEqualTo('testPrefix-projects')
        assertThat(projectsYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(projectsYaml['spec']['project']).isEqualTo('testPrefix')

        //Central Project
        assertThat(new File(repoLayout.argocdRoot() + "${prefixPathCentral}projects/tenant.yaml")).exists()

        def tenantProject = new YamlSlurper().parse(Path.of repoLayout.argocdRoot(), "${prefixPathCentral}projects/tenant.yaml")

        assertThat(tenantProject['metadata']['name']).isEqualTo('testPrefix')
        assertThat(tenantProject['metadata']['namespace']).isEqualTo('argocd')
        def sourceRepos = (List<String>) tenantProject['spec']['sourceRepos']
        assertThat(sourceRepos[0]).isEqualTo('scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources.git')
    }

    @Test
    void 'Append namespaces to Argocd argocd-default-cluster-config secrets'() {
        config.application.namespaces.dedicatedNamespaces = new LinkedHashSet(['dedi-test1', 'dedi-test2', 'dedi-test3'])
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['tenant-test1', 'tenant-test2', 'tenant-test3'])
        setupDedicatedInstanceMode()
        k8sCommands.assertExecuted('kubectl get secret argocd-default-cluster-config -n argocd -ojsonpath={.data.namespaces}')
        k8sCommands.assertExecuted('kubectl patch secret argocd-default-cluster-config -n argocd --patch-file=/tmp/gitops-playground-patch-')
    }

    @Test
    void 'multiTenant folder gets deleted correctly if not in dedicated mode'() {
        config.multiTenant.useDedicatedInstance = false

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        assertThat(Path.of(repoLayout.argocdRoot(), 'multiTenant/')).doesNotExist()
        assertThat(Path.of(repoLayout.argocdRoot(), 'applications/')).exists()
        assertThat(Path.of(repoLayout.argocdRoot(), 'projects/')).exists()
    }

    @Test
    void 'deleting unused folder in dedicated mode'() {
        config.multiTenant.useDedicatedInstance = true

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        assertThat(Path.of(repoLayout.argocdRoot(), 'multiTenant/')).exists()
        assertThat(Path.of(repoLayout.argocdRoot(), 'applications/')).doesNotExist()
        assertThat(Path.of(repoLayout.argocdRoot(), 'projects/')).doesNotExist()
    }

    @Test
    void 'RBACs generated correctly'() {
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['testprefix-tenant-test1', 'testprefix-tenant-test2', 'testprefix-tenant-test3'])
        setupDedicatedInstanceMode()

        File rbacFolder = new File(repoLayout.operatorRbacDir())
        File rbacTenantFolder = new File(repoLayout.operatorRbacDir() + "/tenant")
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

        def argocd = setupOperatorTest(openshift: false)
        argocd.install()
        repoLayout = argocd.repoLayout()

        print config.toMap()

        File rbacDir = Path.of(repoLayout.operatorRbacDir()).toFile()
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

        def argocd = setupOperatorTest(openshift: true)
        argocd.install()
        repoLayout = argocd.repoLayout()

        File rbacDir = Path.of(repoLayout.operatorRbacDir()).toFile()
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

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), '/cluster-resources.yaml')
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

        def argocd = createArgoCD()
        argocd.install()

        repoLayout = argocd.repoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), '/cluster-resources.yaml')
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
        config.scm.gitlab.url = 'https://testGitLab.com/testgroup'
        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), '/cluster-resources.yaml')
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
        config.scm.gitlab.url = "https://testGitLab.com/testgroup"
        config.application.namePrefix = 'test1-'

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), '/cluster-resources.yaml')
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

        def argocd = createArgoCD()
        argocd.install()
        repoLayout = argocd.repoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of repoLayout.projectsDir(), '/cluster-resources.yaml')
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
        this.clusterResourcesRepo = (argocd as ArgoCDForTest).clusterResourcesRepo
        repoLayout = argocd.repoLayout()

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


    static class ArgoCDForTest extends ArgoCD {
        final Config cfg
        final GitProvider tenantProvider
        final GitProvider centralProvider

        static ArgoCDForTest newWithAutoProviders(Config cfg,
                                                  CommandExecutorForTest k8sCommands,
                                                  CommandExecutorForTest helmCommands) {
            def prov = TestGitProvider.buildProviders(cfg)
            return new ArgoCDForTest(
                    cfg,
                    k8sCommands,
                    helmCommands,
                    prov.tenant as GitProvider,
                    prov.central as GitProvider
            )
        }

        ArgoCDForTest(Config cfg,
                      CommandExecutorForTest k8sCommands,
                      CommandExecutorForTest helmCommands,
                      GitProvider tenantProvider,
                      GitProvider centralProvider) {
            super(
                    cfg,
                    new K8sClientForTest(cfg, k8sCommands),
                    new HelmClient(helmCommands),
                    new FileSystemUtils(),
                    new TestGitRepoFactory(cfg, new FileSystemUtils()),
                    new GitHandlerForTests(cfg, tenantProvider, centralProvider)
            )
            this.cfg = cfg
            this.tenantProvider = tenantProvider
            this.centralProvider = centralProvider
            mockPrefixActiveNamespaces(cfg)
        }

        GitRepo getClusterResourcesRepo() {
            return this.clusterResourcesInitializationAction?.repo
        }

        GitRepo getTenantBootstrapRepo() {
            return this.tenantBootstrapInitializationAction?.repo
        }

        ArgoCDRepoLayout getClusterRepoLayout() {
            def root = getClusterResourcesRepo()?.getAbsoluteLocalRepoTmpDir()
            return root ? new ArgoCDRepoLayout(root.toString()) : null
        }

        ArgoCDRepoLayout getTenantRepoLayout() {
            def root = getTenantBootstrapRepo()?.getAbsoluteLocalRepoTmpDir()
            return root ? new ArgoCDRepoLayout(root.toString()) : null
        }

        // Convenience: argocd-Unterordner im cluster-resources Repo
        String getArgocdDirInClusterResources() {
            return getClusterRepoLayout()?.argocdRoot()
        }
    }



    private Map parseActualYaml(String pathToYamlFile) {
        File yamlFile = new File(pathToYamlFile)
        def ys = new YamlSlurper()
        return ys.parse(yamlFile) as Map
    }

}