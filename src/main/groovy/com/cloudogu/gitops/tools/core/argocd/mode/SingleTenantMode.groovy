package com.cloudogu.gitops.tools.core.argocd.mode

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.RbacDefinition
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.Role
import com.cloudogu.gitops.tools.core.argocd.ArgoCDRepoLayout

import java.nio.file.Path
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
class SingleTenantMode implements DeploymentMode {

	private static final List<String> ARGOCD_SERVICE_ACCOUNTS = ['argocd-argocd-server',
	                                                             'argocd-argocd-application-controller',
	                                                             'argocd-applicationset-controller']

	private final Config config
	private final K8sClient k8sClient
	private final GitHandler gitHandler
	private final RepositoryWorkspace repositoryWorkspace
	private final ArgoCDRepoLayout clusterResourcesRepo
	private final String namespace

	SingleTenantMode(Config config,
		K8sClient k8sClient,
		GitHandler gitHandler,
		RepositoryWorkspace repositoryWorkspace,
		ArgoCDRepoLayout clusterResourcesRepo,
		String namespace) {
		this.config = config
		this.k8sClient = k8sClient
		this.gitHandler = gitHandler
		this.repositoryWorkspace = repositoryWorkspace
		this.clusterResourcesRepo = clusterResourcesRepo
		this.namespace = namespace
	}

	@Override
	void createSCMCredentialsSecret() {
		log.debug("Creating repo credential secret that is used by ArgoCD to access repos in ${config.scm.scmProviderType.toString()}")

		createRepoCredentialsSecret('argocd-repo-creds-scm',
			namespace,
			gitHandler.tenant.url,
			gitHandler.tenant.credentials.username,
			gitHandler.tenant.credentials.password)
	}

	@Override
	void generateRBAC() {
		log.debug('Generate RBAC permissions for ArgoCD in all managed namespaces')

		for (String ns : config.application.namespaces.activeNamespaces) {
			new RbacDefinition(Role.Variant.ARGOCD)
				.withName('argocd')
				.withNamespace(ns)
				.withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
				.withConfig(config)
				.withRepo(repositoryWorkspace.clusterResourcesRepository)
				.withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
				.generate()
		}

		if (config.application.clusterAdmin) {
			new RbacDefinition(Role.Variant.CLUSTER_ADMIN)
				.withName('argocd-cluster-admin')
				.withNamespace(namespace)
				.withServiceAccountsFrom(namespace, ARGOCD_SERVICE_ACCOUNTS)
				.withConfig(config)
				.withRepo(repositoryWorkspace.clusterResourcesRepository)
				.withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
				.generate()
		}
	}

	@Override
	void updateManagedNamespaces() {
		log.debug('Updating managed namespaces in ArgoCD configuration secret.')

		k8sClient.patch('secret',
			'argocd-default-cluster-config',
			namespace,
			[stringData: ['namespaces': config.application.namespaces.activeNamespaces.join(',')]])
	}

	@Override
	void applyBootstrapResources() {
		k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), 'argocd.yaml').toString())
		k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), 'bootstrap.yaml').toString())
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