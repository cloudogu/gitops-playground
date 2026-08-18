package com.cloudogu.gitops.application.content;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.OverwriteMode;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractTool;
import com.cloudogu.gitops.tools.common.ConfigLifecycleHook;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.core.Jenkins;
import com.cloudogu.gitops.utils.AllowListFreemarkerObjectWrapper;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.cloudogu.gitops.config.Config.ContentRepoType;
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema;

@Singleton
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
public class ContentLoader extends AbstractTool implements ConfigLifecycleHook {

	private static final String CONTENT_REPOS_TYPE_PREFIX = "content.repos.type ";
	private static final String REFS_HEADS_PREFIX = "refs/heads/";
	private static final String REFS_TAGS_PREFIX = "refs/tags/";
	private static final String OVERWRITE_MODE_PREFIX = "OverwriteMode ";
	private static final String SET_FOR_REPO_SUFFIX = " set for repo '";

	private final K8sClient k8sClient;
	private final GitRepoFactory repoProvider;
	private final Jenkins jenkins;

	private TemplatingEngine templatingEngine;
	private List<RepoCoordinate> cachedRepoCoordinates = new ArrayList<>();
	protected File mergedReposFolder;

	public ContentLoader(
		K8sClient k8sClient,
		GitRepoFactory repoProvider,
		Jenkins jenkins,
		GitHandler gitHandler,
		FileSystemUtils fileSystemUtils,
		Deployer deployer) {
		this.k8sClient = k8sClient;
		this.repoProvider = repoProvider;
		this.jenkins = jenkins;
		this.gitHandler = gitHandler;
		this.fileSystemUtils = fileSystemUtils;
		this.deployer = deployer;
	}

	@Override
	public boolean isEnabled(DeploymentContext context) {
		return true; // for now always on
	}

	@Override
	protected void deploy() {
		try {
			clearCache();
			cachedRepoCoordinates = cloneContentRepos();
			createImagePullSecrets();
			createContentRepos();
			deployHelmReleasesFromContent();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to load and deploy content", e);
		}
	}

	@Override
	public void validate() {
		// No additional validation needed beyond preConfigInit
	}

	@Override
	public void preConfigInit(Config configToSet) {
		if (configToSet.getContent() == null || configToSet.getContent().getRepos() == null) {
			return;
		}

		for (ContentRepositorySchema repo : configToSet.getContent().getRepos()) {
			validateRepo(repo);
		}
	}

	private static void validateRepo(ContentRepositorySchema repo) {
		if (repo.getUrl() == null || repo.getUrl().isEmpty()) {
			throw new IllegalArgumentException("content.repos requires a url parameter.");
		}
		if (repo.getTarget() != null && !repo.getTarget().isEmpty() && !repo.getTarget().contains("/")) {
			throw new IllegalArgumentException(
				"content.target needs / to separate namespace/group from repo name. Repo: " + repo.getUrl());
		}

		switch (repo.getType()) {
			case COPY:
				validateCopyRepo(repo);
				break;
			case FOLDER_BASED:
				validateFolderBasedRepo(repo);
				break;
			case MIRROR:
				validateMirrorRepo(repo);
				break;
		}
	}

	private static void validateCopyRepo(ContentRepositorySchema repo) {
		if (repo.getTarget() == null || repo.getTarget().isEmpty()) {
			throw new IllegalArgumentException(CONTENT_REPOS_TYPE_PREFIX + ContentRepoType.COPY + " requires content.repos.target to be set. Repo: " + repo.getUrl());
		}
	}

	private static void validateFolderBasedRepo(ContentRepositorySchema repo) {
		if (repo.getTarget() != null && !repo.getTarget().isEmpty()) {
			throw new IllegalArgumentException(CONTENT_REPOS_TYPE_PREFIX + ContentRepoType.FOLDER_BASED + " does not support target parameter. Repo: " + repo.getUrl());
		}
		if (repo.getTargetRef() != null && !repo.getTargetRef().isEmpty()) {
			throw new IllegalArgumentException(CONTENT_REPOS_TYPE_PREFIX + ContentRepoType.FOLDER_BASED + " does not support targetRef parameter. Repo: " + repo.getUrl());
		}
	}

	private static void validateMirrorRepo(ContentRepositorySchema repo) {
		if (repo.getTarget() == null || repo.getTarget().isEmpty()) {
			throw new IllegalArgumentException(CONTENT_REPOS_TYPE_PREFIX + ContentRepoType.MIRROR + " requires content.repos.target to be set. Repo: " + repo.getUrl());
		}
		if (!ContentRepositorySchema.DEFAULT_PATH.equals(repo.getPath())) {
			throw new IllegalArgumentException(CONTENT_REPOS_TYPE_PREFIX + ContentRepoType.MIRROR + " does not support path. Current path: " + repo.getPath() + ". Repo: " + repo.getUrl());
		}
		if (repo.getTemplating()) {
			throw new IllegalArgumentException(CONTENT_REPOS_TYPE_PREFIX + ContentRepoType.MIRROR + " does not support templating. Repo: " + repo.getUrl());
		}
	}

	protected void deployHelmReleasesFromContent() throws GitAPIException {
		if (getConfig().getContent() == null || getConfig().getContent()
		                                                   .getHelmReleases() == null || getConfig().getContent()
		                                                                                            .getHelmReleases()
		                                                                                            .isEmpty()) {
			log.debug("No content.helmReleases configured - skipping.");
			return;
		}

		for (Config.ContentSchema.HelmReleaseSchema helmRelease : getConfig().getContent().getHelmReleases()) {
			deployHelmReleaseFromContent(helmRelease);
		}
	}

	private void deployHelmReleaseFromContent(Config.ContentSchema.HelmReleaseSchema helmRelease) throws GitAPIException {
		String version = helmRelease.getVersion() != null ? helmRelease.getVersion().trim() : "";
		if (version.isEmpty()) {
			version = "*";
		}

		HelmChartConfig helmConfig = HelmChartConfig.builder()
			.repoURL(helmRelease.getRepoURL())
			.chart(helmRelease.getChart())
			.version(version)
			.values(new HashMap<>())
			.localHelmChartFolder(getConfig().getApplication().getLocalHelmChartFolder())
			.build();

		Map<String, Object> fileValues = new HashMap<>();
		if (helmRelease.getValuesPath() != null && !helmRelease.getValuesPath().trim().isEmpty()) {
			Map<String, Object> readValues = fileSystemUtils.readYaml(Path.of(helmRelease.getValuesPath()));
			if (readValues != null) {
				fileValues = readValues;
			}
		}

		Map<String, Object> inlineValues = helmRelease.getValues() != null ? helmRelease.getValues() : Collections.emptyMap();

		Map<String, Object> mergedValues = MapUtils.deepMerge(inlineValues, fileValues);

		Path mergedValuesFile = fileSystemUtils.writeTempFile(mergedValues);
		String mergedValuesFilePath = mergedValuesFile.toString();

		String releaseName = (helmRelease.getReleaseName() != null && !helmRelease.getReleaseName()
		                                                                          .isEmpty()) ? helmRelease.getReleaseName() : helmRelease.getName();

		deployHelmChart(
			helmRelease.getName(),
			releaseName,
			helmRelease.getNamespace(),
			helmConfig,
			mergedValuesFilePath,
			context,
			false
		);

		repositoryWorkspace.commitAndPushClusterResourcesChanges("Update " + releaseName + " GitOps resources");
	}

	void createImagePullSecrets() {
		if (getConfig().getRegistry().getCreateImagePullSecrets()) {
			String registryUsername = (getConfig().getRegistry()
			                                      .getReadOnlyUsername() != null && !getConfig().getRegistry()
			                                                                                    .getReadOnlyUsername()
			                                                                                    .isEmpty()) ? getConfig().getRegistry()
			                                                                                                             .getReadOnlyUsername() : getConfig().getRegistry()
			                                                                                                                                                 .getUsername();

			String registryPassword = (getConfig().getRegistry()
			                                      .getReadOnlyPassword() != null && !getConfig().getRegistry()
			                                                                                    .getReadOnlyPassword()
			                                                                                    .isEmpty()) ? getConfig().getRegistry()
			                                                                                                             .getReadOnlyPassword() : getConfig().getRegistry()
			                                                                                                                                                 .getPassword();

			for (String namespace : getConfig().getContent().getNamespaces()) {
				String registrySecretName = "registry";

				k8sClient.createNamespace(namespace);

				k8sClient.createImagePullSecret(
					registrySecretName, namespace, getConfig().getRegistry()
					                                          .getUrl(), registryUsername, registryPassword
				);

				k8sClient.patch(
					"serviceaccount",
					"default",
					namespace,
					Map.of("imagePullSecrets", List.of(Map.of("name", registrySecretName)))
				);

				if (getConfig().getRegistry().getTwoRegistries()) {
					k8sClient.createImagePullSecret(
						"proxy-registry",
						namespace,
						getConfig().getRegistry()
						           .getProxyUrl(),
						getConfig().getRegistry()
						           .getProxyUsername(),
						getConfig().getRegistry()
						           .getProxyPassword()
					);
				}
			}
		}
	}

	void createContentRepos() throws Exception {
		if (cachedRepoCoordinates.isEmpty()) {
			cachedRepoCoordinates = cloneContentRepos();
		}
		pushTargetRepos(cachedRepoCoordinates);
		clearCache();
	}

	protected List<RepoCoordinate> cloneContentRepos() throws Exception {
		try {
			mergedReposFolder = Files.createTempDirectory("gitops-playground-based-content-repos-").toFile();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create temporary directory", e);
		}
		List<RepoCoordinate> repoCoordinates = new ArrayList<>();

		log.debug("Aggregating structure for all {} repos.", getConfig().getContent().getRepos().size());
		for (ContentRepositorySchema repoConfig : getConfig().getContent().getRepos()) {
			createRepoCoordinates(repoConfig, mergedReposFolder, repoCoordinates);
		}
		return repoCoordinates;
	}

	private TemplatingEngine getTemplatingEngine() {
		if (templatingEngine == null) {
			templatingEngine = new TemplatingEngine();
		}
		return templatingEngine;
	}

	private void createRepoCoordinates(
		ContentRepositorySchema repoConfig,
		File mergedReposFolder,
		List<RepoCoordinate> repoCoordinates) {
		File repoTmpDir;
		try {
			repoTmpDir = Files.createTempDirectory("gitops-playground-content-repo-").toFile();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create temporary directory", e);
		}
		log.debug(
			"Cloning content repo, {}, revision {}, path {}, overwriteMode {}",
			repoConfig.getUrl(),
			repoConfig.getRef(),
			repoConfig.getPath(),
			repoConfig.getOverwriteMode()
		);

		UsernamePasswordCredentialsProvider credentialsProvider = null;
		if (repoConfig.getCredentials() != null && repoConfig.getCredentials()
		                                                     .getUsername() != null && repoConfig.getCredentials()
		                                                                                         .getPassword() != null) {
			credentialsProvider = new UsernamePasswordCredentialsProvider(
				repoConfig.getCredentials()
				          .getUsername(), repoConfig.getCredentials()
				                                    .getPassword()
			);
		} else if (repoConfig.getCredentials() != null && repoConfig.getCredentials()
		                                                            .getSecretName() != null && repoConfig.getCredentials()
		                                                                                                  .getSecretNamespace() != null) {
			Credentials credentials = this.k8sClient.getCredentialsFromSecret(repoConfig.getCredentials());
			credentialsProvider = new UsernamePasswordCredentialsProvider(
				credentials.getUsername(),
				credentials.getPassword()
			);
		} else {
			// no credentials configured for this repo; clone anonymously
		}

		cloneToLocalFolder(repoConfig, repoTmpDir, credentialsProvider);

		File contentRepoDir = new File(repoTmpDir, repoConfig.getPath());
		applyTemplatingIfApplicable(repoConfig, contentRepoDir);

		switch (repoConfig.getType()) {
			case FOLDER_BASED:
				createRepoCoordinatesForTypeFolderBased(
					repoConfig,
					repoTmpDir,
					contentRepoDir,
					mergedReposFolder,
					repoCoordinates
				);
				try {
					FileUtils.deleteDirectory(repoTmpDir);
				} catch (IOException e) {
					log.debug("Failed to delete temporary directory {}", repoTmpDir, e);
				}
				break;
			case COPY:
				createRepoCoordinatesForTypeCopy(
					repoConfig,
					contentRepoDir,
					mergedReposFolder,
					repoTmpDir,
					repoCoordinates
				);
				try {
					FileUtils.deleteDirectory(repoTmpDir);
				} catch (IOException e) {
					log.debug("Failed to delete temporary directory {}", repoTmpDir, e);
				}
				break;
			case MIRROR:
				createRepoCoordinateForTypeMirror(repoConfig, repoTmpDir, repoCoordinates);
				break;
		}
		log.debug("Finished cloning content repos. repoCoordinates={}", repoCoordinates);
	}

	private static void createRepoCoordinatesForTypeCopy(
		ContentRepositorySchema repoConfig,
		File contentRepoDir,
		File mergedRepoFolder,
		File repoTmpDir,
		List<RepoCoordinate> repoCoordinates) {
		String namespace = repoConfig.getTarget().split("/")[0];
		String repoName = repoConfig.getTarget().split("/")[1];

		RepoCoordinate repoCoordinate = mergeRepoDirs(
			contentRepoDir,
			namespace,
			repoName,
			mergedRepoFolder,
			repoConfig
		);
		repoCoordinate.refIsTag = GitRepo.isTag(repoTmpDir, repoConfig.getRef());
		addRepoCoordinates(repoCoordinates, repoCoordinate);
	}

	private static void createRepoCoordinatesForTypeFolderBased(
		ContentRepositorySchema repoConfig,
		File repoTmpDir,
		File contentRepoDir,
		File mergedRepoFolder,
		List<RepoCoordinate> repoCoordinates) {
		boolean refIsTag = GitRepo.isTag(repoTmpDir, repoConfig.getRef());
		for (File contentRepoNamespaceDir : findRepoDirectories(contentRepoDir)) {
			for (File contentRepoFolder : findRepoDirectories(contentRepoNamespaceDir)) {
				String namespace = contentRepoNamespaceDir.getName();
				String repoName = contentRepoFolder.getName();
				RepoCoordinate repoCoordinate = mergeRepoDirs(
					contentRepoFolder,
					namespace,
					repoName,
					mergedRepoFolder,
					repoConfig
				);
				repoCoordinate.refIsTag = refIsTag;
				addRepoCoordinates(repoCoordinates, repoCoordinate);
			}
		}
	}

	private static void createRepoCoordinateForTypeMirror(
		ContentRepositorySchema repoConfig,
		File repoTmpDir,
		List<RepoCoordinate> repoCoordinates) {
		String namespace = repoConfig.getTarget().split("/")[0];
		String repoName = repoConfig.getTarget().split("/")[1];
		RepoCoordinate repoCoordinate = new RepoCoordinate();
		repoCoordinate.namespace = namespace;
		repoCoordinate.repoName = repoName;
		repoCoordinate.clonedContentRepo = repoTmpDir;
		repoCoordinate.repoConfig = repoConfig;
		repoCoordinate.refIsTag = GitRepo.isTag(repoTmpDir, repoConfig.getRef());
		addRepoCoordinates(repoCoordinates, repoCoordinate);
	}

	private static RepoCoordinate mergeRepoDirs(
		File src,
		String namespace,
		String repoName,
		File mergedRepoFolder,
		ContentRepositorySchema repoConfig) {
		File target = new File(new File(mergedRepoFolder, namespace), repoName);
		log.debug("Merging content repo, namespace {}, repoName {} from {} to {}", namespace, repoName, src, target);
		try {
			FileUtils.copyDirectory(src, target, new FileSystemUtils.IgnoreDotGitFolderFilter());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to copy directory from " + src + " to " + target, e);
		}

		RepoCoordinate repoCoordinate = new RepoCoordinate();
		repoCoordinate.namespace = namespace;
		repoCoordinate.repoName = repoName;
		repoCoordinate.clonedContentRepo = target;
		repoCoordinate.repoConfig = repoConfig;
		return repoCoordinate;
	}

	private static Collection<File> findRepoDirectories(File srcRepo) {
		File[] files = srcRepo.listFiles();
		if (files == null) {
			return Collections.emptyList();
		}
		return Arrays.stream(files).filter(file -> file.isDirectory() && !file.getName().startsWith(".")).toList();
	}

	private void applyTemplatingIfApplicable(ContentRepositorySchema repoConfig, File srcPath) {
		if (!repoConfig.getTemplating()) {
			return;
		}

		TemplatingEngine engine = getTemplatingEngine();

		try (GitRepo repo = this.repoProvider.create(repoConfig.getTarget(), this.gitHandler.getTenant())) {
			engine.replaceTemplates(
				srcPath, Map.of(
					"config", getConfig(), "scm", Map.of(
						"baseUrl",
						repo.getGitProvider()
						    .getUrl(),
						"host",
						repo.getGitProvider()
						    .getHost(),
						"protocol",
						repo.getGitProvider()
						    .getProtocol(),
						"repoUrl",
						repo.getGitProvider()
						    .repoPrefix()
					), "statics", !getConfig().getContent()
					                          .getUseWhitelist() ? new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build()
					                                                                                                            .getStaticModels() : new AllowListFreemarkerObjectWrapper(
						Configuration.VERSION_2_3_32, getConfig().getContent()
						                                         .getAllowedStaticsWhitelist()
					).getStaticModels()
				)
			);
		} catch (Exception e) {
			throw new RuntimeException("Failed to replace templates in " + srcPath, e);
		}
	}

	private void cloneToLocalFolder(
		ContentRepositorySchema repoConfig,
		File repoTmpDir,
		UsernamePasswordCredentialsProvider credentialsProvider) {
		CloneCommand cloneCommand = gitClone().setURI(repoConfig.getUrl())
		                                      .setDirectory(repoTmpDir)
		                                      .setNoCheckout(false);

		if (credentialsProvider != null) {
			cloneCommand.setCredentialsProvider(credentialsProvider);
		}

		try (Git git = cloneCommand.call()) {
			if (ContentRepoType.MIRROR == repoConfig.getType()) {
				FetchCommand fetch = git.fetch();

				if (credentialsProvider != null) {
					fetch.setCredentialsProvider(credentialsProvider);
				}
				fetch.setRefSpecs("+refs/*:refs/*").call(); // Fetch all branches and tags
			}

			if (repoConfig.getRef() != null && !repoConfig.getRef().isEmpty()) {
				String actualRef = findRef(repoConfig, git.getRepository());
				git.checkout().setName(actualRef).call();
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Failed to clone content repo " + repoConfig.getUrl(), e);
		}
	}

	private static String findRef(ContentRepositorySchema repoConfig, Repository gitRepo) {
		try {
			if (gitRepo.resolve(repoConfig.getRef()) != null) {
				return repoConfig.getRef();
			}

			LsRemoteCommand remoteCommand = Git.lsRemoteRepository()
			                                   .setRemote(repoConfig.getUrl())
			                                   .setHeads(true)
			                                   .setTags(true);

			Collection<Ref> refs = remoteCommand.call();
			String potentialRef = null;
			for (Ref ref : refs) {
				if (ref.getName().equals(REFS_HEADS_PREFIX + repoConfig.getRef()) || ref.getName()
				                                                                        .equals(REFS_TAGS_PREFIX + repoConfig.getRef())) {
					potentialRef = ref.getName();
					break;
				}
			}

			if (potentialRef == null) {
				throw new IllegalStateException("Reference '" + repoConfig.getRef() + "' not found in content repository '" + repoConfig.getUrl() + "'");
			}

			return potentialRef.replace(REFS_HEADS_PREFIX, "origin/");
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(
				"Failed to find ref " + repoConfig.getRef() + " in repo " + repoConfig.getUrl(),
				e
			);
		}
	}

	private void pushTargetRepos(List<RepoCoordinate> repoCoordinates) throws Exception {
		for (RepoCoordinate repoCoordinate : repoCoordinates) {
			pushTargetRepo(repoCoordinate);
		}
	}

	private void pushTargetRepo(RepoCoordinate repoCoordinate) throws Exception {
		log.trace(
			"Preparing ContentLoader target repo '{}'. type='{}', overwriteMode='{}', targetRef='{}', refIsTag='{}', source='{}'",
			repoCoordinate.getFullRepoName(),
			repoCoordinate.repoConfig.getType(),
			repoCoordinate.repoConfig.getOverwriteMode(),
			repoCoordinate.repoConfig.getTargetRef(),
			repoCoordinate.refIsTag,
			repoCoordinate.clonedContentRepo != null ? repoCoordinate.clonedContentRepo.getAbsolutePath() : null
		);

		try (GitRepo targetRepo = repoProvider.create(repoCoordinate.getFullRepoName(), this.gitHandler.getTenant())) {
			boolean isNewRepo = targetRepo.createRepositoryAndSetPermission("", false);
			log.trace(
				"ContentLoader target repo '{}'. isNewRepo='{}', localTargetRepo='{}'",
				repoCoordinate.getFullRepoName(),
				isNewRepo,
				targetRepo.getAbsoluteLocalRepoTmpDir()
			);

			if (!isValidForPush(isNewRepo, repoCoordinate)) {
				log.debug(
					"Skipping ContentLoader push for repo '{}'. isNewRepo='{}', overwriteMode='{}'",
					repoCoordinate.getFullRepoName(),
					isNewRepo,
					repoCoordinate.repoConfig.getOverwriteMode()
				);
				return;
			}

			targetRepo.cloneRepo();

			if (repoCoordinate.repoConfig.getType() == ContentRepoType.MIRROR) {
				handleRepoMirroring(repoCoordinate, targetRepo);
			} else {
				copyContentAndPushTargetRepo(repoCoordinate, targetRepo, isNewRepo);
			}

			createJenkinsJobIfApplicable(repoCoordinate, targetRepo);
			cleanUpTargetRepoTempFolders(repoCoordinate, targetRepo);
		}
	}

	private static void cleanUpTargetRepoTempFolders(RepoCoordinate repoCoordinate, GitRepo targetRepo) {
		log.trace(
			"Cleaning ContentLoader temp folders for repo '{}'. source='{}', target='{}'",
			repoCoordinate.getFullRepoName(),
			repoCoordinate.clonedContentRepo != null ? repoCoordinate.clonedContentRepo.getAbsolutePath() : null,
			targetRepo.getAbsoluteLocalRepoTmpDir()
		);

		try {
			if (repoCoordinate.clonedContentRepo != null) {
				FileUtils.deleteDirectory(repoCoordinate.clonedContentRepo);
			}
			FileUtils.deleteDirectory(new File(targetRepo.getAbsoluteLocalRepoTmpDir()));
		} catch (IOException e) {
			log.debug("Failed to clean up temp folders for repo '{}'", repoCoordinate.getFullRepoName(), e);
		}
	}

	private static void copyContentAndPushTargetRepo(
		RepoCoordinate repoCoordinate,
		GitRepo targetRepo,
		boolean isNewRepo) throws Exception {
		log.trace(
			"Copying ContentLoader content into repo '{}'. isNewRepo='{}', overwriteMode='{}', source='{}', target='{}'",
			repoCoordinate.getFullRepoName(),
			isNewRepo,
			repoCoordinate.repoConfig.getOverwriteMode(),
			repoCoordinate.clonedContentRepo != null ? repoCoordinate.clonedContentRepo.getAbsolutePath() : null,
			targetRepo.getAbsoluteLocalRepoTmpDir()
		);

		if (!isNewRepo) {
			clearTargetRepoIfApplicable(repoCoordinate, targetRepo);
		}

		try {
			targetRepo.copyDirectoryContents(
				repoCoordinate.clonedContentRepo.getAbsolutePath(),
				new FileSystemUtils.IgnoreDotGitFolderFilter()
			);
		} catch (Exception e) {
			throw new RuntimeException("Failed to copy directory contents", e);
		}

		String commitMessage = "Initialize content repo " + repoCoordinate.namespace + "/" + repoCoordinate.repoName;
		String targetRefShort = repoCoordinate.repoConfig.getTargetRef()
		                                                 .replace(REFS_HEADS_PREFIX, "")
		                                                 .replace(REFS_TAGS_PREFIX, "");

		if (!targetRefShort.isEmpty()) {
			String refSpec = setRefSpec(repoCoordinate, targetRefShort);
			log.trace(
				"Committing ContentLoader repo '{}'. targetRefShort='{}', refSpec='{}'",
				repoCoordinate.getFullRepoName(),
				targetRefShort,
				refSpec
			);
			targetRepo.commitAndPush(commitMessage, targetRefShort, refSpec);
		} else {
			log.trace("Committing ContentLoader repo '{}' to default main branch.", repoCoordinate.getFullRepoName());
			targetRepo.commitAndPush(commitMessage);
		}
	}

	private static String setRefSpec(RepoCoordinate repoCoordinate, String targetRefShort) {
		String refSpec;
		if ((repoCoordinate.refIsTag && !repoCoordinate.repoConfig.getTargetRef()
		                                                          .startsWith(REFS_HEADS_PREFIX)) || repoCoordinate.repoConfig.getTargetRef()
		                                                                                                                      .startsWith(
																																  REFS_TAGS_PREFIX)) {
			refSpec = REFS_TAGS_PREFIX + targetRefShort + ":" + REFS_TAGS_PREFIX + targetRefShort;
		} else {
			refSpec = "HEAD:" + REFS_HEADS_PREFIX + targetRefShort;
		}
		return refSpec;
	}

	private static void clearTargetRepoIfApplicable(RepoCoordinate repoCoordinate, GitRepo targetRepo) {
		if (OverwriteMode.INIT != repoCoordinate.repoConfig.getOverwriteMode()) {
			if (OverwriteMode.RESET == repoCoordinate.repoConfig.getOverwriteMode()) {
				log.info(
					"OverwriteMode {} set for repo '{}': Deleting existing files in repo and replacing them with new content.",
					OverwriteMode.RESET,
					repoCoordinate.getFullRepoName()
				);
				targetRepo.clearRepo();
			} else {
				log.debug(
					"OverwriteMode {} set for repo '{}': Merging new content into existing repo.",
					OverwriteMode.UPGRADE,
					repoCoordinate.getFullRepoName()
				);
			}
		}
	}

	private static void handleRepoMirroring(RepoCoordinate repoCoordinate, GitRepo targetRepo) throws Exception {
		try (Git targetGit = Git.open(new File(targetRepo.getAbsoluteLocalRepoTmpDir()))) {
			String remoteUrl = targetGit.getRepository().getConfig().getString("remote", "origin", "url");

			FileSystemUtils.makeWritable(new File(targetRepo.getAbsoluteLocalRepoTmpDir(), ".git"));

			targetRepo.copyDirectoryContents(repoCoordinate.clonedContentRepo.getAbsolutePath());

			targetGit.getRepository().getConfig().setString("remote", "origin", "url", remoteUrl);
			targetGit.getRepository().getConfig().save();
		} catch (Exception e) {
			throw new RuntimeException("Failed to open or configure mirrored Git repo", e);
		}

		if (repoCoordinate.repoConfig.getRef() != null && !repoCoordinate.repoConfig.getRef().isEmpty()) {
			validateCommitReferences(repoCoordinate);
			if (repoCoordinate.repoConfig.getTargetRef() != null && !repoCoordinate.repoConfig.getTargetRef()
			                                                                                  .isEmpty()) {
				log.debug(
					"Mirroring repo '{}' ref '{}' to target repo {}, targetRef: '{}'",
					repoCoordinate.repoConfig.getUrl(),
					repoCoordinate.repoConfig.getRef(),
					repoCoordinate.getFullRepoName(),
					repoCoordinate.repoConfig.getTargetRef()
				);
				targetRepo.pushRef(repoCoordinate.repoConfig.getRef(), repoCoordinate.repoConfig.getTargetRef(), true);
			} else {
				log.debug(
					"Mirroring repo '{}' ref '{}' to target repo {}",
					repoCoordinate.repoConfig.getUrl(),
					repoCoordinate.repoConfig.getRef(),
					repoCoordinate.getFullRepoName()
				);
				targetRepo.pushRef(repoCoordinate.repoConfig.getRef(), true);
			}
		} else {
			log.debug(
				"Mirroring whole repo '{}' to target repo {}",
				repoCoordinate.repoConfig.getUrl(),
				repoCoordinate.getFullRepoName()
			);
			targetRepo.pushAll(true);
		}
	}

	private static void validateCommitReferences(RepoCoordinate repoCoordinate) {
		if (GitRepo.isCommit(repoCoordinate.clonedContentRepo, repoCoordinate.repoConfig.getRef())) {
			throw new IllegalArgumentException(
				"Mirroring commit references is not supported for content repos at the moment. content repository '" + repoCoordinate.repoConfig.getUrl() + "', ref: " + repoCoordinate.repoConfig.getRef());
		}
	}

	private void createJenkinsJobIfApplicable(RepoCoordinate repoCoordinate, GitRepo repo) {
		if (repoCoordinate.repoConfig.getCreateJenkinsJob() && jenkins.isEnabled(context) && GitRepo.existFileInSomeBranch(
			repo.getAbsoluteLocalRepoTmpDir(),
			"Jenkinsfile"
		)) {
			jenkins.createJenkinsjob(repoCoordinate.namespace, repoCoordinate.namespace);
		}
	}

	protected CloneCommand gitClone() {
		return Git.cloneRepository();
	}

	static void addRepoCoordinates(List<RepoCoordinate> repoCoordinates, RepoCoordinate newRepoCoordinate) {
		List<RepoCoordinate> existingRepoCoordinates = newRepoCoordinate.findSame(repoCoordinates);

		if (!existingRepoCoordinates.isEmpty()) {
			log.debug("Found existing repo coordinates for {}: {}", newRepoCoordinate, existingRepoCoordinates);

			RepoCoordinate repoCoordinateToOverwrite = newRepoCoordinate.findSameNotMirror(existingRepoCoordinates);
			if (repoCoordinateToOverwrite != null) {
				repoCoordinates.remove(repoCoordinateToOverwrite);
				log.debug(
					"Replacing existing repo coordinate {} with new one: {}",
					existingRepoCoordinates,
					newRepoCoordinate
				);
			}
		}
		repoCoordinates.add(newRepoCoordinate);
	}

	static boolean isValidForPush(boolean isNewRepo, RepoCoordinate repoCoordinate) {
		if (!isNewRepo && OverwriteMode.INIT == repoCoordinate.repoConfig.getOverwriteMode()) {
			log.warn(OVERWRITE_MODE_PREFIX + OverwriteMode.INIT + SET_FOR_REPO_SUFFIX + repoCoordinate.getFullRepoName() + "' and repo already exists in target:  Not pushing content!" + "If you want to override, set " + OverwriteMode.UPGRADE + " or " + OverwriteMode.RESET + " .");
			return false;
		}
		return true;
	}

	private Config getConfig() {
		return context.getConfig();
	}

	private void clearCache() {
		if (mergedReposFolder != null) {
			try {
				FileUtils.deleteDirectory(mergedReposFolder);
			} catch (IOException e) {
				log.debug("Failed to delete merged repos folder {}", mergedReposFolder, e);
			}
		}
		cachedRepoCoordinates.clear();
		mergedReposFolder = null;
	}

	@Getter
	@Setter
	@NoArgsConstructor
	public static class RepoCoordinate {
		private String namespace;
		private String repoName;
		private File clonedContentRepo;
		private ContentRepositorySchema repoConfig;
		private boolean refIsTag;

		@Override
		public String toString() {
			return "RepoCoordinates{ namespace='" + namespace + "', repoName='" + repoName + "', repoConfig.type='" + repoConfig.getType() + "', repoConfig.overwriteMode='" + repoConfig.getOverwriteMode() + "', clonedContentRepo=" + clonedContentRepo + "', refIsTag='" + refIsTag + "' }";
		}

		public String getFullRepoName() {
			return namespace + "/" + repoName;
		}

		public List<RepoCoordinate> findSame(Collection<RepoCoordinate> repoCoordinates) {
			return repoCoordinates.stream().filter(coordinate -> coordinate.getFullRepoName().equals(getFullRepoName())).toList();
		}

		public RepoCoordinate findSameNotMirror(Collection<RepoCoordinate> repoCoordinates) {
			return repoCoordinates.stream()
			                      .filter(coordinate -> coordinate.getFullRepoName()
			                                      .equals(getFullRepoName()) && ContentRepoType.MIRROR != coordinate.repoConfig.getType())
			                      .findFirst()
			                      .orElse(null);
		}
	}
}
