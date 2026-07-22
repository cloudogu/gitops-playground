package com.cloudogu.gitops.infrastructure.git;

import com.cloudogu.gitops.cli.Version;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.RepoUrlScope;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.cloudogu.gitops.utils.TemplatingEngine;
import com.cloudogu.gitops.utils.jgit.helpers.InsecureCredentialProvider;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ChainingCredentialsProvider;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

@Slf4j
public class GitRepo implements AutoCloseable {

  public static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = "3rd-party-dependencies";
  private static final String GIT_REMOTE_ORIGIN = "origin";
  private static final Pattern REFS_HEADS_PREFIX = Pattern.compile("^refs/heads/");
  private static final Pattern REFS_TAGS_PREFIX = Pattern.compile("^refs/tags/");

  private final Config config;
  @Getter @Setter private GitProvider gitProvider;
  private final FileSystemUtils fileSystemUtils;

  @Getter private final String repoTarget;
  private final boolean insecure;
  private final String gitName;
  private final String gitEmail;

  private Git gitMemoization;
  @Getter private final String absoluteLocalRepoTmpDir;

  public GitRepo(
      Config config, GitProvider gitProvider, String repoTarget, FileSystemUtils fileSystemUtils) {
    try {
      File tmpDir = Files.createTempDirectory("gitops-playground-").toFile();
      tmpDir.deleteOnExit();
      this.absoluteLocalRepoTmpDir = tmpDir.getAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create temporary directory", e);
    }
    this.config = config;
    this.gitProvider = gitProvider;
    this.fileSystemUtils = fileSystemUtils;

    this.repoTarget = config.getApplication().getNamePrefix() + repoTarget;

    this.insecure = config.getApplication().getInsecure();
    this.gitName = config.getApplication().getGitName();
    this.gitEmail = config.getApplication().getGitEmail();
  }

  public boolean createRepositoryAndSetPermission(String description, boolean initialize) {
    boolean isNewRepo = this.gitProvider.createRepository(repoTarget, description, initialize);
    String gitOpsUsername = gitProvider.getGitOpsUsername();
    if (gitOpsUsername != null && !gitOpsUsername.isEmpty()) {
      gitProvider.setRepositoryPermission(repoTarget, gitOpsUsername, AccessRole.WRITE, Scope.USER);
    }
    return isNewRepo;
  }

  public boolean createRepositoryAndSetPermission(String description) {
    return createRepositoryAndSetPermission(description, true);
  }

  public void cloneRepo() throws GitAPIException {
    String cloneUrl = getGitRepositoryUrl();
    log.debug("Cloning {}, Origin: {}", repoTarget, cloneUrl);
    try (Git git =
        Git.cloneRepository()
            .setURI(cloneUrl)
            .setDirectory(new File(absoluteLocalRepoTmpDir))
            .setCredentialsProvider(getCredentialProvider())
            .call()) {
      // Cloned successfully, try-with-resources closes the git reference
    }
  }

  public void initLocalRepoIfNeeded() throws GitAPIException {
    File localRepoDir = new File(getAbsoluteLocalRepoTmpDir());
    File gitDir = new File(localRepoDir, ".git");

    if (gitDir.exists()) {
      log.debug("Local git repository already initialized at {}", localRepoDir);
      return;
    }

    log.debug("Initializing local git repository at {}", localRepoDir);

    if (!localRepoDir.exists() && !localRepoDir.mkdirs()) {
      log.warn("Failed to create directory {}", localRepoDir);
    }

    try (Git git = Git.init().setDirectory(localRepoDir).call()) {

      // Configure the 'origin' remote so init'd repos behave like cloned ones.
      // pullRebaseMain() pulls from the remote name 'origin'; without this the
      // repo has no remote.origin.url and JGit fails with
      // "No value for key remote.origin.url found in configuration".
      git.remoteAdd().setName(GIT_REMOTE_ORIGIN).setUri(new URIish(getGitRepositoryUrl())).call();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid git repository URL: " + getGitRepositoryUrl(), e);
    }
  }

  public void pullRebaseMain() throws GitAPIException {
    log.debug("Pulling remote main with rebase for repo {}", repoTarget);

    getGit()
        .pull()
        .setRemote(GIT_REMOTE_ORIGIN)
        .setRemoteBranchName("main")
        .setRebase(true)
        .setCredentialsProvider(getCredentialProvider())
        .call();
  }

  public void commitAndPush(String message, String tag) throws GitAPIException {
    commitAndPush(message, tag, "HEAD:refs/heads/main");
  }

  public void commitAndPush(String commitMessage, String tag, String refSpec)
      throws GitAPIException {
    log.debug("Adding files to {}", repoTarget);

    Git git = getGit();
    git.add().addFilepattern(".").call();

    if (git.status().call().hasUncommittedChanges()) {
      log.debug("Commiting {}", repoTarget);

      String cleanVersion = Version.NAME.split(",")[0].replace("(", "");
      String committerName = gitName + " - GOP v" + cleanVersion;

      git.commit()
          .setSign(false)
          .setMessage(commitMessage)
          .setAuthor(gitName, gitEmail)
          .setCommitter(committerName, gitEmail)
          .call();

      PushCommand pushCommand = createPushCommand(refSpec);

      if (tag != null && !tag.isEmpty()) {
        log.debug("Setting tag '{}' on repo: {}", tag, repoTarget);

        // Delete existing tags first to get idempotence
        git.tagDelete().setTags(tag).call();
        git.tag().setName(tag).call();

        pushCommand.setPushTags();
      }

      log.debug("Pushing repo: {}, refSpec: {}", repoTarget, refSpec);

      Iterable<PushResult> pushResults = pushCommand.call();
      validatePushResults(pushResults, repoTarget);
    } else {
      log.debug("No changes after add, nothing to commit or push on repo: {}", repoTarget);
    }
  }

  private static void validatePushResults(Iterable<PushResult> pushResults, String repoTarget) {
    for (PushResult result : pushResults) {
      for (RemoteRefUpdate update : result.getRemoteUpdates()) {
        log.debug(
            "Push result for repo '{}': remoteName='{}', status='{}', message='{}'",
            repoTarget,
            update.getRemoteName(),
            update.getStatus(),
            update.getMessage());

        if (update.getStatus() != Status.OK && update.getStatus() != Status.UP_TO_DATE) {
          throw new IllegalStateException(
              "Push failed for repo '"
                  + repoTarget
                  + "', remoteName='"
                  + update.getRemoteName()
                  + "', status='"
                  + update.getStatus()
                  + "', message='"
                  + update.getMessage()
                  + "'");
        }
      }
    }
  }

  public void commitAndPush(String commitMessage) throws GitAPIException {
    commitAndPush(commitMessage, null, "HEAD:refs/heads/main");
  }

  /** Push all refs, i.e. all tags and branches */
  public void pushAll(boolean force) throws GitAPIException {
    createPushCommand("refs/*:refs/*").setForce(force).call();
  }

  public void pushRef(String ref, boolean force) throws GitAPIException {
    pushRef(ref, ref, force);
  }

  public void pushRef(String ref, String targetRef, boolean force) throws GitAPIException {
    createPushCommand(ref + ":" + targetRef).setForce(force).call();
  }

  /** Delete all files in this repository */
  public void clearRepo() {
    fileSystemUtils.deleteFilesExcept(new File(absoluteLocalRepoTmpDir), ".git");
  }

  public void copyDirectoryContents(String srcDir) {
    copyDirectoryContents(srcDir, (FileFilter) null);
  }

  public void copyDirectoryContents(String srcDir, FileFilter fileFilter) {
    if (srcDir == null || srcDir.isEmpty()) {
      log.warn("Source directory is not defined. Nothing to copy?");
      return;
    }

    log.debug("Initializing repo {} from {}", repoTarget, srcDir);
    String absoluteSrcDirLocation =
        new File(srcDir).isAbsolute()
            ? srcDir
            : Path.of(fileSystemUtils.getRootDir(), srcDir).toString();
    fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir, fileFilter);
  }

  public void writeFile(String path, String content) throws IOException {
    File file = new File(absoluteLocalRepoTmpDir, path);
    fileSystemUtils.createDirectory(file.getParent());
    if (file.isDirectory()) {
      throw new java.io.FileNotFoundException(file.getAbsolutePath() + " (Is a directory)");
    }
    // Files.writeString creates the file if it doesn't exist yet.
    Files.writeString(file.toPath(), content);
  }

  public void replaceTemplates(Map<String, Object> parameters) {
    try {
      new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters);
    } catch (IOException | freemarker.template.TemplateException e) {
      throw new RuntimeException("Failed to replace templates in: " + absoluteLocalRepoTmpDir, e);
    }
  }

  public String getGitRepositoryUrl() {
    return this.gitProvider.repoUrl(repoTarget, RepoUrlScope.CLIENT);
  }

  public void checkoutRemoteMainIfLocalMainMissing() throws GitAPIException, IOException {
    initLocalRepoIfNeeded();

    Git git = getGit();

    git.fetch().setRemote(GIT_REMOTE_ORIGIN).setCredentialsProvider(getCredentialProvider()).call();

    Ref localMain = git.getRepository().findRef("refs/heads/main");

    if (localMain != null) {
      git.checkout().setName("main").call();
      return;
    }

    Ref remoteMain = git.getRepository().findRef("refs/remotes/origin/main");
    if (remoteMain != null) {
      log.debug("Creating local main branch from origin/main for repo '{}'", repoTarget);

      git.checkout().setCreateBranch(true).setName("main").setStartPoint("origin/main").call();
      return;
    }

    throw new IllegalStateException(
        "Cannot bootstrap repository '"
            + repoTarget
            + "' because remote branch 'origin/main' does not exist. "
            + "The SCM-Manager repository must be created and initialized before GOP can push generated resources.");
  }

  public static boolean isCommit(File repoPath, String ref) {
    if (ref == null || ref.isEmpty()) {
      return false;
    }

    return withGitOrFalse(
        repoPath,
        "checking if ref '" + ref + "' is a commit in repo '" + repoPath + "'",
        (Git git) -> resolveIsCommit(git, ref));
  }

  private static boolean resolveIsCommit(Git git, String ref) throws IOException, GitAPIException {
    // Get all branch and tag names
    List<String> allRefs = new ArrayList<>();

    // Add all branch names (without refs/heads/ prefix)
    List<Ref> branches = git.branchList().call();
    for (Ref branch : branches) {
      allRefs.add(REFS_HEADS_PREFIX.matcher(branch.getName()).replaceFirst(""));
    }

    // Add all tag names (without refs/tags/ prefix)
    List<Ref> tags = git.tagList().call();
    for (Ref tag : tags) {
      allRefs.add(REFS_TAGS_PREFIX.matcher(tag.getName()).replaceFirst(""));
    }

    // If the ref matches any branch or tag name, it's not a commit hash
    if (allRefs.contains(ref)) {
      return false;
    }

    // If it's not a branch or tag, try to resolve it as a commit
    ObjectId objectId = git.getRepository().resolve(ref);
    return objectId != null;
  }

  /**
   * Checks if a file exists in the repository in some branch.
   *
   * @param repo the repository path
   * @param filename the filename to search for
   * @return true if the file exists in some branch, false otherwise
   */
  public static boolean existFileInSomeBranch(String repo, String filename) {
    File repoPath = new File(repo);

    boolean found =
        withGitOrFalse(
            repoPath,
            "checking if file '" + filename + "' exists in repo '" + repoPath + "'",
            (Git git) -> resolveExistsInSomeBranch(git, filename));

    if (!found) {
      log.debug("File {} not found in repository {}", filename, repoPath);
    }
    return found;
  }

  private static boolean resolveExistsInSomeBranch(Git git, String filename)
      throws IOException, GitAPIException {
    List<Ref> branches = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();

    for (Ref branch : branches) {
      String branchName = branch.getName();

      ObjectId commitId = git.getRepository().resolve(branchName);
      if (commitId != null && branchContainsFile(git, commitId, filename, branchName)) {
        return true;
      }
    }
    return false;
  }

  private static boolean branchContainsFile(
      Git git, ObjectId commitId, String filename, String branchName) throws IOException {
    try (RevWalk revWalk = new RevWalk(git.getRepository())) {
      RevCommit commit = revWalk.parseCommit(commitId);
      try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setFilter(PathFilter.create(filename));

        if (treeWalk.next()) {
          log.debug("File {} found in branch {}", filename, branchName);
          return true;
        }
      }
    }
    return false;
  }

  public static boolean isTag(File repo, String ref) {
    if (ref == null || ref.isEmpty()) {
      return false;
    }
    return withGitOrFalse(
        repo,
        "checking if ref '" + ref + "' is a tag in repo '" + repo + "'",
        (Git git) -> {
          List<Ref> tags = git.tagList().call();
          for (Ref tag : tags) {
            if (tag.getName().endsWith("/" + ref) || tag.getName().equals(ref)) {
              return true;
            }
          }
          return false;
        });
  }

  /**
   * Opens the git repository at {@code repoPath} and runs {@code operation} against it, returning
   * its result. If the repository can't be opened or the operation throws, logs a warning with
   * {@code errorContext} and returns {@code false}. Centralizes the try-with-resources/catch
   * pattern shared by the static ref-inspection helpers above.
   */
  private static boolean withGitOrFalse(
      File repoPath, String errorContext, GitBooleanOperation operation) {
    try (Git git = Git.open(repoPath)) {
      return operation.execute(git);
    } catch (IOException | GitAPIException e) {
      log.warn("Error {}: {}", errorContext, e.getMessage());
      return false;
    }
  }

  @FunctionalInterface
  private interface GitBooleanOperation {
    boolean execute(Git git) throws IOException, GitAPIException;
  }

  private PushCommand createPushCommand(String refSpec) {
    return getGit()
        .push()
        .setRemote(getGitRepositoryUrl())
        .setRefSpecs(new RefSpec(refSpec))
        .setCredentialsProvider(getCredentialProvider());
  }

  private Git getGit() {
    if (gitMemoization != null) {
      return gitMemoization;
    }

    try {
      gitMemoization = Git.open(new File(absoluteLocalRepoTmpDir));
      return gitMemoization;
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to open git repository at: " + absoluteLocalRepoTmpDir, e);
    }
  }

  private CredentialsProvider getCredentialProvider() {
    Credentials auth = this.gitProvider.getCredentials();
    UsernamePasswordCredentialsProvider passwordAuthentication =
        new UsernamePasswordCredentialsProvider(auth.getUsername(), auth.getPassword());
    return insecure
        ? new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication)
        : passwordAuthentication;
  }

  @Override
  public void close() {
    if (gitMemoization != null) {
      gitMemoization.close();
      gitMemoization = null;
    }
    FileSystemUtils.deleteDir(absoluteLocalRepoTmpDir);
  }
}
