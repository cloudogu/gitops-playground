package com.cloudogu.gitops.tools.core.argocd

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
import static org.mockito.Mockito.doNothing
import static org.mockito.Mockito.spy

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
    ArgoCD argocd
    ArgoCDRepoLayout clusterResourcesRepoLayout

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
