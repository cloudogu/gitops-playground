package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.kubernetes.rbac.RbacDefinition
import com.cloudogu.gitops.kubernetes.rbac.Role
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
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
    private final String password

    private ArgoCDRepoSetup repoSetup
    private RepoLayout clusterResourcesRepo


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
        this.repoSetup = ArgoCDRepoSetup.create(config, fileSystemUtils, repoProvider, gitHandler)
        this.clusterResourcesRepo = repoSetup.clusterRepoLayout()

        log.debug('Cloning Repositories')
        repoSetup.initLocalRepos()
        repoSetup.prepareClusterResourcesRepo()
        repoSetup.commitAndPushAll('Initial Commit')

        log.debug('Installing Argo CD')
        installArgoCd()
    }


    private void installArgoCd() {

        log.debug("Creating namespaces")
        k8sClient.createNamespaces(config.application.namespaces.activeNamespaces.toList())

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
            generateRBAC()
            deployWithOperator()
        } else {
            if (this.config.features.argocd?.values) {
                String argocdConfigPath = clusterResourcesRepo.helmValuesFile()
                log.debug("extend Argocd values.yaml with ${this.config.features.argocd.values}")
                def argocdYaml = fileSystemUtils.readYaml(
                        Path.of(argocdConfigPath))

                def result = MapUtils.deepMerge(this.config.features.argocd.values, argocdYaml)
                fileSystemUtils.writeYaml(result, new File (argocdConfigPath))
                log.debug("Argocd values.yaml contains ${result}")
            }
            deployWithHelm()
        }

        if (config.multiTenant.useDedicatedInstance) {
            //Bootstrapping dedicated instance
            k8sClient.applyYaml(clusterResourcesRepo.dedicatedTenantProject())
            k8sClient.applyYaml(clusterResourcesRepo.dedicatedBootstrapApp())

            //Bootstrapping tenant Argocd projects
            RepoLayout tenantRepoLayout = repoSetup.tenantRepoLayout()
            k8sClient.applyYaml(Path.of(tenantRepoLayout.projectsDir(), "argocd.yaml").toString())
            k8sClient.applyYaml(Path.of(tenantRepoLayout.applicationsDir(), "bootstrap.yaml").toString())
        } else {
            // Bootstrap root application
            k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), "argocd.yaml").toString())
            k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), "bootstrap.yaml").toString())
        }

        // Delete helm-argo secrets to decouple from helm.
        // This does not delete Argo from the cluster, but you can no longer modify argo directly with helm
        // For development keeping it in helm makes it easier (e.g. for helm uninstall).
        k8sClient.delete('secret', namespace,
                new Tuple2('owner', 'helm'), new Tuple2('name', 'argocd'))
    }

    private void deployWithOperator() {
        // Apply argocd yaml from operator folder
        String argocdConfigPath = clusterResourcesRepo.operatorConfigFile()
        if (this.config.features.argocd?.values) {
            log.debug("extend Argocd.yaml with ${this.config.features.argocd.values}")
            def argocdYaml = fileSystemUtils.readYaml(
                    Path.of(clusterResourcesRepo.operatorConfigFile()))

            def result = MapUtils.deepMerge(this.config.features.argocd.values, argocdYaml)
            fileSystemUtils.writeYaml(result, new File (argocdConfigPath))
            log.debug("Argocd.yaml for operator contains ${result}")
            // reload file
            argocdConfigPath = clusterResourcesRepo.operatorConfigFile()
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
        String argocdRbacPath = clusterResourcesRepo.operatorRbacDir()
        k8sClient.applyYaml("${argocdRbacPath} --recursive")
    }


    private void deployWithHelm() {

        // Install umbrella chart from argocd/argocd
        String umbrellaChartPath = clusterResourcesRepo.helmDir()
        // Even if the Chart.lock already contains the repo, we need to add it before resolving it
        // See https://github.com/helm/helm/issues/8036#issuecomment-872502901
        List helmDependencies = fileSystemUtils.readYaml(
                Path.of(clusterResourcesRepo.chartYaml()))['dependencies']
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
                        .withRepo(repoSetup.clusterResources.repo)
                        .withSubfolder(clusterResourcesRepo.operatorRbacTenantSubfolder())
                        .generate()
            }

            //Generating Central ArgoCD RBACs for managed namespaces
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
                        .withRepo(repoSetup.clusterResources.repo)
                        .withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
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
                        .withRepo(repoSetup.clusterResources.repo)
                        .withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
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
                        .withRepo(repoSetup.clusterResources.repo)
                        .withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
                        .generate()
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

    private void createRepoCredentialsSecret(String secretName, String ns, String url, String username, String password) {
        k8sClient.createSecret('generic', secretName, ns,
                new Tuple2('url', url),
                new Tuple2('username', username),
                new Tuple2('password', password)
        )
        k8sClient.label('secret', secretName, ns,
                new Tuple2('argocd.argoproj.io/secret-type', 'repo-creds'))
    }

    protected ArgoCDRepoSetup getRepoSetup() {
        return this.repoSetup
    }

}