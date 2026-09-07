package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.K8sClientForTest
import groovy.io.FileType
import groovy.yaml.YamlSlurper
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.*

@EnableKubernetesMockClient(crud = true)
class ArgoCDTest {
    Map buildImages = [kubectl    : 'kubectl-value',
                       helm       : 'helm-value',
                       kubeval    : 'kubeval-value',
                       helmKubeval: 'helmKubeval-value',
                       yamllint   : 'yamllint-value']

    Config config = Config.fromMap(application: [openshift           : false,
                                                 insecure            : false,
                                                 password            : '123',
                                                 username            : 'something',
                                                 namePrefix          : '',
                                                 namePrefixForEnvVars: '',
                                                 gitName             : 'Cloudogu',
                                                 gitEmail            : 'hello@cloudogu.com',
                                                 namespaces          : [dedicatedNamespaces: ['argocd', 'monitoring', 'traefik', 'secrets'],
                                                                        tenantNamespaces   : ['example-apps-staging', 'example-apps-production']]],
            scm: [scmManager: [internal: true],
                  gitlab    : [url: '']],
            multiTenant: [scmManager            : [url: ''],
                          gitlab                : [url: ''],
                          useDedicatedInstance  : false,
                          centralArgocdNamespace: 'argocd'],
            content: [repos     : [[url          : 'https://github.com/cloudogu/gitops-build-lib',
                                    target       : '3rd-party-dependencies/gitops-build-lib',
                                    overwriteMode: 'RESET'],
                                   [url          : 'https://github.com/cloudogu/ces-build-lib',
                                    target       : '3rd-party-dependencies/ces-build-lib',
                                    overwriteMode: 'RESET'],
                                   [url          : 'https://github.com/cloudogu/spring-boot-helm-chart',
                                    target       : '3rd-party-dependencies/spring-boot-helm-chart',
                                    overwriteMode: 'RESET'],
                                   [url             : 'https://github.com/cloudogu/spring-petclinic',
                                    target          : 'argocd/petclinic-plain',
                                    ref             : 'feature/gitops_ready',
                                    targetRef       : 'main',
                                    overwriteMode   : 'UPGRADE',
                                    createJenkinsJob: true],
                                   [url             : 'https://github.com/cloudogu/spring-petclinic',
                                    target          : 'argocd/petclinic-helm',
                                    ref             : 'feature/gitops_ready',
                                    targetRef       : 'main',
                                    overwriteMode   : 'UPGRADE',
                                    createJenkinsJob: true],
                                   [url          : 'https://github.com/cloudogu/gitops-playground',
                                    path         : 'example-apps-via-content-loader/',
                                    ref          : 'main',
                                    templating   : true,
                                    type         : 'FOLDER_BASED',
                                    overwriteMode: 'UPGRADE']],
                      namespaces: ['example-apps-production',
                                   'example-apps-staging'],
                      variables : [petclinic: [baseDomain: 'petclinic.localhost'],
                                   images   : [kubectl    : 'alpine/kubectl:1.35.0',
                                               helm       : 'ghcr.io/cloudogu/helm:4.2.1-1',
                                               kubeval    : 'ghcr.io/cloudogu/helm:4.2.1-1',
                                               helmKubeval: 'ghcr.io/cloudogu/helm:4.2.1-1',
                                               yamllint   : 'cytopia/yamllint:1.25-0.7',
                                               petclinic  : 'eclipse-temurin:17-jre-alpine',
                                               maven      : '']]],
            features: [argocd    : [operator                 : false,
                                    active                   : true,
                                    configOnly               : true,
                                    emailFrom                : 'argocd@example.org',
                                    emailToUser              : 'app-team@example.org',
                                    emailToAdmin             : 'infra@example.org',
                                    resourceInclusionsCluster: ''],
                       monitoring: [active: true,
                                    helm  : [chart  : 'kube-prometheus-stack',
                                             version: '42.0.3']],
                       ingress   : [active: true],
                       secrets   : [active: true]])

    KubernetesClient client
    K8sClient k8sClient

    CommandExecutorForTest helmCommands = new CommandExecutorForTest()

    String actualHelmValuesFile
    GitRepo clusterResourcesRepo
    List<GitRepo> petClinicRepos = []
    ArgoCD argocd
    ArgoCDRepoLayout clusterResourcesRepoLayout
    RepositoryWorkspace repositoryWorkspace

    @BeforeEach
    void setupKubernetesClient() {
        k8sClient = spy(new K8sClientForTest())
        k8sClient.client = client
        k8sClient.sleepTimeMillis = 1
        k8sClient.defaultRetries = 1

        // no need to wait in tests, we stub!
        doNothing().when(k8sClient).waitForResourcePhase(any(String),
                any(String),
                any(String),
                any(String))
    }


    @Test
    void 'Installs argoCD'() {
        // Simulate argocd Namespace does not exist

        def argocd = createArgoCD()
        execute(argocd)
        this.clusterResourcesRepo = (argocd as ArgoCDForTest).clusterResourcesRepo

        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()
        this.actualHelmValuesFile = "${clusterResourcesRepoLayout.helmDir()}/values.yaml"

        assertThat(client.namespaces().withName('argocd').get()).isNotNull()

        // check values.yaml
        List filesWithInternalSCMM = findFilesContaining(new File(clusterResourcesRepoLayout.rootDir()),
                clusterResourcesRepo.gitProvider.url)
        assertThat(filesWithInternalSCMM).isNotEmpty()
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['server']['service']['type'])
                .isEqualTo('ClusterIP')
        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['notifications']['argocdUrl']).isNull()

        assertThat(parseActualYaml(actualHelmValuesFile)['argo-cd']['crds']).isNull()
        assertThat(parseActualYaml(actualHelmValuesFile)['global']).isNull()

        Secret repoCredentialsSecret = client.secrets()
                .inNamespace('argocd')
                .withName('argocd-repo-creds-scm')
                .get()

        assertThat(repoCredentialsSecret).isNotNull()
        assertThat(repoCredentialsSecret.metadata.labels['argocd.argoproj.io/secret-type']).isEqualTo('repo-creds')

        // Check dependency build and helm install (Chart liegt jetzt unter apps/argocd/argocd)
        assertThat(helmCommands.actualCommands[0].trim())
                .isEqualTo('helm repo add argo https://argoproj.github.io/argo-helm')
        assertThat(helmCommands.actualCommands[1].trim())
                .isEqualTo("helm dependency build ${clusterResourcesRepoLayout.helmDir()}".toString())
        assertThat(helmCommands.actualCommands[2].trim())
                .isEqualTo("helm upgrade -i argocd ${clusterResourcesRepoLayout.helmDir()} --create-namespace --namespace argocd".toString())

        Secret argocdSecret = client.secrets()
                .inNamespace('argocd')
                .withName('argocd-secret')
                .get()

        assertThat(argocdSecret).isNotNull()

        String patchedPasswordHash = decodedSecretValue(argocdSecret, 'admin.password')

        assertThat(BCrypt.checkpw(config.application.password as String, patchedPasswordHash))
                .as('Password hash mismatch')
                .isTrue()

        assertThat(client.secrets()
                .inNamespace('argocd')
                .withLabels([owner: 'helm', name: 'argocd'])
                .list()
                .items).isEmpty()

        // Operator disabled -> operator Ordner sollte fehlen
        assertThat(Path.of(clusterResourcesRepoLayout.operatorConfigFile()).toFile()).doesNotExist()
        assertThat(Path.of(clusterResourcesRepoLayout.operatorRbacDir()).toFile()).doesNotExist()

        // Projects (jetzt unter argocd/projects)
        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.projectsDir(), 'cluster-resources.yaml'))
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains('https://prometheus-community.github.io/helm-charts')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain('http://scmm-scm-manager.default.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack')

        // Applications (jetzt unter argocd/applications)
        def argocdYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.applicationsDir(), 'argocd.yaml'))
        assertThat(argocdYaml['spec']['source']['directory']).isNull()

        // Neuer Pfad: Chart liegt unter argocd/argocd (nicht mehr nur argocd/)
        assertThat(argocdYaml['spec']['source']['path'] as String)
                .isIn('apps/argocd/argocd', 'apps/argocd/argocd/')
    }

    @Test
    void 'publishes argocd repository content through repository workspace'() {
        def argocd = createArgoCD()

        execute(argocd)

        verify(repositoryWorkspace.clusterResourcesRepository).commitAndPush('Update ArgoCD repository content')
    }

    @Test
    void 'uses repository workspace for cluster resources repository content'() {
        def argocd = createArgoCD()

        execute(argocd)

        def argoCDForTest = argocd as ArgoCDForTest

        assertThat(argoCDForTest.repositoryWorkspace.clusterResourcesRepository)
                .isSameAs(argoCDForTest.clusterResourcesRepo)

        clusterResourcesRepoLayout = argoCDForTest.getClusterRepoLayout()

        assertThat(new File(clusterResourcesRepoLayout.rootDir()).canonicalFile)
                .isEqualTo(new File(argoCDForTest.clusterResourcesRepo.absoluteLocalRepoTmpDir).canonicalFile)
    }


    @Test
    void 'SecurityContext null in Openshift'() {
        config.application.openshift = true
        execute(createArgoCD())

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
        prepareKubernetesObjectsForArgoCd()

        def argoCD = ArgoCDForTest.newWithAutoProviders(config,
                k8sClient,
                helmCommands)

        this.repositoryWorkspace = (argoCD as ArgoCDForTest).repositoryWorkspace

        return argoCD
    }

    private boolean execute(ArgoCD argoCD) {
        return (argoCD as ArgoCDForTest).execute()
    }

    private void prepareKubernetesObjectsForArgoCd() {
        String namespace = "${config.application.namePrefix ?: ''}${config.features.argocd.namespace ?: 'argocd'}"

        createNamespaceIfMissing(namespace)
        createNamespaceIfMissing(config.multiTenant.centralArgocdNamespace ?: 'argocd')

        createArgoCdCrds()

        config.application.namespaces.getActiveNamespaces().each { String activeNamespace -> createNamespaceIfMissing(activeNamespace)
        }

        createSecretIfMissing('argocd-secret', namespace)
        createSecretIfMissing('argocd-cluster', namespace)
        createSecretIfMissing('argocd-default-cluster-config', namespace,
                [namespaces: Base64.encoder.encodeToString('testnamespace1,testnamespace2'.bytes)])

        if (config.multiTenant.useDedicatedInstance) {
            createSecretIfMissing('argocd-default-cluster-config', config.multiTenant.centralArgocdNamespace ?: 'argocd',
                    [namespaces: Base64.encoder.encodeToString('testnamespace1,testnamespace2'.bytes)])
        }
    }

    private void createArgoCdCrds() {
        createNamespacedCrd('appprojects.argoproj.io', 'argoproj.io', 'v1alpha1', 'AppProject', 'appprojects', 'appproject')
        createNamespacedCrd('applications.argoproj.io', 'argoproj.io', 'v1alpha1', 'Application', 'applications', 'application')
        createNamespacedCrd('argocds.argoproj.io', 'argoproj.io', 'v1beta1', 'ArgoCD', 'argocds', 'argocd')
    }

    private void createNamespacedCrd(String name,
                                     String group,
                                     String version,
                                     String kind,
                                     String plural,
                                     String singular) {
        if (client.apiextensions().v1().customResourceDefinitions().withName(name).get()) {
            return
        }

        CustomResourceDefinition crd = new CustomResourceDefinitionBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .withNewSpec()
                .withGroup(group)
                .withScope('Namespaced')
                .withNewNames()
                .withKind(kind)
                .withPlural(plural)
                .withSingular(singular)
                .endNames()
                .addNewVersion()
                .withName(version)
                .withServed(true)
                .withStorage(true)
                .withNewSchema()
                .withNewOpenAPIV3Schema()
                .withType('object')
                .withXKubernetesPreserveUnknownFields(true)
                .endOpenAPIV3Schema()
                .endSchema()
                .endVersion()
                .endSpec()
                .build()

        client.apiextensions()
                .v1()
                .customResourceDefinitions()
                .resource(crd)
                .create()
    }

    private void createNamespaceIfMissing(String name) {
        if (!name) {
            throw new IllegalArgumentException()
        }

        if (!client.namespaces().withName(name).get()) {
            client.namespaces().resource(new NamespaceBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .endMetadata()
                    .build())
                    .create()
        }
    }

    private String decodedSecretValue(Secret secret, String key) {
        if (secret.stringData?.containsKey(key)) {
            return secret.stringData[key]
        }

        if (secret.data?.containsKey(key)) {
            return new String(Base64.decoder.decode(secret.data[key]))
        }

        return null
    }

    private void createSecretIfMissing(String name, String namespace, Map<String, String> data = [:]) {
        if (!namespace) {
            throw new IllegalArgumentException()
        }

        createNamespaceIfMissing(namespace)

        if (!client.secrets().inNamespace(namespace).withName(name).get()) {
            Secret secret = new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .endMetadata()
                    .withType('Opaque')
                    .withData(data)
                    .build()

            client.secrets()
                    .inNamespace(namespace)
                    .resource(secret)
                    .create()
        }
    }

    @Test
    void 'Creates all necessary namespaces'() {
        def argoCD = createArgoCD()

        execute(argoCD)

        config.application.namespaces.getActiveNamespaces().each { namespace -> assertThat(client.namespaces().withName(namespace).get()).isNotNull()
        }
    }

    @Test
    void 'Central Bootstrapping for Tenant Applications'() {
        setupDedicatedInstanceMode()

        assertThat(clusterResourcesRepoLayout).isNotNull()

        def ingressFile = new File(clusterResourcesRepoLayout.operatorDir(), 'ingress.yaml')
        assertThat(ingressFile)
                .as('Ingress file should not be generated when insecure is false')
                .doesNotExist()
    }

    @Test
    void 'dedicated mode applies central and tenant bootstrap resources'() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.scmManager.url = 'scmm.testhost/scm'
        config.multiTenant.scmManager.username = 'testUserName'
        config.multiTenant.scmManager.password = 'testPassword'
        config.multiTenant.useDedicatedInstance = true
        config.features.argocd.operator = true
        config.features.argocd.resourceInclusionsCluster = 'https://192.168.0.1:6443'

        doReturn('Applied').when(k8sClient).applyYaml(any(String))

        def argocd = createArgoCD()

        execute(argocd)

        def argoCDForTest = argocd as ArgoCDForTest
        def clusterLayout = argoCDForTest.getClusterRepoLayout()
        def tenantLayout = argoCDForTest.getTenantRepoLayout()

        verify(k8sClient).applyYaml(Path.of(clusterLayout.projectsDir(), 'tenant.yaml').toString())
        verify(k8sClient).applyYaml(Path.of(clusterLayout.applicationsDir(), 'bootstrap.yaml').toString())
        verify(k8sClient).applyYaml(Path.of(tenantLayout.projectsDir(), 'argocd.yaml').toString())
        verify(k8sClient).applyYaml(Path.of(tenantLayout.applicationsDir(), 'bootstrap.yaml').toString())
    }

    @Test
    void 'dedicated mode creates central repo credentials secret'() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.scmManager.url = 'scmm.testhost/scm'
        config.multiTenant.scmManager.username = 'testUserName'
        config.multiTenant.scmManager.password = 'testPassword'
        config.multiTenant.useDedicatedInstance = true
        config.features.argocd.operator = true
        config.features.argocd.resourceInclusionsCluster = 'https://192.168.0.1:6443'

        doReturn('Applied').when(k8sClient).applyYaml(any(String))

        execute(createArgoCD())

        Secret centralRepoCredentialsSecret = client.secrets()
                .inNamespace(config.multiTenant.centralArgocdNamespace)
                .withName('argocd-repo-creds-central-scm')
                .get()

        assertThat(centralRepoCredentialsSecret).isNotNull()
        assertThat(centralRepoCredentialsSecret.metadata.labels['argocd.argoproj.io/secret-type'])
                .isEqualTo('repo-creds')
    }

    @Test
    void 'GOP DedicatedInstances Central templating works correctly'() {
        setupDedicatedInstanceMode()

        assertThat(clusterResourcesRepoLayout).isNotNull()

        assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + '/applications/argocd.yaml')).exists()
        assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + '/applications/bootstrap.yaml')).exists()
        assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + '/applications/projects.yaml')).exists()
        assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + '/applications/example-apps.yaml')).doesNotExist()

        def argocdYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.argocdRoot(), '/applications/argocd.yaml'))
        def bootstrapYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.argocdRoot(), '/applications/bootstrap.yaml'))
        def projectsYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.argocdRoot(), '/applications/projects.yaml'))

        assertThat(argocdYaml['metadata']['name']).isEqualTo('testPrefix-argocd')
        assertThat(argocdYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(argocdYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(argocdYaml['spec']['source']['path']).isEqualTo('apps/argocd/operator/')

        assertThat(bootstrapYaml['metadata']['name']).isEqualTo('testPrefix-bootstrap')
        assertThat(bootstrapYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(bootstrapYaml['spec']['project']).isEqualTo('testPrefix')
        assertThat(bootstrapYaml['spec']['source']['repoURL']).isEqualTo('scmm.testhost/scm/repo/testPrefix-argocd/cluster-resources.git')

        assertThat(projectsYaml['metadata']['name']).isEqualTo('testPrefix-projects')
        assertThat(projectsYaml['metadata']['namespace']).isEqualTo('argocd')
        assertThat(projectsYaml['spec']['project']).isEqualTo('testPrefix')

        assertThat(new File(clusterResourcesRepoLayout.argocdRoot() + '/projects/tenant.yaml')).exists()

        def tenantProject = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.argocdRoot(), '/projects/tenant.yaml'))

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

        Secret defaultClusterConfig = client.secrets()
                .inNamespace('argocd')
                .withName('argocd-default-cluster-config')
                .get()

        assertThat(defaultClusterConfig).isNotNull()

        String namespaces = decodedSecretValue(defaultClusterConfig, 'namespaces')
        assertThat(namespaces).contains('testnamespace1')
        assertThat(namespaces).contains('testnamespace2')
        assertThat(namespaces).contains('testPrefix-dedi-test1')
        assertThat(namespaces).contains('testPrefix-dedi-test2')
        assertThat(namespaces).contains('testPrefix-dedi-test3')
        assertThat(namespaces).contains('testPrefix-tenant-test1')
        assertThat(namespaces).contains('testPrefix-tenant-test2')
        assertThat(namespaces).contains('testPrefix-tenant-test3')
    }

    @Test
    void 'multiTenant folder gets deleted correctly if not in dedicated mode'() {
        config.multiTenant.useDedicatedInstance = false

        def argocd = createArgoCD()
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), 'multiTenant/')).doesNotExist()
        assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), 'applications/')).exists()
        assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), 'projects/')).exists()
    }

    @Test
    void 'deleting unused folder in dedicated mode'() {
        setupDedicatedInstanceMode()

        assertThat(clusterResourcesRepoLayout).isNotNull()
        assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), 'multiTenant/')).doesNotExist()
        assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), 'applications/')).exists()
        assertThat(Path.of(clusterResourcesRepoLayout.argocdRoot(), 'projects/')).exists()
    }

    @Test
    void 'RBACs generated correctly'() {
        config.application.namespaces.tenantNamespaces = new LinkedHashSet(['testprefix-tenant-test1', 'testprefix-tenant-test2', 'testprefix-tenant-test3'])
        setupDedicatedInstanceMode()

        File rbacFolder = new File(clusterResourcesRepoLayout.operatorRbacDir())
        File rbacTenantFolder = new File(clusterResourcesRepoLayout.operatorRbacDir() + '/tenant')
        assertThat(rbacFolder).exists()
        assertThat(rbacTenantFolder).exists()

        assertThat(rbacFolder.listFiles().count { it.isFile() }).isEqualTo(14)
        assertThat(rbacTenantFolder.listFiles().count { it.isFile() }).isEqualTo(6)

        rbacFolder.eachFile { file ->
            if (file.name.startsWith('role-') && file.name.contains('dedi')) {
                def rbacFile = new YamlSlurper().parse(Path.of(file.path))
                assertThat(rbacFile['metadata']['namespace']).isIn(config.application.namespaces.getActiveNamespaces())
            }
            if (file.name.startsWith('rolebinding-') && file.name.contains('dedi')) {
                def rbacFile = new YamlSlurper().parse(Path.of(file.path))
                assertThat(rbacFile['subjects']['namespace']).isEqualTo(['argocd', 'argocd', 'argocd'])
            }
        }

        rbacTenantFolder.eachFile { file ->
            if (file.name.startsWith('role-')) {
                def rbacFile = new YamlSlurper().parse(Path.of(file.path))
                assertThat(rbacFile['metadata']['namespace']).isIn(config.application.namespaces.tenantNamespaces)
            }

            if (file.name.startsWith('rolebinding-')) {
                def rbacFile = new YamlSlurper().parse(Path.of(file.path))
                assertThat(rbacFile['subjects']['namespace']).isEqualTo(['testPrefix-argocd', 'testPrefix-argocd', 'testPrefix-argocd'])
            }
        }
    }

    @Test
    void 'Operator RBAC includes node access rules when not on OpenShift'() {
        config.application.namePrefix = 'testprefix-'

        def argocd = setupOperatorTest(openshift: false)
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        print config.toMap()

        File rbacDir = Path.of(clusterResourcesRepoLayout.operatorRbacDir()).toFile()
        File roleFile = new File(rbacDir, 'role-argocd-testprefix-monitoring.yaml')

        Map yaml = new YamlSlurper().parse(roleFile) as Map
        List<Map<String, Object>> rules = yaml['rules'] as List<Map<String, Object>>

        assertThat(rules).anyMatch { rule ->
            List<String> resources = rule['resources'] as List<String>
            resources.contains('nodes') && resources.contains('nodes/metrics')
        }
    }

    @Test
    void 'Operator RBAC does not include node access rules when on OpenShift'() {
        config.application.namePrefix = 'testprefix-'

        def argocd = setupOperatorTest(openshift: true)
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        File rbacDir = Path.of(clusterResourcesRepoLayout.operatorRbacDir()).toFile()
        File roleFile = new File(rbacDir, 'role-argocd-testprefix-monitoring.yaml')

        Map yaml = new YamlSlurper().parse(roleFile) as Map
        List<Map<String, Object>> rules = yaml['rules'] as List<Map<String, Object>>

        assertThat(rules).noneMatch { rule ->
            List<String> resources = rule['resources'] as List<String>
            resources.contains('nodes') && resources.contains('nodes/metrics')
        }
    }

    @Test
    void 'If not using mirror, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = false

        def argocd = createArgoCD()
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.projectsDir(), '/cluster-resources.yaml'))

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains('https://charts.external-secrets.io',
                'https://codecentric.github.io/helm-charts',
                'https://prometheus-community.github.io/helm-charts',
                'https://traefik.github.io/charts',
                'https://helm.releases.hashicorp.com',
                'https://charts.jetstack.io')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain('http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/traefik',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager')

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain('http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/kube-prometheus-stack.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/traefik.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/external-secrets.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/vault.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/cert-manager.git')
    }

    @Test
    void 'If using mirror, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true

        def argocd = createArgoCD()
        execute(argocd)

        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.projectsDir(), '/cluster-resources.yaml'))

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains('http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/traefik',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager')
        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain('http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/kube-prometheus-stack.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/traefik.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/external-secrets.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/vault.git',
                'http://scmm.scm-manager.svc.cluster.local/scm/3rd-party-dependencies/cert-manager.git')
    }

    @Test
    void 'If using mirror with GitLab, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true
        config.scm.scmProviderType = 'GITLAB'
        config.scm.gitlab.url = 'https://testGitLab.com/testgroup'
        def argocd = createArgoCD()
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.projectsDir(), '/cluster-resources.yaml'))

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains('https://testGitLab.com/testgroup/3rd-party-dependencies/kube-prometheus-stack.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/traefik.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/external-secrets.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/vault.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/cert-manager.git')
    }

    @Test
    void 'If using mirror with GitLab with prefix, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true
        config.scm.scmProviderType = 'GITLAB'
        config.scm.gitlab.url = 'https://testGitLab.com/testgroup'
        config.application.namePrefix = 'test1-'

        def argocd = createArgoCD()
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.projectsDir(), '/cluster-resources.yaml'))

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains('https://testGitLab.com/testgroup/3rd-party-dependencies/kube-prometheus-stack.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/traefik.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/external-secrets.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/vault.git',
                'https://testGitLab.com/testgroup/3rd-party-dependencies/cert-manager.git')

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain('http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/traefik',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager')
    }

    @Test
    void 'If using mirror with name-prefix, ensure source repos in cluster-resources got right URL'() {
        config.application.mirrorRepos = true
        config.application.namePrefix = 'test1-'

        def argocd = createArgoCD()
        execute(argocd)
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()

        def clusterRessourcesYaml = new YamlSlurper().parse(Path.of(clusterResourcesRepoLayout.projectsDir(), '/cluster-resources.yaml'))

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).contains('http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/kube-prometheus-stack',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/traefik',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/external-secrets',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/vault',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/repo/3rd-party-dependencies/cert-manager')

        assertThat(clusterRessourcesYaml['spec']['sourceRepos'] as List).doesNotContain('http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/kube-prometheus-stack.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/traefik.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/external-secrets.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/vault.git',
                'http://scmm.test1-scm-manager.svc.cluster.local/scm/3rd-party-dependencies/cert-manager.git')
    }

    void setupDedicatedInstanceMode() {
        config.application.namePrefix = 'testPrefix-'
        config.multiTenant.scmManager.url = 'scmm.testhost/scm'
        config.multiTenant.scmManager.username = 'testUserName'
        config.multiTenant.scmManager.password = 'testPassword'
        config.multiTenant.useDedicatedInstance = true
        this.argocd = setupOperatorTest()

        doReturn('Applied').when(k8sClient).applyYaml(any(String))

        execute(argocd)
        this.clusterResourcesRepo = (argocd as ArgoCDForTest).clusterResourcesRepo
        clusterResourcesRepoLayout = (argocd as ArgoCDForTest).getClusterRepoLayout()
    }

    protected ArgoCD setupOperatorTest(Map options = [:]) {
        config.features.argocd.operator = true
        config.features.argocd.resourceInclusionsCluster = 'https://192.168.0.1:6443'
        config.application.openshift = options.openshift ?: false

        return createArgoCD()
    }

    private Map parseActualYaml(String pathToYamlFile) {
        File yamlFile = new File(pathToYamlFile)
        def ys = new YamlSlurper()
        return ys.parse(yamlFile) as Map
    }
}
