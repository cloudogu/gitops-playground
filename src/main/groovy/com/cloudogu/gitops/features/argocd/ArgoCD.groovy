package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.kubernetes.rbac.RbacDefinition
import com.cloudogu.gitops.kubernetes.rbac.Role
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.kubernetes.HelmClient
import com.cloudogu.gitops.kubernetes.K8sClient
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
@Singleton
@Order(100)
class ArgoCD extends Feature {
    static final String HELM_VALUES_PATH = 'argocd/values.yaml'
    static final String OPERATOR_CONFIG_PATH = 'operator/argocd.yaml'
    static final String OPERATOR_RBAC_PATH = 'operator/rbac'
    static final String CHART_YAML_PATH = 'argocd/Chart.yaml'
    static final String DEDICATED_INSTANCE_PATH = 'multiTenant/central/'
    static final String MONITORING_RESOURCES_PATH = '/misc/monitoring/'

    private String namespace = "${config.application.namePrefix}${config.features.argocd.namespace}"
    private Config config
    private List<RepoInitializationAction> gitRepos = []

    private String password

    protected final String scmmUrlInternal = "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm"

    protected RepoInitializationAction argocdRepoInitializationAction
    protected RepoInitializationAction clusterResourcesInitializationAction
    protected RepoInitializationAction tenantBootstrapInitializationAction
    protected File remotePetClinicRepoTmpDir

    protected K8sClient k8sClient
    protected HelmClient helmClient

    protected FileSystemUtils fileSystemUtils
    private GitRepoFactory repoProvider

    GitHandler gitHandler

    ArgoCD(
            Config config,
            K8sClient k8sClient,
            HelmClient helmClient,
            FileSystemUtils fileSystemUtils,
            GitRepoFactory repoProvider,
            GitHandler gitHandler
    ) {
        this.repoProvider = repoProvider
        this.config = config
        this.k8sClient = k8sClient
        this.helmClient = helmClient
        this.fileSystemUtils = fileSystemUtils
        this.gitHandler = gitHandler
        this.password = this.config.application.password
    }

    @Override
    boolean isEnabled() {
        config.features.argocd.active
    }

    @Override
    void postConfigInit(Config configToSet) {
        // Exit early if not in operator mode or if env list is empty
        if (!configToSet.features.argocd.operator || !configToSet.features.argocd.env) {
            log.debug("Skipping features.argocd.env validation: operator mode is disabled or env list is empty.")
            return
        }

        List<Map> env = configToSet.features.argocd.env as List<Map<String, String>>

        log.info("Validating env list in features.argocd.env with {} entries.", env.size())

        env.each { map ->
            if (!(map instanceof Map) || !map.containsKey('name') || !map.containsKey('value')) {
                throw new IllegalArgumentException("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: $map")
            }
        }

        log.info("Env list validation for features.argocd.env completed successfully.")
    }

    @Override
    void enable() {

        initTenantRepos()
        initCentralRepos()

        log.debug('Cloning Repositories')

        gitRepos.forEach(repoInitializationAction -> {
            repoInitializationAction.initLocalRepo()
        })

        prepareGitOpsRepos()

        gitRepos.forEach(repoInitializationAction -> {
            repoInitializationAction.repo.commitAndPush('Initial Commit')
        })

        log.debug('Installing Argo CD')
        installArgoCd()
    }

    private void installArgoCd() {

        prepareArgoCdRepo()

        log.debug("Creating namespaces")
        k8sClient.createNamespaces(config.application.namespaces.activeNamespaces.toList())

        createMonitoringCrd()

        createSCMCredentialsSecret()

        if (config.features.mail.smtpUser || config.features.mail.smtpPassword) {
            k8sClient.createSecret(
                    'generic',
                    'argocd-notifications-secret',
                    namespace,
                    new Tuple2('email-username', config.features.mail.smtpUser),
                    new Tuple2('email-password', config.features.mail.smtpPassword)
            )
        }

        if (config.features.argocd.operator) {
            deployWithOperator()
        } else {
            deployWithHelm()
        }

        if (config.multiTenant.useDedicatedInstance) {
            //Bootstrapping dedicated instance
            k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), "${DEDICATED_INSTANCE_PATH}projects/tenant.yaml").toString())
            k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), "${DEDICATED_INSTANCE_PATH}applications/bootstrap.yaml").toString())
            //Bootstrapping tenant Argocd projects
            k8sClient.applyYaml(Path.of(tenantBootstrapInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml').toString())
            k8sClient.applyYaml(Path.of(tenantBootstrapInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml').toString())
        } else {
            // Bootstrap root application
            k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'projects/argocd.yaml').toString())
            k8sClient.applyYaml(Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'applications/bootstrap.yaml').toString())
        }

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', namespace,
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))
    }

    protected initTenantRepos() {
        if (!config.multiTenant.useDedicatedInstance) {
            argocdRepoInitializationAction = createRepoInitializationAction('argocd/argocd', 'argocd/argocd', this.gitHandler.tenant)

            clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources', this.gitHandler.tenant)
            gitRepos += clusterResourcesInitializationAction
        } else {
            tenantBootstrapInitializationAction = createRepoInitializationAction('argocd/argocd/multiTenant/tenant', 'argocd/argocd', this.gitHandler.tenant)
            gitRepos += tenantBootstrapInitializationAction
        }
    }

    protected initCentralRepos() {
        if (config.multiTenant.useDedicatedInstance) {
            argocdRepoInitializationAction = createRepoInitializationAction('argocd/argocd', 'argocd/argocd', true)

            clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources', true)
            gitRepos += clusterResourcesInitializationAction
        }
    }

    private void prepareGitOpsRepos() {

        if (!config.features.secrets.active) {
            log.debug("Deleting unnecessary secrets folder from cluster resources: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteDir clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/misc/secrets'
        }

        if (!config.features.monitoring.active) {
            log.debug("Deleting unnecessary monitoring folder from cluster resources: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteDir clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + MONITORING_RESOURCES_PATH
        } else if (!config.features.ingressNginx.active) {
            FileSystemUtils.deleteFile clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + MONITORING_RESOURCES_PATH + 'ingress-nginx-dashboard.yaml'
            FileSystemUtils.deleteFile clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + MONITORING_RESOURCES_PATH + 'ingress-nginx-dashboard-requests-handling.yaml'
        }

    }

    private void deployWithHelm() {
        // Install umbrella chart from folder
        String umbrellaChartPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'argocd/')
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(
                Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), CHART_YAML_PATH))['dependencies']
        helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
        helmClient.dependencyBuild(umbrellaChartPath)
        helmClient.upgrade('argocd', umbrellaChartPath, [namespace: "${namespace}"])

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', namespace,
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))

        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of values.yaml, because we don't want it to show in git repo
        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        k8sClient.patch('secret', 'argocd-secret', namespace,
                [stringData: ['admin.password': bcryptArgoCDPassword]])
    }

    private void deployWithOperator() {
        // Apply argocd yaml from operator folder
        String argocdConfigPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), OPERATOR_CONFIG_PATH)
        if (this.config.features.argocd?.values) {
            log.debug("extend Argocd.yaml with ${this.config.features.argocd.values}")
            def argocdYaml = fileSystemUtils.readYaml(
                    Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), OPERATOR_CONFIG_PATH))

            def result = MapUtils.deepMerge(this.config.features.argocd.values, argocdYaml)
            fileSystemUtils.writeYaml(result, new File (argocdConfigPath))
            log.debug("Argocd.yaml for operator contains ${result}")
            // reload file
            argocdConfigPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), OPERATOR_CONFIG_PATH)

        }

        k8sClient.applyYaml(argocdConfigPath)

        // ArgoCD is not installed until the ArgoCD-Operator did his job.
        // This can take some time, so we wait for the status of the custom resource to become "Available"
        k8sClient.waitForResourcePhase("argocd", "argocd", namespace, "Available")

        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of operator/argocd.yaml, because we don't want it to show in git repo
        // The Operator uses an extra secret to store the admin Password, which is not bcrypted
        k8sClient.patch('secret', 'argocd-cluster', namespace,
                [stringData: ['admin.password': password]])
        // In newer Versions ArgoCD Operator uses the password in argocd-cluster secret only as generated initial password
        // but we want to set our own admin password so we set the password in both Secrets for consistency
        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        k8sClient.patch('secret', 'argocd-secret', namespace,
                [stringData: ['admin.password': bcryptArgoCDPassword]])


        updatingArgoCDManagedNamespaces()

        log.debug("Apply RBAC permissions for ArgoCD in all managed namespaces imperatively")
        // Apply rbac yamls from operator/rbac folder
        String argocdRbacPath = Path.of(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), OPERATOR_RBAC_PATH)
        k8sClient.applyYaml("${argocdRbacPath} --recursive")
    }

    // The ArgoCD instance installed via an operator only manages its deployment namespace.
    // To manage additional namespaces, we need to update the 'argocd-default-cluster-config' secret with all managed namespaces.
    void updatingArgoCDManagedNamespaces() {

        log.debug("Updating managed namespaces in ArgoCD configuration secret.")
        def namespaceList = !config.multiTenant.useDedicatedInstance ?
                config.application.namespaces.activeNamespaces :
                config.application.namespaces.tenantNamespaces

        k8sClient.patch('secret', 'argocd-default-cluster-config', namespace,
                [stringData: ['namespaces': namespaceList.join(',')]])

        if (config.multiTenant.useDedicatedInstance) {
            // Append new namespaces to existing ones from the secret.
            // `kubectl patch` can't merge list subfields, so we read, decode, merge, and update the secret.
            // This ensures all centrally managed namespaces are preserved.
            String base64Namespaces = k8sClient.getArgoCDNamespacesSecret('argocd-default-cluster-config', config.multiTenant.centralArgocdNamespace)
            byte[] decodedBytes = Base64.decoder.decode(base64Namespaces)
            String decoded = new String(decodedBytes, "UTF-8")
            def decodedList = decoded?.split(',') as List ?: []
            def activeList = config.application.namespaces.activeNamespaces?.flatten() as List ?: []
            def merged = (decodedList + activeList).unique().join(',')
            log.debug("Updating Central Argocd 'argocd-default-cluster-config' secret")
            k8sClient.patch('secret', 'argocd-default-cluster-config', config.multiTenant.centralArgocdNamespace,
                    [stringData: ['namespaces': merged]])
        }
    }

    private void generateRBAC() {

        log.debug("Generate RBAC permissions for ArgoCD in all managed namespaces")
        if (config.multiTenant.useDedicatedInstance) {
            //Generating Tenant Namespace RBACs for Tenant Argocd
            for (String ns : config.application.namespaces.tenantNamespaces) {
                new RbacDefinition(Role.Variant.ARGOCD)
                        .withName("argocd")
                        .withNamespace(ns)
                        .withServiceAccountsFrom(
                                namespace,
                                ["argocd-argocd-server", "argocd-argocd-application-controller", "argocd-applicationset-controller"]
                        )
                        .withConfig(config)
                        .withRepo(argocdRepoInitializationAction.repo)
                        .withSubfolder("${OPERATOR_RBAC_PATH}/tenant")
                        .generate()
            }

            //Generating Central ArgoCD RBACs for mananged namespaces
            for (String ns : config.application.namespaces.activeNamespaces) {
                log.debug("Generate RBAC permissions for centralized ArgoCD to access tenant ArgoCDs")
                new RbacDefinition(Role.Variant.ARGOCD)
                        .withName('argocd-central')
                        .withNamespace(ns)
                        .withServiceAccountsFrom(
                                config.multiTenant.centralArgocdNamespace,
                                ["argocd-argocd-server", "argocd-argocd-application-controller", "argocd-applicationset-controller"]
                        )
                        .withConfig(config)
                        .withRepo(argocdRepoInitializationAction.repo)
                        .withSubfolder(OPERATOR_RBAC_PATH)
                        .generate()
            }
        } else {
            for (String ns : config.application.namespaces.activeNamespaces) {
                new RbacDefinition(Role.Variant.ARGOCD)
                        .withName("argocd")
                        .withNamespace(ns)
                        .withServiceAccountsFrom(
                                namespace,
                                ["argocd-argocd-server", "argocd-argocd-application-controller", "argocd-applicationset-controller"]
                        )
                        .withConfig(config)
                        .withRepo(argocdRepoInitializationAction.repo)
                        .withSubfolder(OPERATOR_RBAC_PATH)
                        .generate()
            }

            if(config.application.clusterAdmin) {
                new RbacDefinition(Role.Variant.CLUSTER_ADMIN)
                        .withName("argocd-cluster-admin")
                        .withNamespace(namespace)
                        .withServiceAccountsFrom(
                                namespace,
                                ["argocd-argocd-server", "argocd-argocd-application-controller", "argocd-applicationset-controller"]
                        )
                        .withConfig(config)
                        .withRepo(argocdRepoInitializationAction.repo)
                        .withSubfolder(OPERATOR_RBAC_PATH)
                        .generate()
            }
        }
    }

    protected void createMonitoringCrd() {
        if (config.features.monitoring.active) {
            if (!config.application.skipCrds) {
                def serviceMonitorCrdYaml
                if (config.application.mirrorRepos) {
                    serviceMonitorCrdYaml = Path.of(
                            "${config.application.localHelmChartFolder}/${config.features.monitoring.helm.chart}/charts/crds/crds/crd-servicemonitors.yaml"
                    ).toString()
                } else {
                    serviceMonitorCrdYaml =
                            "https://raw.githubusercontent.com/prometheus-community/helm-charts/" +
                                    "kube-prometheus-stack-${config.features.monitoring.helm.version}/" +
                                    "charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml"
                }

                log.debug("Applying ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.\n" +
                        "Applying from path ${serviceMonitorCrdYaml}")
                k8sClient.applyYaml(serviceMonitorCrdYaml)
            }
        }
    }

    protected void createSCMCredentialsSecret() {

        log.debug("Creating repo credential secret that is used by argocd to access repos in ${config.scm.scmProviderType.toString()}")
        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
        def repoTemplateSecretName = 'argocd-repo-creds-scm'

        k8sClient.createSecret('generic', repoTemplateSecretName, namespace,
                new Tuple2('url', this.gitHandler.tenant.url),
                new Tuple2('username', this.gitHandler.tenant.credentials.username),
                new Tuple2('password', this.gitHandler.tenant.credentials.password)
        )

        k8sClient.label('secret', repoTemplateSecretName, namespace,
                new Tuple2('argocd.argoproj.io/secret-type', 'repo-creds'))

        if (config.multiTenant.useDedicatedInstance) {
            log.debug("Creating central repo credential secret that is used by argocd to access repos in ${config.scm.scmProviderType.toString()}")
            // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
            def centralRepoTemplateSecretName = 'argocd-repo-creds-central-scm'

            k8sClient.createSecret('generic', centralRepoTemplateSecretName, config.multiTenant.centralArgocdNamespace,
                    new Tuple2('url', this.gitHandler.central.url),
                    new Tuple2('username', this.gitHandler.central.credentials.username),
                    new Tuple2('password', this.gitHandler.central.credentials.password)
            )

            k8sClient.label('secret', centralRepoTemplateSecretName, config.multiTenant.centralArgocdNamespace,
                    new Tuple2('argocd.argoproj.io/secret-type', 'repo-creds'))
        }
    }

    protected void prepareArgoCdRepo() {

        argocdRepoInitializationAction.initLocalRepo()

        if (config.features.argocd.operator) {
            log.debug("Deleting unnecessary argocd (argocd helm variant) folder from argocd repo: ${argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/argocd'
            log.debug("Deleting unnecessary namespaces resources from clusterResources repo: ${clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteFile clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/misc/namespaces.yaml'
            generateRBAC()
        } else {
            log.debug("Deleting unnecessary operator (argocd operator variant) folder from argocd repo: ${argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/operator'
        }

        if (config.multiTenant.useDedicatedInstance) {
            log.debug("Deleting unnecessary non dedicated instances folders from argocd repo: ${argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/applications'
            FileSystemUtils.deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/projects'
        } else {
            log.debug("Deleting unnecessary multiTenant folder from argocd repo: ${argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()}")
            FileSystemUtils.deleteDir argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/multiTenant'
        }

        if (!config.application.netpols) {
            log.debug("Deleting argocd netpols.")
            FileSystemUtils.deleteFile argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/argocd/templates/allow-namespaces.yaml'
        }

        argocdRepoInitializationAction.repo.commitAndPush("Initial Commit")
    }

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmRepoTarget, Boolean isCentral) {
        GitProvider provider = (Boolean.TRUE == isCentral) ? gitHandler.central : gitHandler.tenant
        new RepoInitializationAction(config, repoProvider.getRepo(scmRepoTarget, provider), this.gitHandler, localSrcDir)
    }

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmRepoTarget, GitProvider gitProvider) {
        new RepoInitializationAction(config, repoProvider.getRepo(scmRepoTarget, gitProvider), this.gitHandler, localSrcDir)
    }

    private void replaceFileContentInYamls(File folder, String from, String to) {
        fileSystemUtils.getAllFilesFromDirectoryWithEnding(folder.absolutePath, ".yaml").forEach(file -> {
            fileSystemUtils.replaceFileContent(file.absolutePath, from, to)
        })
    }
}