package com.cloudogu.gitops.tools.core.argocd.mode

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.RbacDefinition
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.Role
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoSetup

import java.nio.file.Path
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
class DedicatedMultiTenantMode implements DeploymentMode {

	private static final List<String> ARGOCD_SERVICE_ACCOUNTS = ['argocd-argocd-server',
	                                                             'argocd-argocd-application-controller',
	                                                             'argocd-applicationset-controller']

	private final Config config
	private final K8sClient k8sClient
	private final GitHandler gitHandler
	private final RepositoryWorkspace repositoryWorkspace
	private final ArgoCDRepoSetup repoSetup
	private final ArgoCDRepoLayout clusterResourcesRepo
	private final String namespace

	DedicatedMultiTenantMode(Config config,
		K8sClient k8sClient,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace,
		ArgoCDRepoSetup repoSetup,
		ArgoCDRepoLayout clusterResourcesRepo,
		String namespace) {
		this.config = config
		this.k8sClient = k8sClient
		this.gitHandler = gitHandler
		this.repositoryWorkspace = repositoryWorkspace
		this.repoSetup = repoSetup
		this.clusterResourcesRepo = clusterResourcesRepo
		this.namespace = namespace
	}

	@Override
	void createSCMCredentialsSecret() {
		log.debug("Creating tenant repo credential secret that is used by tenant ArgoCD to access repos in ${config.scm.scmProviderType.toString()}")

		createRepoCredentialsSecret('argocd-repo-creds-scm',
			namespace,
			gitHandler.tenant.url,
			gitHandler.tenant.credentials.username,
			gitHandler.tenant.credentials.password)

		log.debug("Creating central repo credential secret that is used by central ArgoCD to access repos in ${config.scm.scmProviderType.toString()}")

		createRepoCredentialsSecret('argocd-repo-creds-central-scm',
			config.multiTenant.centralArgocdNamespace,
			gitHandler.central.url,
			gitHandler.central.credentials.username,
			gitHandler.central.credentials.password)
	}

	@Override
	void generateRBAC() {
		log.debug('Generate RBAC permissions for tenant ArgoCD and central ArgoCD.')

		generateTenantArgoCDRBAC()
		generateCentralArgoCDRBAC()
	}

	@Override
	void updateManagedNamespaces() {
		log.debug('Updating managed namespaces in tenant ArgoCD configuration secret.')

		k8sClient.patch('secret',
			'argocd-default-cluster-config',
			namespace,
			[stringData: ['namespaces': config.application.namespaces.tenantNamespaces.join(',')]])

		updateCentralManagedNamespaces()
	}

	@Override
	void applyBootstrapResources() {
		// Bootstrapping dedicated instance
		k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), 'tenant.yaml').toString())
		k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), 'bootstrap.yaml').toString())

		ArgoCDRepoLayout tenantRepoLayout = repoSetup.tenantRepoLayout()
		k8sClient.applyYaml(Path.of(tenantRepoLayout.projectsDir(), 'argocd.yaml').toString())
		k8sClient.applyYaml(Path.of(tenantRepoLayout.applicationsDir(), 'bootstrap.yaml').toString())
	}

	private void generateTenantArgoCDRBAC() {
		for (String ns : config.application.namespaces.tenantNamespaces) {
			new RbacDefinition(Role.Variant.ARGOCD)
				.withName('argocd')
				.withNamespace(ns)
				.withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
				.withConfig(config)
				.withRepo(repositoryWorkspace.clusterResourcesRepository)
				.withSubfolder(clusterResourcesRepo.operatorRbacTenantSubfolder())
				.generate()
		}
	}

	private void generateCentralArgoCDRBAC() {
		for (String ns : config.application.namespaces.activeNamespaces) {
			log.debug('Generate RBAC permissions for centralized ArgoCD to access tenant ArgoCDs')

			new RbacDefinition(Role.Variant.ARGOCD)
				.withName('argocd-central')
				.withNamespace(ns)
				.withServiceAccountsFrom(config.multiTenant.centralArgocdNamespace, ARGOCD_SERVICE_ACCOUNTS)
				.withConfig(config)
				.withRepo(repositoryWorkspace.clusterResourcesRepository)
				.withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
				.generate()
		}
	}

	private void updateCentralManagedNamespaces() {
		String base64Namespaces = k8sClient.getArgoCDNamespacesSecret('argocd-default-cluster-config',
			config.multiTenant.centralArgocdNamespace)

		byte[] decodedBytes = Base64.decoder.decode(base64Namespaces)
		String decoded = new String(decodedBytes, 'UTF-8')

		def decodedList = decoded?.split(',') as List ?: []
		def activeList = config.application.namespaces.activeNamespaces?.flatten() as List ?: []
		def merged = (decodedList + activeList).unique().join(',')

		log.debug("Updating Central Argocd 'argocd-default-cluster-config' secret")

		k8sClient.patch('secret',
			'argocd-default-cluster-config',
			config.multiTenant.centralArgocdNamespace,
			[stringData: ['namespaces': merged]])
	}

	private void createRepoCredentialsSecret(String secretName,
		String ns,
		String url,
		String username,
		String password) {
		k8sClient.createSecret('generic',
			secretName,
			ns,
			new Tuple2('url', url),
			new Tuple2('username', username),
			new Tuple2('password', password))

		k8sClient.label('secret',
			secretName,
			ns,
			new Tuple2('argocd.argoproj.io/secret-type', 'repo-creds'))
	}
}