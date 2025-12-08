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
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
@Singleton
@Order(100)
class ArgoCD extends Feature {

    private final String namespace
    private final Config config
    private final K8sClient k8sClient
    private final HelmClient helmClient
    private final FileSystemUtils fileSystemUtils
    private final GitRepoFactory repoProvider
    private final GitHandler gitHandler
    private final List<RepoInitializationAction> gitRepos = []

    private final String password

    private RepoInitializationAction clusterResourcesInitializationAction
    private RepoInitializationAction tenantBootstrapInitializationAction


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
        this.password = config.application.password
        this.namespace = "${config.application.namePrefix}${config.features.argocd.namespace}"
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

        initGitOpsRepos()

        // Init aller Repos (clusterResources, tenantBootstrap, etc.)
        gitRepos.each { it.initLocalRepo() }

        prepareGitOpsRepos()

        this.gitRepos.forEach(repoInitializationAction -> {
            repoInitializationAction.repo.commitAndPush('Initial Commit')
        })

        log.debug('Installing Argo CD')
        installArgoCd()
    }

    private void initGitOpsRepos() {
        initTenantRepos()
        initCentralRepos()

        // Ab hier existiert clusterResourcesInitializationAction
        Set<String> clusterResourceSubDirs = new LinkedHashSet<>()

        // Basis: der argocd-Ordner unter argocd/cluster-resources
        clusterResourceSubDirs.add('argocd')
        clusterResourceSubDirs.add('misc')

        // Monitoring nur, wenn Feature aktiv
        if (config.features.monitoring.active) {
            clusterResourceSubDirs.add(ArgoCDRepoLayout.monitoringSubdirRel())   // "apps/monitoring"
        }

        // direkt auf die eine bekannte Action setzen
        clusterResourcesInitializationAction.subDirsToCopy = clusterResourceSubDirs
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

        ArgoCDRepoLayout repoLayout = repoLayout()

        if (config.multiTenant.useDedicatedInstance) {
            //Bootstrapping dedicated instance
            k8sClient.applyYaml(repoLayout.dedicatedTenantProject())
            k8sClient.applyYaml(repoLayout.dedicatedBootstrapApp())

            //Bootstrapping tenant Argocd projects
            ArgoCDRepoLayout tenantRepoLayout = tenantRepoLayout()
            k8sClient.applyYaml(Path.of(tenantRepoLayout.projectsDir(), "argocd.yaml").toString())
            k8sClient.applyYaml(Path.of(tenantRepoLayout.applicationsDir(), "bootstrap.yaml").toString())
        } else {
            // Bootstrap root application
            k8sClient.applyYaml(Path.of(repoLayout.projectsDir(), "argocd.yaml").toString())
            k8sClient.applyYaml(Path.of(repoLayout.applicationsDir(), "bootstrap.yaml").toString())
        }

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', namespace,
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))
    }

    private void initTenantRepos() {
        if (!config.multiTenant.useDedicatedInstance) {
            clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources', this.gitHandler.tenant)
            this.gitRepos.add(clusterResourcesInitializationAction)

        } else {
            tenantBootstrapInitializationAction = createRepoInitializationAction('argocd/cluster-resources/argocd/multiTenant/tenant', 'argocd/cluster-resources', this.gitHandler.tenant)
            this.gitRepos.add(tenantBootstrapInitializationAction)
        }
    }

    private void initCentralRepos() {
        if (config.multiTenant.useDedicatedInstance) {
            clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources', true)
            this.gitRepos.add(clusterResourcesInitializationAction)
        }
    }

    private void prepareGitOpsRepos() {
        ArgoCDRepoLayout repoLayout = repoLayout()
        String root = repoLayout.rootDir()
        String monitoringRoot = repoLayout.monitoringDir()

        if (!config.features.secrets.active) {
            log.debug("Deleting unnecessary secrets folder from cluster resources: ${root}")
            FileSystemUtils.deleteDir root + '/misc/secrets'
        }

        if (!config.features.ingressNginx.active) {
            FileSystemUtils.deleteFile monitoringRoot + '/ingress-nginx-dashboard.yaml'
            FileSystemUtils.deleteFile monitoringRoot + '/ingress-nginx-dashboard-requests-handling.yaml'
        }
    }

    private void deployWithOperator() {
        ArgoCDRepoLayout repoLayout = repoLayout()

        // Apply argocd yaml from operator folder
        String argocdConfigPath = repoLayout.operatorConfigFile()
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
        String argocdRbacPath = repoLayout.operatorRbacDir()
        k8sClient.applyYaml("${argocdRbacPath} --recursive")
    }


    private void deployWithHelm() {
        ArgoCDRepoLayout repoLayout = repoLayout()

        // Install umbrella chart from argocd/argocd
        String umbrellaChartPath = repoLayout.helmDir()
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(
                Path.of(repoLayout.chartYaml()))['dependencies']
        helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
        helmClient.dependencyBuild(umbrellaChartPath)
        helmClient.upgrade('argocd', umbrellaChartPath, [namespace: namespace])

        log.debug("Setting new argocd admin password")
        // Set admin password imperatively here instead of values.yaml, because we don't want it to show in git repo
        String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))
        k8sClient.patch('secret', 'argocd-secret', namespace,
                [stringData: ['admin.password': bcryptArgoCDPassword]])

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
        ArgoCDRepoLayout repoLayout = repoLayout()

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
                        .withRepo(clusterResourcesInitializationAction.repo)
                        .withSubfolder(repoLayout.operatorRbacTenantSubfolder())
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
                        .withRepo(clusterResourcesInitializationAction.repo)
                        .withSubfolder(repoLayout.operatorRbacSubfolder())
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
                        .withRepo(clusterResourcesInitializationAction.repo)
                        .withSubfolder(repoLayout.operatorRbacSubfolder())
                        .generate()
            }

            if (config.application.clusterAdmin) {
                new RbacDefinition(Role.Variant.CLUSTER_ADMIN)
                        .withName("argocd-cluster-admin")
                        .withNamespace(namespace)
                        .withServiceAccountsFrom(
                                namespace,
                                ["argocd-argocd-server", "argocd-argocd-application-controller", "argocd-applicationset-controller"]
                        )
                        .withConfig(config)
                        .withRepo(clusterResourcesInitializationAction.repo)
                        .withSubfolder(repoLayout.operatorRbacSubfolder())
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
        createRepoCredentialsSecret(
                'argocd-repo-creds-scm',
                namespace,
                gitHandler.tenant.url,
                gitHandler.tenant.credentials.username,
                gitHandler.tenant.credentials.password
        )

        if (config.multiTenant.useDedicatedInstance) {
            log.debug("Creating central repo credential secret that is used by argocd to access repos in ${config.scm.scmProviderType.toString()}")

            // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
            createRepoCredentialsSecret(
                    'argocd-repo-creds-central-scm',
                    config.multiTenant.centralArgocdNamespace,
                    gitHandler.central.url,
                    gitHandler.central.credentials.username,
                    gitHandler.central.credentials.password
            )
        }
    }

    protected void prepareArgoCdRepo() {
        ArgoCDRepoLayout repoLayout = repoLayout()

        if (config.features.argocd.operator) {
            log.debug("Deleting unnecessary argocd (argocd helm variant) folder from argocd repo: ${repoLayout.helmDir()}")
            FileSystemUtils.deleteDir repoLayout.helmDir()
            log.debug("Deleting unnecessary namespaces resources from clusterResources repo: ${repoLayout.namespacesYaml()}")
            FileSystemUtils.deleteFile repoLayout.namespacesYaml()
            generateRBAC()
        } else {
            log.debug("Deleting unnecessary operator (argocd operator variant) folder from argocd repo: ${repoLayout.operatorDir()}")
            FileSystemUtils.deleteDir repoLayout.operatorDir()
        }

        if (config.multiTenant.useDedicatedInstance) {
            log.debug("Deleting unnecessary non dedicated instances folders from argocd repo: applications=${repoLayout.applicationsDir()}, projects=${repoLayout.projectsDir()}")
            FileSystemUtils.deleteDir repoLayout.applicationsDir()
            FileSystemUtils.deleteDir repoLayout.projectsDir()
        } else {
            log.debug("Deleting unnecessary multiTenant folder from argocd repo: ${repoLayout.multiTenantDir()}")
            FileSystemUtils.deleteDir repoLayout.multiTenantDir()
        }

        if (!config.application.netpols) {
            log.debug("Deleting argocd netpols at ${repoLayout.netpolFile()}")
            FileSystemUtils.deleteFile repoLayout.netpolFile()
        }

        clusterResourcesInitializationAction.repo.commitAndPush("Initial Commit")
    }

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmRepoTarget, Boolean isCentral) {
        GitProvider provider = (Boolean.TRUE == isCentral) ? gitHandler.central : gitHandler.tenant
        new RepoInitializationAction(config, repoProvider.getRepo(scmRepoTarget, provider), this.gitHandler, localSrcDir)
    }

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmRepoTarget, GitProvider gitProvider) {
        new RepoInitializationAction(config, repoProvider.getRepo(scmRepoTarget, gitProvider), this.gitHandler, localSrcDir)
    }

    private void createRepoCredentialsSecret(String secretName, String ns, String url, String username, String password) {
        k8sClient.createSecret('generic', secretName, ns,
                new Tuple2('url', url),
                new Tuple2('username', username),
                new Tuple2('password', password)
        )
        k8sClient.label('secret', secretName, ns,
                new Tuple2('argocd.argoproj.io/secret-type', 'repo-creds'))
    }

    private ArgoCDRepoLayout repoLayout() {
        new ArgoCDRepoLayout(clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir())
    }

    private ArgoCDRepoLayout tenantRepoLayout() {
        new ArgoCDRepoLayout(tenantBootstrapInitializationAction.repo.getAbsoluteLocalRepoTmpDir())
    }

}