package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.scm.ScmHandler
import com.cloudogu.gitops.git.scmm.ScmRepoProvider
import com.cloudogu.gitops.kubernetes.rbac.RbacDefinition
import com.cloudogu.gitops.kubernetes.rbac.Role
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scmm.ScmRepoProvider
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmUrlResolver
import com.cloudogu.gitops.scmm.ScmmRepoProvider
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
    protected RepoInitializationAction exampleAppsInitializationAction
    protected RepoInitializationAction nginxHelmJenkinsInitializationAction
    protected RepoInitializationAction nginxValidationInitializationAction
    protected RepoInitializationAction brokenApplicationInitializationAction
    protected RepoInitializationAction tenantBootstrapInitializationAction
    protected File remotePetClinicRepoTmpDir
    protected List<RepoInitializationAction> petClinicInitializationActions = []

    protected K8sClient k8sClient
    protected HelmClient helmClient

    protected FileSystemUtils fileSystemUtils
    private ScmRepoProvider repoProvider

    ScmHandler scmProvider

    ArgoCD(
            Config config,
            K8sClient k8sClient,
            HelmClient helmClient,
            FileSystemUtils fileSystemUtils,
            ScmRepoProvider repoProvider,
            ScmHandler scmProvider
    ) {
        this.repoProvider = repoProvider
        this.config = config
        this.k8sClient = k8sClient
        this.helmClient = helmClient
        this.fileSystemUtils = fileSystemUtils
        this.scmProvider = scmProvider
        this.password = this.config.application.password
    }

    @Override
    boolean isEnabled() {
        config.features.argocd.active
    }

    @Override
    void enable() {

        initTenantRepos()
        initCentralRepos()

        log.debug('Cloning Repositories')

        if (config.content.examples) {
            def petclinicInitAction = createRepoInitializationAction('applications/argocd/petclinic/plain-k8s', 'argocd/petclinic-plain')
            petClinicInitializationActions += petclinicInitAction
            gitRepos += petclinicInitAction
    
            petclinicInitAction = createRepoInitializationAction('applications/argocd/petclinic/helm', 'argocd/petclinic-helm')
            petClinicInitializationActions += petclinicInitAction
            gitRepos += petclinicInitAction
    
            petclinicInitAction = createRepoInitializationAction('exercises/petclinic-helm', 'exercises/petclinic-helm')
            petClinicInitializationActions += petclinicInitAction
            gitRepos += petclinicInitAction
        
            cloneRemotePetclinicRepo()
        }

        gitRepos.forEach(repoInitializationAction -> {
            repoInitializationAction.initLocalRepo()
        })

        prepareGitOpsRepos()

        if (config.content.examples) {
            prepareApplicationNginxHelmJenkins()
            preparePetClinicRepos()
        }

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
            argocdRepoInitializationAction = createRepoInitializationAction('argocd/argocd', 'argocd/argocd')
            gitRepos += argocdRepoInitializationAction

            clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources')
            gitRepos += clusterResourcesInitializationAction
        } else {
            tenantBootstrapInitializationAction = createRepoInitializationAction('argocd/argocd/multiTenant/tenant', 'argocd/argocd')
            gitRepos += tenantBootstrapInitializationAction
        }
    }

    protected initCentralRepos() {
        if (config.multiTenant.useDedicatedInstance) {
            argocdRepoInitializationAction = createRepoInitializationAction('argocd/argocd', 'argocd/argocd',true)
            gitRepos += argocdRepoInitializationAction

            clusterResourcesInitializationAction = createRepoInitializationAction('argocd/cluster-resources', 'argocd/cluster-resources',true)
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

        if (!config.scm.internal) {
            String externalScmmUrl = ScmRepo.createScmmUrl(config)
            log.debug("Configuring all yaml files in gitops repos to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(new File(clusterResourcesInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), scmmUrlInternal, externalScmmUrl)

            if (config.content.examples) {
                replaceFileContentInYamls(new File(exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), scmmUrlInternal, externalScmmUrl)
            }
        }

        if (config.content.examples) {
            fileSystemUtils.copyDirectory("${fileSystemUtils.rootDir}/applications/argocd/nginx/helm-umbrella",
                    Path.of(exampleAppsInitializationAction.repo.getAbsoluteLocalRepoTmpDir(), 'apps/nginx-helm-umbrella/').toString())
            exampleAppsInitializationAction.replaceTemplates()

            //generating the bootstrap application in a app of app pattern for example apps in the /argocd applications folder
            if (config.multiTenant.useDedicatedInstance) {
                new ArgoApplication(
                        'example-apps',
                        ScmUrlResolver.tenantBaseUrl(config)+'argocd/example-apps',
                        namespace,
                        namespace,
                        'argocd/',
                        config.application.getTenantName())
                        .generate(tenantBootstrapInitializationAction.repo, 'applications')
            }
        }
    }

    private void prepareApplicationNginxHelmJenkins() {
        if (!config.features.secrets.active) {
            // External Secrets are not needed in example
            FileSystemUtils.deleteFile nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/k8s/staging/external-secret.yaml'
            FileSystemUtils.deleteFile nginxHelmJenkinsInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/k8s/production/external-secret.yaml'
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

        log.debug('Creating repo credential secret that is used by argocd to access repos in SCMHandler-Manager')
        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
        def repoTemplateSecretName = 'argocd-repo-creds-scmm'

        String scmmUrlForArgoCD = config.scmm.internal ? scmmUrlInternal : ScmUrlResolver.externalHost(config)
        k8sClient.createSecret('generic', repoTemplateSecretName, namespace,
                new Tuple2('url', this.scmProvider.tenant.url),
                new Tuple2('username', this.scmProvider.tenant.credentials.username),
                new Tuple2('password', this.scmProvider.tenant.credentials.password)
        )

        k8sClient.label('secret', repoTemplateSecretName, namespace,
                new Tuple2(' argocd.argoproj.io/secret-type', 'repo-creds'))

        if (config.multiTenant.useDedicatedInstance) {
            log.debug('Creating central repo credential secret that is used by argocd to access repos in SCMHandler-Manager')
            // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
            def centralRepoTemplateSecretName = 'argocd-repo-creds-central-scmm'

            k8sClient.createSecret('generic', centralRepoTemplateSecretName, config.multiTenant.centralArgocdNamespace,
                    new Tuple2('url', this.scmProvider.central.url),
                    new Tuple2('username', this.scmProvider.central.credentials.username),
                    new Tuple2('password', this.scmProvider.central.credentials.password)
            )

            k8sClient.label('secret', centralRepoTemplateSecretName, config.multiTenant.centralArgocdNamespace,
                    new Tuple2(' argocd.argoproj.io/secret-type', 'repo-creds'))
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

        if (!config.scmm.internal) {
            String externalScmmUrl = ScmUrlResolver.externalHost(config)
            log.debug("Configuring all yaml files in argocd repo to use the external scmm url: ${externalScmmUrl}")
            replaceFileContentInYamls(new File(argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir()), scmmUrlInternal, externalScmmUrl)
        }

        if (!config.application.netpols) {
            log.debug("Deleting argocd netpols.")
            FileSystemUtils.deleteFile argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/argocd/templates/allow-namespaces.yaml'
        }

        if (!config.content.examples) {
            FileSystemUtils.deleteFile argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/applications/example-apps.yaml'
            FileSystemUtils.deleteFile argocdRepoInitializationAction.repo.getAbsoluteLocalRepoTmpDir() + '/projects/example-apps.yaml'
        }

        argocdRepoInitializationAction.repo.commitAndPush("Initial Commit")
    }

    protected RepoInitializationAction createRepoInitializationAction(String localSrcDir, String scmRepoTarget) {
        new RepoInitializationAction(config, repoProvider.getRepo(scmRepoTarget), localSrcDir)
    }


    private void replaceFileContentInYamls(File folder, String from, String to) {
        fileSystemUtils.getAllFilesFromDirectoryWithEnding(folder.absolutePath, ".yaml").forEach(file -> {
            fileSystemUtils.replaceFileContent(file.absolutePath, from, to)
        })
    }
}