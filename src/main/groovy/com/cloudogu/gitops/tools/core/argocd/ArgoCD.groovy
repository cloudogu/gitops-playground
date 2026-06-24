package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.application.repository.RepositoryProvisioning
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.helm.HelmClient
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.RbacDefinition
import com.cloudogu.gitops.infrastructure.kubernetes.rbac.Role
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton
import org.springframework.security.crypto.bcrypt.BCrypt

import java.nio.file.Path

@Slf4j
@Singleton
@Order(100)
class ArgoCD extends Tool {

	private final String namespace
	private final Config config
	private final K8sClient k8sClient
	private final HelmClient helmClient
	private final FileSystemUtils fileSystemUtils
	private final GitHandler gitHandler
	private final RepositoryProvisioning repositoryProvisioning
	private final String password

	private RepositoryWorkspace repositoryWorkspace
	private ArgoCDRepoSetup repoSetup
	private ArgoCDRepoLayout clusterResourcesRepo

	ArgoCD(
		Config config,
		K8sClient k8sClient,
		HelmClient helmClient,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		RepositoryProvisioning repositoryProvisioning
	) {
		this.config = config
		this.k8sClient = k8sClient
		this.helmClient = helmClient
		this.fileSystemUtils = fileSystemUtils
		this.gitHandler = gitHandler
		this.repositoryProvisioning = repositoryProvisioning
		this.password = config.application.password
		this.namespace = "${config.application.namePrefix}${config.features.argocd.namespace}"
	}

	@Override
	boolean isEnabled() {
		config.features.argocd.active
	}

	@Override
	void postConfigInit(Config configToSet) {
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
		this.repositoryWorkspace = repositoryProvisioning.provideWorkspace()

		this.repoSetup = ArgoCDRepoSetup.create(
			config,
			fileSystemUtils,
			gitHandler,
			repositoryWorkspace
		)

		this.clusterResourcesRepo = repoSetup.clusterRepoLayout()

		log.debug('Preparing ArgoCD repository content')
		repoSetup.prepareRepositories()

		repositoryProvisioning.publishClusterResourcesAndTenantBootstrapRepositoryChanges(
			'argocd',
			'Update ArgoCD repository content'
		)

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

				def argocdYaml = fileSystemUtils.readYaml(Path.of(argocdConfigPath))
				def result = MapUtils.deepMerge(this.config.features.argocd.values, argocdYaml)

				fileSystemUtils.writeYaml(result, new File(argocdConfigPath))
				log.debug("Argocd values.yaml contains ${result}")
			}

			deployWithHelm()
		}

		if (config.multiTenant.useDedicatedInstance) {
			k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), "tenant.yaml").toString())
			k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), "bootstrap.yaml").toString())

			ArgoCDRepoLayout tenantRepoLayout = repoSetup.tenantRepoLayout()
			k8sClient.applyYaml(Path.of(tenantRepoLayout.projectsDir(), "argocd.yaml").toString())
			k8sClient.applyYaml(Path.of(tenantRepoLayout.applicationsDir(), "bootstrap.yaml").toString())
		} else {
			k8sClient.applyYaml(Path.of(clusterResourcesRepo.projectsDir(), "argocd.yaml").toString())
			k8sClient.applyYaml(Path.of(clusterResourcesRepo.applicationsDir(), "bootstrap.yaml").toString())
		}

		k8sClient.delete(
			'secret',
			namespace,
			new Tuple2('owner', 'helm'),
			new Tuple2('name', 'argocd')
		)
	}

	private void deployWithOperator() {
		String argocdConfigPath = clusterResourcesRepo.operatorConfigFile()

		if (this.config.features.argocd?.values) {
			log.debug("extend Argocd.yaml with ${this.config.features.argocd.values}")

			def argocdYaml = fileSystemUtils.readYaml(Path.of(clusterResourcesRepo.operatorConfigFile()))
			def result = MapUtils.deepMerge(this.config.features.argocd.values, argocdYaml)

			fileSystemUtils.writeYaml(result, new File(argocdConfigPath))
			log.debug("Argocd.yaml for operator contains ${result}")

			argocdConfigPath = clusterResourcesRepo.operatorConfigFile()
		}

		k8sClient.applyYaml(argocdConfigPath)

		k8sClient.waitForResourcePhase("argocd", "argocd", namespace, "Available")

		log.debug("Setting new argocd admin password")

		k8sClient.patch(
			'secret',
			'argocd-cluster',
			namespace,
			[stringData: ['admin.password': password]]
		)

		String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))

		k8sClient.patch(
			'secret',
			'argocd-secret',
			namespace,
			[stringData: ['admin.password': bcryptArgoCDPassword]]
		)

		updatingArgoCDManagedNamespaces()

		log.debug("Apply RBAC permissions for ArgoCD in all managed namespaces imperatively")
		k8sClient.applyYaml(clusterResourcesRepo.operatorRbacDir())
	}

	private void deployWithHelm() {
		String umbrellaChartPath = clusterResourcesRepo.helmDir()

		List helmDependencies = fileSystemUtils
			.readYaml(Path.of(clusterResourcesRepo.chartYaml()))['dependencies']
			.collect { it }

		helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
		helmClient.dependencyBuild(umbrellaChartPath)
		helmClient.upgrade('argocd', umbrellaChartPath, [namespace: namespace])

		log.debug("Setting new argocd admin password")

		String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))

		k8sClient.patch(
			'secret',
			'argocd-secret',
			namespace,
			[stringData: ['admin.password': bcryptArgoCDPassword]]
		)
	}

	void updatingArgoCDManagedNamespaces() {
		log.debug("Updating managed namespaces in ArgoCD configuration secret.")

		def namespaceList = !config.multiTenant.useDedicatedInstance ?
		                    config.application.namespaces.activeNamespaces :
		                    config.application.namespaces.tenantNamespaces

		k8sClient.patch(
			'secret',
			'argocd-default-cluster-config',
			namespace,
			[stringData: ['namespaces': namespaceList.join(',')]]
		)

		if (config.multiTenant.useDedicatedInstance) {
			String base64Namespaces = k8sClient.getArgoCDNamespacesSecret(
				'argocd-default-cluster-config',
				config.multiTenant.centralArgocdNamespace
			)

			byte[] decodedBytes = Base64.decoder.decode(base64Namespaces)
			String decoded = new String(decodedBytes, "UTF-8")

			def decodedList = decoded?.split(',') as List ?: []
			def activeList = config.application.namespaces.activeNamespaces?.flatten() as List ?: []
			def merged = (decodedList + activeList).unique().join(',')

			log.debug("Updating Central Argocd 'argocd-default-cluster-config' secret")

			k8sClient.patch(
				'secret',
				'argocd-default-cluster-config',
				config.multiTenant.centralArgocdNamespace,
				[stringData: ['namespaces': merged]]
			)
		}
	}

	private void generateRBAC() {
		log.debug("Generate RBAC permissions for ArgoCD in all managed namespaces")

		if (config.multiTenant.useDedicatedInstance) {
			for (String ns : config.application.namespaces.tenantNamespaces) {
				new RbacDefinition(Role.Variant.ARGOCD)
					.withName("argocd")
					.withNamespace(ns)
					.withServiceAccountsFrom(
						namespace,
						[
							"argocd-argocd-server",
							"argocd-argocd-application-controller",
							"argocd-applicationset-controller"
						]
					)
					.withConfig(config)
					.withRepo(repositoryWorkspace.clusterResourcesRepository)
					.withSubfolder(clusterResourcesRepo.operatorRbacTenantSubfolder())
					.generate()
			}

			for (String ns : config.application.namespaces.activeNamespaces) {
				log.debug("Generate RBAC permissions for centralized ArgoCD to access tenant ArgoCDs")

				new RbacDefinition(Role.Variant.ARGOCD)
					.withName('argocd-central')
					.withNamespace(ns)
					.withServiceAccountsFrom(
						config.multiTenant.centralArgocdNamespace,
						[
							"argocd-argocd-server",
							"argocd-argocd-application-controller",
							"argocd-applicationset-controller"
						]
					)
					.withConfig(config)
					.withRepo(repositoryWorkspace.clusterResourcesRepository)
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
						[
							"argocd-argocd-server",
							"argocd-argocd-application-controller",
							"argocd-applicationset-controller"
						]
					)
					.withConfig(config)
					.withRepo(repositoryWorkspace.clusterResourcesRepository)
					.withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
					.generate()
			}

			if (config.application.clusterAdmin) {
				new RbacDefinition(Role.Variant.CLUSTER_ADMIN)
					.withName("argocd-cluster-admin")
					.withNamespace(namespace)
					.withServiceAccountsFrom(
						namespace,
						[
							"argocd-argocd-server",
							"argocd-argocd-application-controller",
							"argocd-applicationset-controller"
						]
					)
					.withConfig(config)
					.withRepo(repositoryWorkspace.clusterResourcesRepository)
					.withSubfolder(clusterResourcesRepo.operatorRbacSubfolder())
					.generate()
			}
		}
	}

	protected void createSCMCredentialsSecret() {
		log.debug("Creating repo credential secret that is used by argocd to access repos in ${config.scm.scmProviderType.toString()}")

		createRepoCredentialsSecret(
			'argocd-repo-creds-scm',
			namespace,
			gitHandler.tenant.url,
			gitHandler.tenant.credentials.username,
			gitHandler.tenant.credentials.password
		)

		if (config.multiTenant.useDedicatedInstance) {
			log.debug("Creating central repo credential secret that is used by argocd to access repos in ${config.scm.scmProviderType.toString()}")

			createRepoCredentialsSecret(
				'argocd-repo-creds-central-scm',
				config.multiTenant.centralArgocdNamespace,
				gitHandler.central.url,
				gitHandler.central.credentials.username,
				gitHandler.central.credentials.password
			)
		}
	}

	private void createRepoCredentialsSecret(
		String secretName,
		String ns,
		String url,
		String username,
		String password
	) {
		k8sClient.createSecret(
			'generic',
			secretName,
			ns,
			new Tuple2('url', url),
			new Tuple2('username', username),
			new Tuple2('password', password)
		)

		k8sClient.label(
			'secret',
			secretName,
			ns,
			new Tuple2('argocd.argoproj.io/secret-type', 'repo-creds')
		)
	}

	protected ArgoCDRepoSetup getRepoSetup() {
		return this.repoSetup
	}
}