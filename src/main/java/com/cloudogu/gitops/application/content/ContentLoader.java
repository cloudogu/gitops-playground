package com.cloudogu.gitops.application.content;

import static com.cloudogu.gitops.config.Config.ContentRepoType;
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Config.OverwriteMode;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.Tool;
import com.cloudogu.gitops.tools.core.Jenkins;
import com.cloudogu.gitops.utils.AllowListFreemarkerObjectWrapper;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.MapUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

@Singleton
@Slf4j
public class ContentLoader extends Tool {

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
  public void validate() {}

  @Override
  public void preConfigInit(Config configToSet) {
    if (configToSet.getContent() == null || configToSet.getContent().getRepos() == null) {
      return;
    }

    for (ContentRepositorySchema repo : configToSet.getContent().getRepos()) {
      if (repo.getUrl() == null || repo.getUrl().isEmpty()) {
        throw new RuntimeException("content.repos requires a url parameter.");
      }
      if (repo.getTarget() != null && !repo.getTarget().isEmpty()) {
        if (!repo.getTarget().contains("/")) {
          throw new RuntimeException(
              "content.target needs / to separate namespace/group from repo name. Repo: "
                  + repo.getUrl());
        }
      }

      switch (repo.getType()) {
        case COPY:
          if (repo.getTarget() == null || repo.getTarget().isEmpty()) {
            throw new RuntimeException(
                "content.repos.type "
                    + ContentRepoType.COPY
                    + " requires content.repos.target to be set. Repo: "
                    + repo.getUrl());
          }
          break;
        case FOLDER_BASED:
          if (repo.getTarget() != null && !repo.getTarget().isEmpty()) {
            throw new RuntimeException(
                "content.repos.type "
                    + ContentRepoType.FOLDER_BASED
                    + " does not support target parameter. Repo: "
                    + repo.getUrl());
          }
          if (repo.getTargetRef() != null && !repo.getTargetRef().isEmpty()) {
            throw new RuntimeException(
                "content.repos.type "
                    + ContentRepoType.FOLDER_BASED
                    + " does not support targetRef parameter. Repo: "
                    + repo.getUrl());
          }
          break;
        case MIRROR:
          if (repo.getTarget() == null || repo.getTarget().isEmpty()) {
            throw new RuntimeException(
                "content.repos.type "
                    + ContentRepoType.MIRROR
                    + " requires content.repos.target to be set. Repo: "
                    + repo.getUrl());
          }
          if (!ContentRepositorySchema.DEFAULT_PATH.equals(repo.getPath())) {
            throw new RuntimeException(
                "content.repos.type "
                    + ContentRepoType.MIRROR
                    + " does not support path. Current path: "
                    + repo.getPath()
                    + ". Repo: "
                    + repo.getUrl());
          }
          if (Boolean.TRUE.equals(repo.getTemplating())) {
            throw new RuntimeException(
                "content.repos.type "
                    + ContentRepoType.MIRROR
                    + " does not support templating. Repo: "
                    + repo.getUrl());
          }
          break;
      }
    }
  }

  protected void deployHelmReleasesFromContent() throws Exception {
    if (getConfig().getContent() == null
        || getConfig().getContent().getHelmReleases() == null
        || getConfig().getContent().getHelmReleases().isEmpty()) {
      log.debug("No content.helmReleases configured - skipping.");
      return;
    }

    for (Config.ContentSchema.HelmReleaseSchema helmRelease :
        getConfig().getContent().getHelmReleases()) {
      String version = helmRelease.getVersion() != null ? helmRelease.getVersion().trim() : "";
      if (version.isEmpty()) {
        version = "*";
      }

      Config.HelmConfigWithValues helmConfig = new Config.HelmConfigWithValues();
      helmConfig.setRepoURL(helmRelease.getRepoURL());
      helmConfig.setChart(helmRelease.getChart());
      helmConfig.setVersion(version);
      helmConfig.setValues(new HashMap<>());

      Map<String, Object> fileValues = new HashMap<>();
      if (helmRelease.getValuesPath() != null && !helmRelease.getValuesPath().trim().isEmpty()) {
        Map<String, Object> readValues =
            fileSystemUtils.readYaml(Path.of(helmRelease.getValuesPath()));
        if (readValues != null) {
          fileValues = readValues;
        }
      }

      Map<String, Object> inlineValues =
          helmRelease.getValues() != null ? helmRelease.getValues() : Collections.emptyMap();

      Map<String, Object> mergedValues = MapUtils.deepMerge(inlineValues, fileValues);

      Path mergedValuesFile = fileSystemUtils.writeTempFile(mergedValues);
      String mergedValuesFilePath = mergedValuesFile.toString();

      String releaseName =
          (helmRelease.getReleaseName() != null && !helmRelease.getReleaseName().isEmpty())
              ? helmRelease.getReleaseName()
              : helmRelease.getName();

      deployHelmChart(
          helmRelease.getName(),
          releaseName,
          helmRelease.getNamespace(),
          helmConfig,
          mergedValuesFilePath,
          context,
          false);

      repositoryWorkspace.commitAndPushClusterResourcesChanges(
          "Update " + releaseName + " GitOps resources");
    }
  }

  void createImagePullSecrets() {
    if (Boolean.TRUE.equals(getConfig().getRegistry().getCreateImagePullSecrets())) {
      String registryUsername =
          (getConfig().getRegistry().getReadOnlyUsername() != null
                  && !getConfig().getRegistry().getReadOnlyUsername().isEmpty())
              ? getConfig().getRegistry().getReadOnlyUsername()
              : getConfig().getRegistry().getUsername();

      String registryPassword =
          (getConfig().getRegistry().getReadOnlyPassword() != null
                  && !getConfig().getRegistry().getReadOnlyPassword().isEmpty())
              ? getConfig().getRegistry().getReadOnlyPassword()
              : getConfig().getRegistry().getPassword();

      for (String namespace : getConfig().getContent().getNamespaces()) {
        String registrySecretName = "registry";

        k8sClient.createNamespace(namespace);

        k8sClient.createImagePullSecret(
            registrySecretName,
            namespace,
            getConfig().getRegistry().getUrl(),
            registryUsername,
            registryPassword);

        k8sClient.patch(
            "serviceaccount",
            "default",
            namespace,
            Map.of("imagePullSecrets", List.of(Map.of("name", registrySecretName))));

        if (Boolean.TRUE.equals(getConfig().getRegistry().getTwoRegistries())) {
          k8sClient.createImagePullSecret(
              "proxy-registry",
              namespace,
              getConfig().getRegistry().getProxyUrl(),
              getConfig().getRegistry().getProxyUsername(),
              getConfig().getRegistry().getProxyPassword());
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
      mergedReposFolder =
          Files.createTempDirectory("gitops-playground-based-content-repos-").toFile();
    } catch (IOException e) {
      throw new RuntimeException("Failed to create temporary directory", e);
    }
    List<RepoCoordinate> repoCoordinates = new ArrayList<>();

    log.debug(
        "Aggregating structure for all {} repos.", getConfig().getContent().getRepos().size());
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
      List<RepoCoordinate> repoCoordinates)
      throws Exception {
    File repoTmpDir;
    try {
      repoTmpDir = Files.createTempDirectory("gitops-playground-content-repo-").toFile();
    } catch (IOException e) {
      throw new RuntimeException("Failed to create temporary directory", e);
    }
    log.debug(
        "Cloning content repo, {}, revision {}, path {}, overwriteMode {}",
        repoConfig.getUrl(),
        repoConfig.getRef(),
        repoConfig.getPath(),
        repoConfig.getOverwriteMode());

    UsernamePasswordCredentialsProvider credentialsProvider = null;
    if (repoConfig.getCredentials() != null
        && repoConfig.getCredentials().getUsername() != null
        && repoConfig.getCredentials().getPassword() != null) {
      credentialsProvider =
          new UsernamePasswordCredentialsProvider(
              repoConfig.getCredentials().getUsername(), repoConfig.getCredentials().getPassword());
    } else if (repoConfig.getCredentials() != null
        && repoConfig.getCredentials().getSecretName() != null
        && repoConfig.getCredentials().getSecretNamespace() != null) {
      Credentials credentials =
          this.k8sClient.getCredentialsFromSecret(repoConfig.getCredentials());
      credentialsProvider =
          new UsernamePasswordCredentialsProvider(
              credentials.getUsername(), credentials.getPassword());
    }

    cloneToLocalFolder(repoConfig, repoTmpDir, credentialsProvider);

    File contentRepoDir = new File(repoTmpDir, repoConfig.getPath());
    applyTemplatingIfApplicable(repoConfig, contentRepoDir);

    switch (repoConfig.getType()) {
      case FOLDER_BASED:
        createRepoCoordinatesForTypeFolderBased(
            repoConfig, repoTmpDir, contentRepoDir, mergedReposFolder, repoCoordinates);
        try {
          FileUtils.deleteDirectory(repoTmpDir);
        } catch (IOException ignored) {
        }
        break;
      case COPY:
        createRepoCoordinatesForTypeCopy(
            repoConfig, contentRepoDir, mergedReposFolder, repoTmpDir, repoCoordinates);
        try {
          FileUtils.deleteDirectory(repoTmpDir);
        } catch (IOException ignored) {
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

    RepoCoordinate repoCoordinate =
        mergeRepoDirs(contentRepoDir, namespace, repoName, mergedRepoFolder, repoConfig);
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
        RepoCoordinate repoCoordinate =
            mergeRepoDirs(contentRepoFolder, namespace, repoName, mergedRepoFolder, repoConfig);
        repoCoordinate.refIsTag = refIsTag;
        addRepoCoordinates(repoCoordinates, repoCoordinate);
      }
    }
  }

  private static void createRepoCoordinateForTypeMirror(
      ContentRepositorySchema repoConfig, File repoTmpDir, List<RepoCoordinate> repoCoordinates) {
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
    log.debug(
        "Merging content repo, namespace {}, repoName {} from {} to {}",
        namespace,
        repoName,
        src,
        target);
    try {
      FileUtils.copyDirectory(src, target, new FileSystemUtils.IgnoreDotGitFolderFilter());
    } catch (IOException e) {
      throw new RuntimeException("Failed to copy directory from " + src + " to " + target, e);
    }

    RepoCoordinate repoCoordinate = new RepoCoordinate();
    repoCoordinate.namespace = namespace;
    repoCoordinate.repoName = repoName;
    repoCoordinate.clonedContentRepo = target;
    repoCoordinate.repoConfig = repoConfig;
    return repoCoordinate;
  }

  private static List<File> findRepoDirectories(File srcRepo) {
    File[] files = srcRepo.listFiles();
    if (files == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(files)
        .filter(file -> file.isDirectory() && !file.getName().startsWith("."))
        .collect(Collectors.toList());
  }

  private void applyTemplatingIfApplicable(ContentRepositorySchema repoConfig, File srcPath) {
    if (Boolean.TRUE.equals(repoConfig.getTemplating())) {
      TemplatingEngine engine = getTemplatingEngine();

      try (GitRepo repo =
          this.repoProvider.create(repoConfig.getTarget(), this.gitHandler.getTenant())) {
        engine.replaceTemplates(
            srcPath,
            Map.of(
                "config", getConfig(),
                "scm",
                    Map.of(
                        "baseUrl", repo.getGitProvider().getUrl(),
                        "host", repo.getGitProvider().getHost(),
                        "protocol", repo.getGitProvider().getProtocol(),
                        "repoUrl", repo.getGitProvider().repoPrefix()),
                "statics",
                    !getConfig().getContent().getUseWhitelist()
                        ? new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32)
                            .build()
                            .getStaticModels()
                        : new AllowListFreemarkerObjectWrapper(
                                Configuration.VERSION_2_3_32,
                                getConfig().getContent().getAllowedStaticsWhitelist())
                            .getStaticModels()));
      } catch (Exception e) {
        throw new RuntimeException("Failed to replace templates in " + srcPath, e);
      }
    }
  }

  private void cloneToLocalFolder(
      ContentRepositorySchema repoConfig,
      File repoTmpDir,
      UsernamePasswordCredentialsProvider credentialsProvider) {
    CloneCommand cloneCommand =
        gitClone().setURI(repoConfig.getUrl()).setDirectory(repoTmpDir).setNoCheckout(false);

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

      LsRemoteCommand remoteCommand =
          Git.lsRemoteRepository().setRemote(repoConfig.getUrl()).setHeads(true).setTags(true);

      Collection<Ref> refs = remoteCommand.call();
      String potentialRef = null;
      for (Ref ref : refs) {
        if (ref.getName().equals("refs/heads/" + repoConfig.getRef())
            || ref.getName().equals("refs/tags/" + repoConfig.getRef())) {
          potentialRef = ref.getName();
          break;
        }
      }

      if (potentialRef == null) {
        throw new RuntimeException(
            "Reference '"
                + repoConfig.getRef()
                + "' not found in content repository '"
                + repoConfig.getUrl()
                + "'");
      }

      return potentialRef.replace("refs/heads/", "origin/");
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to find ref " + repoConfig.getRef() + " in repo " + repoConfig.getUrl(), e);
    }
  }

  private void pushTargetRepos(List<RepoCoordinate> repoCoordinates) throws Exception {
    for (RepoCoordinate repoCoordinate : repoCoordinates) {
      log.trace(
          "Preparing ContentLoader target repo '{}'. type='{}', overwriteMode='{}', targetRef='{}', refIsTag='{}', source='{}'",
          repoCoordinate.getFullRepoName(),
          repoCoordinate.repoConfig.getType(),
          repoCoordinate.repoConfig.getOverwriteMode(),
          repoCoordinate.repoConfig.getTargetRef(),
          repoCoordinate.refIsTag,
          repoCoordinate.clonedContentRepo != null
              ? repoCoordinate.clonedContentRepo.getAbsolutePath()
              : null);

      try (GitRepo targetRepo =
          repoProvider.create(repoCoordinate.getFullRepoName(), this.gitHandler.getTenant())) {
        boolean isNewRepo = targetRepo.createRepositoryAndSetPermission("", false);
        log.trace(
            "ContentLoader target repo '{}'. isNewRepo='{}', localTargetRepo='{}'",
            repoCoordinate.getFullRepoName(),
            isNewRepo,
            targetRepo.getAbsoluteLocalRepoTmpDir());

        if (isValidForPush(isNewRepo, repoCoordinate)) {
          targetRepo.cloneRepo();

          switch (repoCoordinate.repoConfig.getType()) {
            case MIRROR:
              handleRepoMirroring(repoCoordinate, targetRepo);
              break;
            case FOLDER_BASED:
            case COPY:
              handleRepoCopyingOrFolderBased(repoCoordinate, targetRepo, isNewRepo);
              break;
          }

          createJenkinsJobIfApplicable(repoCoordinate, targetRepo);

          log.trace(
              "Cleaning ContentLoader temp folders for repo '{}'. source='{}', target='{}'",
              repoCoordinate.getFullRepoName(),
              repoCoordinate.clonedContentRepo != null
                  ? repoCoordinate.clonedContentRepo.getAbsolutePath()
                  : null,
              targetRepo.getAbsoluteLocalRepoTmpDir());

          try {
            if (repoCoordinate.clonedContentRepo != null) {
              FileUtils.deleteDirectory(repoCoordinate.clonedContentRepo);
            }
            FileUtils.deleteDirectory(new File(targetRepo.getAbsoluteLocalRepoTmpDir()));
          } catch (IOException ignored) {
          }
        } else {
          log.debug(
              "Skipping ContentLoader push for repo '{}'. isNewRepo='{}', overwriteMode='{}'",
              repoCoordinate.getFullRepoName(),
              isNewRepo,
              repoCoordinate.repoConfig.getOverwriteMode());
        }
      }
    }
  }

  private static void handleRepoCopyingOrFolderBased(
      RepoCoordinate repoCoordinate, GitRepo targetRepo, boolean isNewRepo) throws Exception {
    log.trace(
        "Copying ContentLoader content into repo '{}'. isNewRepo='{}', overwriteMode='{}', source='{}', target='{}'",
        repoCoordinate.getFullRepoName(),
        isNewRepo,
        repoCoordinate.repoConfig.getOverwriteMode(),
        repoCoordinate.clonedContentRepo != null
            ? repoCoordinate.clonedContentRepo.getAbsolutePath()
            : null,
        targetRepo.getAbsoluteLocalRepoTmpDir());

    if (!isNewRepo) {
      clearTargetRepoIfApplicable(repoCoordinate, targetRepo);
    }

    try {
      targetRepo.copyDirectoryContents(
          repoCoordinate.clonedContentRepo.getAbsolutePath(),
          new FileSystemUtils.IgnoreDotGitFolderFilter());
    } catch (Exception e) {
      throw new RuntimeException("Failed to copy directory contents", e);
    }

    String commitMessage =
        "Initialize content repo " + repoCoordinate.namespace + "/" + repoCoordinate.repoName;
    String targetRefShort =
        repoCoordinate
            .repoConfig
            .getTargetRef()
            .replace("refs/heads/", "")
            .replace("refs/tags/", "");

    if (!targetRefShort.isEmpty()) {
      String refSpec = setRefSpec(repoCoordinate, targetRefShort);
      log.trace(
          "Committing ContentLoader repo '{}'. targetRefShort='{}', refSpec='{}'",
          repoCoordinate.getFullRepoName(),
          targetRefShort,
          refSpec);
      targetRepo.commitAndPush(commitMessage, targetRefShort, refSpec);
    } else {
      log.trace(
          "Committing ContentLoader repo '{}' to default main branch.",
          repoCoordinate.getFullRepoName());
      targetRepo.commitAndPush(commitMessage);
    }
  }

  private static String setRefSpec(RepoCoordinate repoCoordinate, String targetRefShort) {
    String refSpec;
    if ((repoCoordinate.refIsTag
            && !repoCoordinate.repoConfig.getTargetRef().startsWith("refs/heads"))
        || repoCoordinate.repoConfig.getTargetRef().startsWith("refs/tags")) {
      refSpec = "refs/tags/" + targetRefShort + ":refs/tags/" + targetRefShort;
    } else {
      refSpec = "HEAD:refs/heads/" + targetRefShort;
    }
    return refSpec;
  }

  private static void clearTargetRepoIfApplicable(
      RepoCoordinate repoCoordinate, GitRepo targetRepo) {
    if (OverwriteMode.INIT != repoCoordinate.repoConfig.getOverwriteMode()) {
      if (OverwriteMode.RESET == repoCoordinate.repoConfig.getOverwriteMode()) {
        log.info(
            "OverwriteMode "
                + OverwriteMode.RESET
                + " set for repo '"
                + repoCoordinate.getFullRepoName()
                + "': "
                + "Deleting existing files in repo and replacing them with new content.");
        targetRepo.clearRepo();
      } else {
        log.debug(
            "OverwriteMode "
                + OverwriteMode.UPGRADE
                + " set for repo '"
                + repoCoordinate.getFullRepoName()
                + "': "
                + "Merging new content into existing repo. ");
      }
    }
  }

  private static void handleRepoMirroring(RepoCoordinate repoCoordinate, GitRepo targetRepo)
      throws Exception {
    try (Git targetGit = Git.open(new File(targetRepo.getAbsoluteLocalRepoTmpDir()))) {
      String remoteUrl = targetGit.getRepository().getConfig().getString("remote", "origin", "url");

      FileSystemUtils.makeWritable(new File(targetRepo.getAbsoluteLocalRepoTmpDir(), ".git"));

      targetRepo.copyDirectoryContents(repoCoordinate.clonedContentRepo.getAbsolutePath());

      targetGit.getRepository().getConfig().setString("remote", "origin", "url", remoteUrl);
      targetGit.getRepository().getConfig().save();
    } catch (Exception e) {
      throw new RuntimeException("Failed to open or configure mirrored Git repo", e);
    }

    if (repoCoordinate.repoConfig.getRef() != null
        && !repoCoordinate.repoConfig.getRef().isEmpty()) {
      validateCommitReferences(repoCoordinate);
      if (repoCoordinate.repoConfig.getTargetRef() != null
          && !repoCoordinate.repoConfig.getTargetRef().isEmpty()) {
        log.debug(
            "Mirroring repo '{}' ref '{}' to target repo {}, targetRef: '{}'",
            repoCoordinate.repoConfig.getUrl(),
            repoCoordinate.repoConfig.getRef(),
            repoCoordinate.getFullRepoName(),
            repoCoordinate.repoConfig.getTargetRef());
        targetRepo.pushRef(
            repoCoordinate.repoConfig.getRef(), repoCoordinate.repoConfig.getTargetRef(), true);
      } else {
        log.debug(
            "Mirroring repo '{}' ref '{}' to target repo {}",
            repoCoordinate.repoConfig.getUrl(),
            repoCoordinate.repoConfig.getRef(),
            repoCoordinate.getFullRepoName());
        targetRepo.pushRef(repoCoordinate.repoConfig.getRef(), true);
      }
    } else {
      log.debug(
          "Mirroring whole repo '{}' to target repo {}",
          repoCoordinate.repoConfig.getUrl(),
          repoCoordinate.getFullRepoName());
      targetRepo.pushAll(true);
    }
  }

  private static void validateCommitReferences(RepoCoordinate repoCoordinate) {
    if (GitRepo.isCommit(repoCoordinate.clonedContentRepo, repoCoordinate.repoConfig.getRef())) {
      throw new RuntimeException(
          "Mirroring commit references is not supported for content repos at the moment. content repository '"
              + repoCoordinate.repoConfig.getUrl()
              + "', ref: "
              + repoCoordinate.repoConfig.getRef());
    }
  }

  private void createJenkinsJobIfApplicable(RepoCoordinate repoCoordinate, GitRepo repo) {
    if (repoCoordinate.repoConfig.getCreateJenkinsJob() && jenkins.isEnabled(context)) {
      if (GitRepo.existFileInSomeBranch(repo.getAbsoluteLocalRepoTmpDir(), "Jenkinsfile")) {
        jenkins.createJenkinsjob(repoCoordinate.namespace, repoCoordinate.namespace);
      }
    }
  }

  protected CloneCommand gitClone() {
    return Git.cloneRepository();
  }

  static void addRepoCoordinates(
      List<RepoCoordinate> repoCoordinates, RepoCoordinate newRepoCoordinate) {
    List<RepoCoordinate> existingRepoCoordinates = newRepoCoordinate.findSame(repoCoordinates);

    if (!existingRepoCoordinates.isEmpty()) {
      log.debug(
          "Found existing repo coordinates for {}: {}", newRepoCoordinate, existingRepoCoordinates);

      RepoCoordinate repoCoordinateToOverwrite =
          newRepoCoordinate.findSameNotMirror(existingRepoCoordinates);
      if (repoCoordinateToOverwrite != null) {
        repoCoordinates.remove(repoCoordinateToOverwrite);
        log.debug(
            "Replacing existing repo coordinate {} with new one: {}",
            existingRepoCoordinates,
            newRepoCoordinate);
      }
    }
    repoCoordinates.add(newRepoCoordinate);
  }

  static boolean isValidForPush(boolean isNewRepo, RepoCoordinate repoCoordinate) {
    if (!isNewRepo && OverwriteMode.INIT == repoCoordinate.repoConfig.getOverwriteMode()) {
      log.warn(
          "OverwriteMode "
              + OverwriteMode.INIT
              + " set for repo '"
              + repoCoordinate.getFullRepoName()
              + "' and repo already exists in target:  Not pushing content!"
              + "If you want to override, set "
              + OverwriteMode.UPGRADE
              + " or "
              + OverwriteMode.RESET
              + " .");
      return false;
    }
    return true;
  }

  private void clearCache() {
    if (mergedReposFolder != null) {
      try {
        FileUtils.deleteDirectory(mergedReposFolder);
      } catch (IOException ignored) {
      }
    }
    cachedRepoCoordinates.clear();
    mergedReposFolder = null;
  }

  @Getter
  @Setter
  public static class RepoCoordinate {
    String namespace;
    String repoName;
    File clonedContentRepo;
    ContentRepositorySchema repoConfig;
    boolean refIsTag;

    @Override
    public String toString() {
      return "RepoCoordinates{ namespace='"
          + namespace
          + "', repoName='"
          + repoName
          + "', repoConfig.type='"
          + repoConfig.getType()
          + "', repoConfig.overwriteMode='"
          + repoConfig.getOverwriteMode()
          + "', clonedContentRepo="
          + clonedContentRepo
          + "', refIsTag='"
          + refIsTag
          + "' }";
    }

    public String getFullRepoName() {
      return namespace + "/" + repoName;
    }

    public List<RepoCoordinate> findSame(List<RepoCoordinate> repoCoordinates) {
      return repoCoordinates.stream()
          .filter(it -> it.getFullRepoName().equals(getFullRepoName()))
          .collect(Collectors.toList());
    }

    public RepoCoordinate findSameNotMirror(List<RepoCoordinate> repoCoordinates) {
      return repoCoordinates.stream()
          .filter(
              it ->
                  it.getFullRepoName().equals(getFullRepoName())
                      && ContentRepoType.MIRROR != it.repoConfig.getType())
          .findFirst()
          .orElse(null);
    }
  }
}
