package com.cloudogu.gitops.tools.core.argocd

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.infrastructure.helm.HelmClient
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.core.argocd.mode.DeploymentMode
import com.cloudogu.gitops.tools.core.argocd.mode.DeploymentModeFactory
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils

import io.micronaut.core.annotation.Order

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.springframework.security.crypto.bcrypt.BCrypt

@CompileStatic
@Slf4j
@Singleton
@Order(100)
class ArgoCD extends Tool {

	private final K8sClient k8sClient
	private final HelmClient helmClient
	private final FileSystemUtils fileSystemUtils
	private final GitHandler gitHandler
	private final DeploymentModeFactory deploymentModeFactory

	private String password
	private String namespace
	private ArgoCDRepoSetup repoSetup
	private ArgoCDRepoLayout clusterResourcesRepo
	private DeploymentMode deploymentMode

	ArgoCD(K8sClient k8sClient,
		HelmClient helmClient,
		FileSystemUtils fileSystemUtils,
		GitHandler gitHandler,
		DeploymentModeFactory deploymentModeFactory) {
		this.k8sClient = k8sClient
		this.helmClient = helmClient
		this.fileSystemUtils = fileSystemUtils
		this.gitHandler = gitHandler
		this.deploymentModeFactory = deploymentModeFactory
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return context.config.features.argocd.active
	}

	@Override
	protected void preDeploy() {
		this.namespace = resolveNamespace(context)
		this.password = config.application.password

		this.repoSetup = ArgoCDRepoSetup.create(context,
			fileSystemUtils,
			gitHandler,
			repositoryWorkspace)

		this.clusterResourcesRepo = repoSetup.clusterRepoLayout()

		this.deploymentMode = deploymentModeFactory.create(context,
			config,
			k8sClient,
			gitHandler,
			repositoryWorkspace,
			repoSetup,
			clusterResourcesRepo,
			namespace)

		log.debug('Preparing ArgoCD repository content')
		repoSetup.prepareRepositories()

		log.debug('Creating namespaces')
		k8sClient.createNamespaces(config.application.namespaces.activeNamespaces.toList())

		deploymentMode.createSCMCredentialsSecret()
		createNotificationSecretIfRequired()

		if (config.features.argocd.operator) {
			deploymentMode.generateRBAC()
		} else {
			mergeHelmValuesIfConfigured()
		}
	}

	@Override
	protected void deploy() {
		log.debug('Installing Argo CD')

		if (config.features.argocd.operator) {
			deployWithOperator()
		} else {
			deployWithHelm()
		}
	}

	@Override
	protected void postDeploy() {
		deploymentMode.applyBootstrapResources()
		deleteHelmArgoSecrets()
	}

	@Override
	protected void publishChanges() {
		repositoryWorkspace.commitAndPushClusterResourcesAndTenantBootstrapChanges('Update ArgoCD repository content')
	}

	@Override
	protected String resolveNamespace(DeploymentContext context) {
		return "${context.config.application.namePrefix}${context.config.features.argocd.namespace}"
	}

	@Override
	void postConfigInit(Config configToSet) {
		// Exit early if not in operator mode or if env list is empty
		if (!configToSet.features.argocd.operator || !configToSet.features.argocd.env) {
			log.debug('Skipping features.argocd.env validation: operator mode is disabled or env list is empty.')
			return
		}

		List<Map> env = configToSet.features.argocd.env as List<Map<String, String>>

		log.info('Validating env list in features.argocd.env with {} entries.', env.size())

		env.each { map ->
			if (!(map instanceof Map) || !map.containsKey('name') || !map.containsKey('value')) {
				throw new IllegalArgumentException("Each env variable in features.argocd.env must be a map with 'name' and 'value'. Invalid entry found: $map")
			}
		}

		log.info('Env list validation for features.argocd.env completed successfully.')
	}

	private void createNotificationSecretIfRequired() {
		if (config.features.mail.smtpUser || config.features.mail.smtpPassword) {
			k8sClient.createSecret('generic',
				'argocd-notifications-secret',
				namespace,
				new Tuple2('email-username', config.features.mail.smtpUser),
				new Tuple2('email-password', config.features.mail.smtpPassword))
		}
	}

	private void mergeHelmValuesIfConfigured() {
		if (!this.config.features.argocd?.values) {
			return
		}

		String argocdConfigPath = clusterResourcesRepo.helmValuesFile()
		log.debug("extend Argocd values.yaml with ${this.config.features.argocd.values}")

		def argocdYaml = fileSystemUtils.readYaml(Path.of(argocdConfigPath))
		def result = MapUtils.deepMerge(this.config.features.argocd.values, argocdYaml)

		fileSystemUtils.writeYaml(result, new File(argocdConfigPath))
		log.debug("Argocd values.yaml contains ${result}")
	}

	private void deleteHelmArgoSecrets() {
		// Delete helm-argo secrets to decouple from helm.
		// This does not delete Argo from the cluster, but you can no longer modify argo directly with helm.
		// For development keeping it in helm makes it easier, e.g. for helm uninstall.
		k8sClient.delete('secret',
			namespace,
			new Tuple2('owner', 'helm'),
			new Tuple2('name', 'argocd'))
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

		// ArgoCD is not installed until the ArgoCD-Operator did his job.
		// This can take some time, so we wait for the status of the custom resource to become "Available"
		k8sClient.waitForResourcePhase('argocd', 'argocd', namespace, 'Available')

		updateAdminPasswordForOperator()

		deploymentMode.updateManagedNamespaces()

		log.debug('Apply RBAC permissions for ArgoCD in all managed namespaces imperatively')
		k8sClient.applyYaml(clusterResourcesRepo.operatorRbacDir())
	}

	private void updateAdminPasswordForOperator() {
		log.debug('Setting new argocd admin password')

		// Set admin password imperatively here instead of operator/argocd.yaml, because we don't want it to show in git repo.
		// The Operator uses an extra secret to store the admin Password, which is not bcrypted.
		k8sClient.patch('secret', 'argocd-cluster', namespace,
			[stringData: ['admin.password': password]])

		// In newer Versions ArgoCD Operator uses the password in argocd-cluster secret only as generated initial password,
		// but we want to set our own admin password so we set the password in both Secrets for consistency.
		updateBcryptAdminPassword()
	}

	private void deployWithHelm() {
		String umbrellaChartPath = clusterResourcesRepo.helmDir()

		// Even if the Chart.lock already contains the repo, we need to add it before resolving it.
		// See https://github.com/helm/helm/issues/8036#issuecomment-872502901
		List helmDependencies = fileSystemUtils
			.readYaml(Path.of(clusterResourcesRepo.chartYaml()))['dependencies']
			.collect { it }

		helmClient.addRepo('argo', helmDependencies[0]['repository'] as String)
		helmClient.dependencyBuild(umbrellaChartPath)
		helmClient.upgrade('argocd', umbrellaChartPath, [namespace: namespace])

		updateBcryptAdminPassword()
	}

	private void updateBcryptAdminPassword() {
		log.debug('Setting new argocd admin password')

		String bcryptArgoCDPassword = BCrypt.hashpw(password, BCrypt.gensalt(4))

		k8sClient.patch('secret',
			'argocd-secret',
			namespace,
			[stringData: ['admin.password': bcryptArgoCDPassword]])
	}

	protected ArgoCDRepoSetup getRepoSetup() {
		return this.repoSetup
	}
}