package com.cloudogu.gitops.application.content

import static com.cloudogu.gitops.config.Config.ContentRepoType
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.orchestration.GitHandler
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Config.OverwriteMode
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeployer
import com.cloudogu.gitops.infrastructure.deployment.helm.HelmToolDeploymentRequest
import com.cloudogu.gitops.infrastructure.git.GitRepo
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient
import com.cloudogu.gitops.tools.common.Tool
import com.cloudogu.gitops.tools.core.Jenkins
import com.cloudogu.gitops.utils.AllowListFreemarkerObjectWrapper
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine

import java.nio.file.Path
import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

import com.fasterxml.jackson.annotation.JsonIgnore
import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

@Slf4j
@Singleton
// We want to evaluate content last, to allow for changing all other repos.
class ContentLoader extends Tool {

	private final K8sClient k8sClient
	private final GitRepoFactory repoProvider
	private final Jenkins jenkins
	private final GitHandler gitHandler
	private final FileSystemUtils fileSystemUtils
	private final HelmToolDeployer helmToolDeployer

	// Set by lazy initialization.
	private TemplatingEngine templatingEngine

	// Used to clone repos in validation phase.
	private List<RepoCoordinate> cachedRepoCoordinates = new ArrayList<>()

	protected File mergedReposFolder

	// For security reasons, credentials are stored at runtime instead of in the config.
	@JsonIgnore
	UsernamePasswordCredentialsProvider credentialsProvider

	ContentLoader(K8sClient k8sClient,
		GitRepoFactory repoProvider,
		Jenkins jenkins,
		GitHandler gitHandler,
		FileSystemUtils fileSystemUtils,
		HelmToolDeployer helmToolDeployer) {
		this.k8sClient = k8sClient
		this.repoProvider = repoProvider
		this.jenkins = jenkins
		this.gitHandler = gitHandler
		this.fileSystemUtils = fileSystemUtils
		this.helmToolDeployer = helmToolDeployer
	}

	@Override
	boolean isEnabled(DeploymentContext context) {
		return true
	}

	@Override
	protected void deploy() {
		clearCache()

		cachedRepoCoordinates = cloneContentRepos()

		createImagePullSecrets()
		createContentRepos()
		deployHelmReleasesFromContent()
	}

	@Override
	void validate() {}

	@Override
	void preConfigInit(Config configToSet) {
		configToSet.content.repos.each { repo ->
			if (!repo.url) {
				throw new RuntimeException('content.repos requires a url parameter.')
			}

			if (repo.target && repo.target.count('/') == 0) {
				throw new RuntimeException('content.target needs / to separate namespace/group ' + "from repo name. Repo: ${repo.url}")
			}

			switch (repo.type) {
				case ContentRepoType.COPY:
					if (!repo.target) {
						throw new RuntimeException("content.repos.type ${ContentRepoType.COPY} " + 'requires content.repos.target to be set. ' + "Repo: ${repo.url}")
					}
					break

				case ContentRepoType.FOLDER_BASED:
					if (repo.target) {
						throw new RuntimeException("content.repos.type ${ContentRepoType.FOLDER_BASED} " + 'does not support target parameter. ' + "Repo: ${repo.url}")
					}

					if (repo.targetRef) {
						throw new RuntimeException("content.repos.type ${ContentRepoType.FOLDER_BASED} " + 'does not support targetRef parameter. ' + "Repo: ${repo.url}")
					}
					break

				case ContentRepoType.MIRROR:
					if (!repo.target) {
						throw new RuntimeException("content.repos.type ${ContentRepoType.MIRROR} " + 'requires content.repos.target to be set. ' + "Repo: ${repo.url}")
					}

					if (repo.path != ContentRepositorySchema.DEFAULT_PATH) {
						throw new RuntimeException("content.repos.type ${ContentRepoType.MIRROR} " + 'does not support path. ' + "Current path: ${repo.path}. Repo: ${repo.url}")
					}

					if (repo.templating) {
						throw new RuntimeException("content.repos.type ${ContentRepoType.MIRROR} " + 'does not support templating. ' + "Repo: ${repo.url}")
					}
					break
			}
		}
	}

	protected void deployHelmReleasesFromContent() {
		if (!config.content?.helmReleases) {
			log.debug('No content.helmReleases configured - skipping.')
			return
		}

		config.content.helmReleases.each { helmRelease ->
			String version = helmRelease.version?.trim()

			if (!version) {
				version = '*'
			}

			Map<String, Object> fileValues = [:]

			if (helmRelease.valuesPath?.trim()) {
				/*
				 * This is a plain YAML file and not a Freemarker template.
				 */
				fileValues = (fileSystemUtils.readYaml(Path.of(helmRelease.valuesPath)) ?: [:]) as Map<String, Object>
			}

			Map<String, Object> inlineValues =
				(helmRelease.values ?: [:])
					as Map<String, Object>

			/*
			 * File values are the base.
			 * Inline values override values from the file.
			 */
			Map<String, Object> mergedValues =
				MapUtils.deepMerge(inlineValues,
					fileValues)

			Config.HelmConfigWithValues helmConfig =
				new Config.HelmConfigWithValues(repoURL: helmRelease.repoURL,
					chart: helmRelease.chart,
					version: version,
					values: mergedValues)

			String releaseName =
				(helmRelease.releaseName ?: helmRelease.name)
					as String

			HelmToolDeploymentRequest request =
				new HelmToolDeploymentRequest(helmRelease.name as String,
					releaseName,
					helmRelease.namespace as String,
					helmConfig,
					'')

			helmToolDeployer.deploy(request,
				context,
				repositoryWorkspace)

			repositoryWorkspace
				.commitAndPushClusterResourcesChanges("Update ${releaseName} GitOps resources")
		}
	}

	void createImagePullSecrets() {
		if (!config.registry.createImagePullSecrets) {
			return
		}

		String registryUsername =
			config.registry.readOnlyUsername ?: config.registry.username

		String registryPassword =
			config.registry.readOnlyPassword ?: config.registry.password

		config.content.namespaces.each { String namespace ->
			String registrySecretName = 'registry'

			k8sClient.createNamespace(namespace)

			k8sClient.createImagePullSecret(registrySecretName,
				namespace,
				config.registry.url,
				registryUsername,
				registryPassword)

			k8sClient.patch('serviceaccount',
				'default',
				namespace,
				[imagePullSecrets: [[name: registrySecretName]]])

			if (config.registry.twoRegistries) {
				k8sClient.createImagePullSecret('proxy-registry',
					namespace,
					config.registry.proxyUrl,
					config.registry.proxyUsername,
					config.registry.proxyPassword)
			}
		}
	}

	void createContentRepos() {
		if (cachedRepoCoordinates.empty) {
			cachedRepoCoordinates = cloneContentRepos()
		}

		pushTargetRepos(cachedRepoCoordinates)
		clearCache()
	}

	protected List<RepoCoordinate> cloneContentRepos() {
		mergedReposFolder = File.createTempDir('gitops-playground-based-content-repos-')

		List<RepoCoordinate> repoCoordinates = []

		log.debug('Aggregating structure for all ' + "${config.content.repos.size()} repos.")

		config.content.repos.each { repoConfig ->
			createRepoCoordinates(repoConfig,
				mergedReposFolder,
				repoCoordinates)
		}

		return repoCoordinates
	}

	private TemplatingEngine getTemplatingEngine() {
		if (templatingEngine == null) {
			templatingEngine = new TemplatingEngine()
		}

		return templatingEngine
	}

	private void createRepoCoordinates(ContentRepositorySchema repoConfig,
		File mergedReposFolder,
		List<RepoCoordinate> repoCoordinates) {
		File repoTmpDir =
			File.createTempDir('gitops-playground-content-repo-')

		log.debug("Cloning content repo, ${repoConfig.url}, " + "revision ${repoConfig.ref}, " + "path ${repoConfig.path}, " + "overwriteMode ${repoConfig.overwriteMode}")

		if (repoConfig.credentials?.username != null && repoConfig.credentials?.password != null) {
			credentialsProvider = new UsernamePasswordCredentialsProvider(repoConfig.credentials.username,
				repoConfig.credentials.password)
		} else if (repoConfig.credentials?.secretName && repoConfig.credentials?.secretNamespace) {
			Credentials credentials =
				k8sClient.getCredentialsFromSecret(repoConfig.credentials)

			credentialsProvider = new UsernamePasswordCredentialsProvider(credentials.username,
				credentials.password)
		}

		cloneToLocalFolder(repoConfig, repoTmpDir)

		File contentRepoDir =
			new File(repoTmpDir, repoConfig.path)

		applyTemplatingIfApplicable(repoConfig,
			contentRepoDir)

		switch (repoConfig.type) {
			case ContentRepoType.FOLDER_BASED:
				createRepoCoordinatesForTypeFolderBased(repoConfig,
					repoTmpDir,
					contentRepoDir,
					mergedReposFolder,
					repoCoordinates)
				repoTmpDir.deleteDir()
				break

			case ContentRepoType.COPY:
				createRepoCoordinatesForTypeCopy(repoConfig,
					contentRepoDir,
					mergedReposFolder,
					repoTmpDir,
					repoCoordinates)
				repoTmpDir.deleteDir()
				break

			case ContentRepoType.MIRROR:
				createRepoCoordinateForTypeMirror(repoConfig,
					repoTmpDir,
					repoCoordinates)
				break
		}

		log.debug('Finished cloning content repos. ' + "repoCoordinates=${repoCoordinates}")
	}

	private static void createRepoCoordinatesForTypeCopy(ContentRepositorySchema repoConfig,
		File contentRepoDir,
		File mergedReposFolder,
		File repoTmpDir,
		List<RepoCoordinate> repoCoordinates) {
		String namespace =
			repoConfig.target.split('/')[0]

		String repoName =
			repoConfig.target.split('/')[1]

		RepoCoordinate repoCoordinate =
			mergeRepoDirs(contentRepoDir,
				namespace,
				repoName,
				mergedReposFolder,
				repoConfig)

		repoCoordinate.refIsTag = GitRepo.isTag(repoTmpDir,
			repoConfig.ref)

		addRepoCoordinates(repoCoordinates,
			repoCoordinate)
	}

	private static void createRepoCoordinatesForTypeFolderBased(ContentRepositorySchema repoConfig,
		File repoTmpDir,
		File contentRepoDir,
		File mergedReposFolder,
		List<RepoCoordinate> repoCoordinates) {
		boolean refIsTag =
			GitRepo.isTag(repoTmpDir,
				repoConfig.ref)

		findRepoDirectories(contentRepoDir)
			.each { File contentRepoNamespaceDir ->
				findRepoDirectories(contentRepoNamespaceDir)
					.each { File contentRepoFolder ->
						String namespace =
							contentRepoNamespaceDir.name

						String repoName =
							contentRepoFolder.name

						RepoCoordinate repoCoordinate =
							mergeRepoDirs(contentRepoFolder,
								namespace,
								repoName,
								mergedReposFolder,
								repoConfig)

						repoCoordinate.refIsTag = refIsTag

						addRepoCoordinates(repoCoordinates,
							repoCoordinate)
					}
			}
	}

	private static void createRepoCoordinateForTypeMirror(ContentRepositorySchema repoConfig,
		File repoTmpDir,
		List<RepoCoordinate> repoCoordinates) {
		String namespace =
			repoConfig.target.split('/')[0]

		String repoName =
			repoConfig.target.split('/')[1]

		RepoCoordinate repoCoordinate =
			new RepoCoordinate(namespace: namespace,
				repoName: repoName,
				clonedContentRepo: repoTmpDir,
				repoConfig: repoConfig,
				refIsTag: GitRepo.isTag(repoTmpDir,
					repoConfig.ref))

		addRepoCoordinates(repoCoordinates,
			repoCoordinate)
	}

	/**
	 * Merges the files from src into mergeRepoFolder/namespace/name.
	 *
	 * Existing coordinates with another overwrite mode are replaced.
	 * The last configured repository wins.	*/
	private static RepoCoordinate mergeRepoDirs(File src,
		String namespace,
		String repoName,
		File mergedRepoFolder,
		ContentRepositorySchema repoConfig) {
		File target =
			new File(new File(mergedRepoFolder,
				namespace),
				repoName)

		log.debug("Merging content repo, namespace ${namespace}, " + "repoName ${repoName} from ${src} to ${target}")

		FileUtils.copyDirectory(src,
			target,
			new FileSystemUtils.IgnoreDotGitFolderFilter())

		return new RepoCoordinate(namespace: namespace,
			repoName: repoName,
			clonedContentRepo: target,
			repoConfig: repoConfig)
	}

	private static List<File> findRepoDirectories(File srcRepo) {
		return srcRepo.listFiles().findAll {
			it.isDirectory() && !it.name.startsWith('.')
		}
	}

	private void applyTemplatingIfApplicable(ContentRepositorySchema repoConfig,
		File srcPath) {
		if (!repoConfig.templating) {
			return
		}

		TemplatingEngine engine =
			getTemplatingEngine()

		GitRepo repo =
			repoProvider.create(repoConfig.target,
				gitHandler.tenant)

		def statics =
			!config.content.useWhitelist ? new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32)
				.build()
				.getStaticModels() : new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32,
				config.content
					.getAllowedStaticsWhitelist())
				.getStaticModels()

		engine.replaceTemplates(srcPath,
			[config : config,
			 scm    : [baseUrl : repo.gitProvider.url,
			           host    : repo.gitProvider.host,
			           protocol: repo.gitProvider.protocol,
			           repoUrl : repo.gitProvider.repoPrefix()],
			 statics: statics])
	}

	private void cloneToLocalFolder(ContentRepositorySchema repoConfig,
		File repoTmpDir) {
		CloneCommand cloneCommand =
			gitClone()
				.setURI(repoConfig.url)
				.setDirectory(repoTmpDir)
				.setNoCheckout(false)

		if (credentialsProvider) {
			cloneCommand.setCredentialsProvider(credentialsProvider)
		}

		Git git = cloneCommand.call()

		if (ContentRepoType.MIRROR == repoConfig.type) {
			def fetch = git.fetch()

			if (credentialsProvider) {
				fetch.setCredentialsProvider(credentialsProvider)
			}

			fetch
				.setRefSpecs('+refs/*:refs/*')
				.call()
		}

		if (repoConfig.ref) {
			String actualRef =
				findRef(repoConfig,
					git.repository)

			git.checkout()
				.setName(actualRef)
				.call()
		}
	}

	private static String findRef(ContentRepositorySchema repoConfig,
		Repository gitRepo) {
		if (gitRepo.resolve(repoConfig.ref)) {
			return repoConfig.ref
		}

		def remoteCommand =
			Git.lsRemoteRepository()
				.setRemote(repoConfig.url)
				.setHeads(true)
				.setTags(true)

		Collection<Ref> refs =
			remoteCommand.call()

		String potentialRef =
			refs.find {
				it.name.endsWith(repoConfig.ref)
			}?.name

		if (!potentialRef) {
			throw new RuntimeException("Reference '${repoConfig.ref}' not found in " + "content repository '${repoConfig.url}'")
		}

		return potentialRef.replace('refs/heads/',
			'origin/')
	}

	private void pushTargetRepos(List<RepoCoordinate> repoCoordinates) {
		repoCoordinates.each { RepoCoordinate repoCoordinate ->
			log.trace("Preparing ContentLoader target repo '{}'. " + "type='{}', overwriteMode='{}', targetRef='{}', " + "refIsTag='{}', source='{}'",
				repoCoordinate.fullRepoName,
				repoCoordinate.repoConfig.type,
				repoCoordinate.repoConfig.overwriteMode,
				repoCoordinate.repoConfig.targetRef,
				repoCoordinate.refIsTag,
				repoCoordinate
					.clonedContentRepo
					?.absolutePath)

			GitRepo targetRepo =
				repoProvider.create(repoCoordinate.fullRepoName,
					gitHandler.tenant)

			boolean isNewRepo =
				targetRepo
					.createRepositoryAndSetPermission('',
						false)

			log.trace("ContentLoader target repo '{}'. " + "isNewRepo='{}', localTargetRepo='{}'",
				repoCoordinate.fullRepoName,
				isNewRepo,
				targetRepo.absoluteLocalRepoTmpDir)

			if (isValidForPush(isNewRepo,
				repoCoordinate)) {
				targetRepo.cloneRepo()

				switch (repoCoordinate.repoConfig.type) {
					case ContentRepoType.MIRROR:
						handleRepoMirroring(repoCoordinate,
							targetRepo)
						break

					case ContentRepoType.FOLDER_BASED:
					case ContentRepoType.COPY:
						handleRepoCopyingOrFolderBased(repoCoordinate,
							targetRepo,
							isNewRepo)
						break
				}

				createJenkinsJobIfApplicable(repoCoordinate,
					targetRepo)

				log.trace("Cleaning ContentLoader temp folders for repo '{}'. " + "source='{}', target='{}'",
					repoCoordinate.fullRepoName,
					repoCoordinate
						.clonedContentRepo
						?.absolutePath,
					targetRepo.absoluteLocalRepoTmpDir)

				repoCoordinate
					.clonedContentRepo
					.deleteDir()

				new File(targetRepo.absoluteLocalRepoTmpDir).deleteDir()
			} else {
				log.debug("Skipping ContentLoader push for repo '{}'. " + "isNewRepo='{}', overwriteMode='{}'",
					repoCoordinate.fullRepoName,
					isNewRepo,
					repoCoordinate
						.repoConfig
						.overwriteMode)
			}
		}
	}

	/**
	 * Copies the repository coordinate into the target repository,
	 * commits and pushes the changes.	*/
	private static void handleRepoCopyingOrFolderBased(RepoCoordinate repoCoordinate,
		GitRepo targetRepo,
		boolean isNewRepo) {
		log.trace("Copying ContentLoader content into repo '{}'. " + "isNewRepo='{}', overwriteMode='{}', " + "source='{}', target='{}'",
			repoCoordinate.fullRepoName,
			isNewRepo,
			repoCoordinate.repoConfig.overwriteMode,
			repoCoordinate
				.clonedContentRepo
				?.absolutePath,
			targetRepo.absoluteLocalRepoTmpDir)

		if (!isNewRepo) {
			clearTargetRepoIfApplicable(repoCoordinate,
				targetRepo)
		}

		targetRepo.copyDirectoryContents(repoCoordinate
			.clonedContentRepo
			.absolutePath,
			new FileSystemUtils.IgnoreDotGitFolderFilter())

		String commitMessage =
			'Initialize content repo ' + "${repoCoordinate.namespace}/" + "${repoCoordinate.repoName}"

		String targetRefShort =
			repoCoordinate.repoConfig.targetRef
				.replace('refs/heads/', '')
				.replace('refs/tags/', '')

		if (targetRefShort) {
			String refSpec =
				setRefSpec(repoCoordinate,
					targetRefShort)

			log.trace("Committing ContentLoader repo '{}'. " + "targetRefShort='{}', refSpec='{}'",
				repoCoordinate.fullRepoName,
				targetRefShort,
				refSpec)

			targetRepo.commitAndPush(commitMessage,
				targetRefShort,
				refSpec)
		} else {
			log.trace("Committing ContentLoader repo '{}' " + 'to default main branch.',
				repoCoordinate.fullRepoName)

			targetRepo.commitAndPush(commitMessage)
		}
	}

	private static String setRefSpec(RepoCoordinate repoCoordinate,
		String targetRefShort) {
		if ((repoCoordinate.refIsTag && !repoCoordinate.repoConfig.targetRef
			.startsWith('refs/heads')) || repoCoordinate.repoConfig.targetRef
			.startsWith('refs/tags')) {
			return "refs/tags/${targetRefShort}:" + "refs/tags/${targetRefShort}"
		}

		return "HEAD:refs/heads/${targetRefShort}"
	}

	private static void clearTargetRepoIfApplicable(RepoCoordinate repoCoordinate,
		GitRepo targetRepo) {
		if (OverwriteMode.INIT == repoCoordinate.repoConfig.overwriteMode) {
			return
		}

		if (OverwriteMode.RESET == repoCoordinate.repoConfig.overwriteMode) {
			log.info("OverwriteMode ${OverwriteMode.RESET} set for repo " + "'${repoCoordinate.fullRepoName}': " +
				'Deleting existing files in repo and replacing ' +
				'them with new content.')

			targetRepo.clearRepo()
			return
		}

		log.debug("OverwriteMode ${OverwriteMode.UPGRADE} set for repo " + "'${repoCoordinate.fullRepoName}': " + 'Merging new content into existing repo.')
	}

	/**
	 * Force-pushes the configured ref or all refs to the target repository.	*/
	private static void handleRepoMirroring(RepoCoordinate repoCoordinate,
		GitRepo targetRepo) {
		try (Git targetGit =
			Git.open(new File(targetRepo.absoluteLocalRepoTmpDir))) {
			String remoteUrl =
				targetGit.repository.config.getString('remote',
					'origin',
					'url')

			FileSystemUtils.makeWritable(new File(targetRepo.absoluteLocalRepoTmpDir,
				'.git'))

			targetRepo.copyDirectoryContents(repoCoordinate
				.clonedContentRepo
				.absolutePath)

			targetGit.repository.config.setString('remote',
				'origin',
				'url',
				remoteUrl)

			targetGit.repository.config.save()
		}

		if (repoCoordinate.repoConfig.ref) {
			validateCommitReferences(repoCoordinate)

			if (repoCoordinate.repoConfig.targetRef) {
				log.debug("Mirroring repo '${repoCoordinate.repoConfig.url}' " + "ref '${repoCoordinate.repoConfig.ref}' " +
					"to target repo ${repoCoordinate.fullRepoName}, " +
					"targetRef: '${repoCoordinate.repoConfig.targetRef}'")

				targetRepo.pushRef(repoCoordinate.repoConfig.ref,
					repoCoordinate.repoConfig.targetRef,
					true)
			} else {
				log.debug("Mirroring repo '${repoCoordinate.repoConfig.url}' " + "ref '${repoCoordinate.repoConfig.ref}' " + "to target repo ${repoCoordinate.fullRepoName}")

				targetRepo.pushRef(repoCoordinate.repoConfig.ref,
					true)
			}
		} else {
			log.debug('Mirroring whole repo ' + "'${repoCoordinate.repoConfig.url}' " + "to target repo ${repoCoordinate.fullRepoName}")

			targetRepo.pushAll(true)
		}
	}

	private static void validateCommitReferences(RepoCoordinate repoCoordinate) {
		if (GitRepo.isCommit(repoCoordinate.clonedContentRepo,
			repoCoordinate.repoConfig.ref)) {
			throw new RuntimeException('Mirroring commit references is not supported for ' + 'content repos at the moment. Content repository ' +
				"'${repoCoordinate.repoConfig.url}', " +
				"ref: ${repoCoordinate.repoConfig.ref}")
		}
	}

	private void createJenkinsJobIfApplicable(RepoCoordinate repoCoordinate,
		GitRepo repo) {
		if (repoCoordinate.repoConfig.createJenkinsJob && jenkins.isEnabled(context) && GitRepo.existFileInSomeBranch(repo.absoluteLocalRepoTmpDir,
			'Jenkinsfile')) {
			jenkins.createJenkinsjob(repoCoordinate.namespace,
				repoCoordinate.namespace)
		}
	}

	/**
	 * Overridable for tests.	*/
	protected CloneCommand gitClone() {
		return Git.cloneRepository()
	}

	/**
	 * Adds a repository coordinate and ensures that the newest
	 * COPY/FOLDER_BASED coordinate replaces the previous one.	*/
	static void addRepoCoordinates(List<RepoCoordinate> repoCoordinates,
		RepoCoordinate newRepoCoordinate) {
		List<RepoCoordinate> existingRepoCoordinates =
			newRepoCoordinate.findSame(repoCoordinates)

		if (!existingRepoCoordinates.isEmpty()) {
			log.debug('Found existing repo coordinates for ' + "${newRepoCoordinate}: " + "${existingRepoCoordinates}")

			RepoCoordinate repoCoordinateToOverwrite =
				newRepoCoordinate.findSameNotMirror(existingRepoCoordinates)

			if (repoCoordinateToOverwrite) {
				repoCoordinates.remove(repoCoordinateToOverwrite)

				log.debug('Replacing existing repo coordinate ' + "${existingRepoCoordinates} with new one: " + "${newRepoCoordinate}")
			}
		}

		repoCoordinates << newRepoCoordinate
	}

	/**
	 * Checks whether the repository should be pushed.	*/
	static boolean isValidForPush(boolean isNewRepo,
		RepoCoordinate repoCoordinate) {
		if (!isNewRepo && OverwriteMode.INIT == repoCoordinate.repoConfig.overwriteMode) {
			log.warn('OverwriteMode ' + String.valueOf(OverwriteMode.INIT) +
				' set for repo ' +
				('\'' + repoCoordinate.fullRepoName + '\' and repo already ') +
				'exists in target. Not pushing content. ' +
				'If you want to overwrite it, use ' +
				"${OverwriteMode.UPGRADE} or ${OverwriteMode.RESET}.")

			return false
		}

		return true
	}

	private void clearCache() {
		if (mergedReposFolder) {
			mergedReposFolder.deleteDir()
		}

		cachedRepoCoordinates.clear()
		mergedReposFolder = null
	}

	static class RepoCoordinate {

		String namespace
		String repoName
		File clonedContentRepo
		ContentRepositorySchema repoConfig
		boolean refIsTag

		@Override
		String toString() {
			return 'RepoCoordinates{' + (' namespace=\'' + namespace + '\',') +
				(' repoName=\'' + repoName + '\',') +
				(' repoConfig.type=\'' + String.valueOf(repoConfig.type) + '\',') +
				' repoConfig.overwriteMode=' +
				"'${repoConfig.overwriteMode}'," +
				" clonedContentRepo=${clonedContentRepo}," +
				" refIsTag='${refIsTag}'" +
				' }'
		}

		String getFullRepoName() {
			return "${namespace}/${repoName}"
		}

		List<RepoCoordinate> findSame(List<RepoCoordinate> repoCoordinates) {
			return repoCoordinates.findAll {
				it.fullRepoName == fullRepoName
			}
		}

		RepoCoordinate findSameNotMirror(List<RepoCoordinate> repoCoordinates) {
			return repoCoordinates.find {
				it.fullRepoName == fullRepoName && ContentRepoType.MIRROR != it.repoConfig.type
			}
		}
	}

}
