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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class GitRepo implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GitRepo.class);

    public static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = "3rd-party-dependencies";

    private final Config config;
    public GitProvider gitProvider;
    private final FileSystemUtils fileSystemUtils;

    private final String repoTarget;
    private final boolean insecure;
    private final String gitName;
    private final String gitEmail;

    private Git gitMemoization;
    private final String absoluteLocalRepoTmpDir;

    public GitRepo(Config config,
                   GitProvider gitProvider,
                   String repoTarget,
                   FileSystemUtils fileSystemUtils) {
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

        this.insecure = Boolean.TRUE.equals(config.getApplication().getInsecure());
        this.gitName = config.getApplication().getGitName();
        this.gitEmail = config.getApplication().getGitEmail();
    }

    public String getRepoTarget() {
        return repoTarget;
    }

    public GitProvider getGitProvider() {
        return gitProvider;
    }

    public void setGitProvider(GitProvider gitProvider) {
        this.gitProvider = gitProvider;
    }

    public boolean createRepositoryAndSetPermission(String description, boolean initialize) {
        boolean isNewRepo = this.gitProvider.createRepository(repoTarget, description, initialize);
        String gitOpsUsername = gitProvider.getGitOpsUsername();
        if (gitOpsUsername != null && !gitOpsUsername.isEmpty()) {
            gitProvider.setRepositoryPermission(repoTarget,
                    gitOpsUsername,
                    AccessRole.WRITE,
                    Scope.USER);
        }
        return isNewRepo;
    }

    public boolean createRepositoryAndSetPermission(String description) {
        return createRepositoryAndSetPermission(description, true);
    }

    public String getAbsoluteLocalRepoTmpDir() {
        return absoluteLocalRepoTmpDir;
    }

    public void cloneRepo() throws GitAPIException {
        String cloneUrl = getGitRepositoryUrl();
        log.debug("Cloning {}, Origin: {}", repoTarget, cloneUrl);
        try (Git git = Git.cloneRepository()
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

        try (Git git = Git.init()
                .setDirectory(localRepoDir)
                .call()) {

            // Configure the 'origin' remote so init'd repos behave like cloned ones.
            // pullRebaseMain() pulls from the remote name 'origin'; without this the
            // repo has no remote.origin.url and JGit fails with
            // "No value for key remote.origin.url found in configuration".
            git.remoteAdd()
                    .setName("origin")
                    .setUri(new URIish(getGitRepositoryUrl()))
                    .call();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Invalid git repository URL: " + getGitRepositoryUrl(), e);
        }
    }

    public void pullRebaseMain() throws GitAPIException {
        log.debug("Pulling remote main with rebase for repo {}", repoTarget);

        getGit().pull()
                .setRemote("origin")
                .setRemoteBranchName("main")
                .setRebase(true)
                .setCredentialsProvider(getCredentialProvider())
                .call();
    }

    public void commitAndPush(String message, String tag) throws GitAPIException {
        commitAndPush(message, tag, "HEAD:refs/heads/main");
    }

    public void commitAndPush(String commitMessage, String tag, String refSpec) throws GitAPIException {
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
                git.tag()
                        .setName(tag)
                        .call();

                pushCommand.setPushTags();
            }

            log.debug("Pushing repo: {}, refSpec: {}", repoTarget, refSpec);

            Iterable<PushResult> pushResults = pushCommand.call();

            for (PushResult result : pushResults) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    log.debug("Push result for repo '{}': remoteName='{}', status='{}', message='{}'",
                            repoTarget,
                            update.getRemoteName(),
                            update.getStatus(),
                            update.getMessage());

                    if (update.getStatus() != Status.OK && update.getStatus() != Status.UP_TO_DATE) {
                        throw new RuntimeException("Push failed for repo '" + repoTarget +
                                "', remoteName='" + update.getRemoteName() +
                                "', status='" + update.getStatus() +
                                "', message='" + update.getMessage() + "'");
                    }
                }
            }
        } else {
            log.debug("No changes after add, nothing to commit or push on repo: {}", repoTarget);
        }
    }

    public void commitAndPush(String commitMessage) throws GitAPIException {
        commitAndPush(commitMessage, null, "HEAD:refs/heads/main");
    }

    /**
     * Push all refs, i.e. all tags and branches
     */
    public void pushAll(boolean force) throws GitAPIException {
        createPushCommand("refs/*:refs/*").setForce(force).call();
    }

    public void pushRef(String ref, boolean force) throws GitAPIException {
        pushRef(ref, ref, force);
    }

    public void pushRef(String ref, String targetRef, boolean force) throws GitAPIException {
        createPushCommand(ref + ":" + targetRef).setForce(force).call();
    }

    /**
     * Delete all files in this repository
     */
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
        String absoluteSrcDirLocation = new File(srcDir).isAbsolute()
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
        if (!file.exists()) {
            file.createNewFile();
        }
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

        git.fetch()
                .setRemote("origin")
                .setCredentialsProvider(getCredentialProvider())
                .call();

        Ref localMain = git.getRepository().findRef("refs/heads/main");
        Ref remoteMain = git.getRepository().findRef("refs/remotes/origin/main");

        if (localMain != null) {
            git.checkout()
                    .setName("main")
                    .call();
            return;
        }

        if (remoteMain != null) {
            log.debug("Creating local main branch from origin/main for repo '{}'", repoTarget);

            git.checkout()
                    .setCreateBranch(true)
                    .setName("main")
                    .setStartPoint("origin/main")
                    .call();
            return;
        }

        throw new IllegalStateException("Cannot bootstrap repository '" + repoTarget +
                "' because remote branch 'origin/main' does not exist. " +
                "The SCM-Manager repository must be created and initialized before GOP can push generated resources.");
    }

    public static boolean isCommit(File repoPath, String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }

        try (Git git = Git.open(repoPath)) {
            // Get all branch and tag names
            List<String> allRefs = new ArrayList<>();

            // Add all branch names (without refs/heads/ prefix)
            List<Ref> branches = git.branchList().call();
            for (Ref branch : branches) {
                allRefs.add(branch.getName().replaceFirst("^refs/heads/", ""));
            }

            // Add all tag names (without refs/tags/ prefix)
            List<Ref> tags = git.tagList().call();
            for (Ref tag : tags) {
                allRefs.add(tag.getName().replaceFirst("^refs/tags/", ""));
            }

            // If the ref matches any branch or tag name, it's not a commit hash
            if (allRefs.contains(ref)) {
                return false;
            }

            // If it's not a branch or tag, try to resolve it as a commit
            ObjectId objectId = git.getRepository().resolve(ref);
            return objectId != null;

        } catch (GitAPIException | IOException e) {
            log.warn("Error checking if ref '{}' is a commit in repo '{}': {}", ref, repoPath, e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a file exists in the repository in some branch.
     * @param repo the repository path
     * @param filename the filename to search for
     * @return true if the file exists in some branch, false otherwise
     */
    public static boolean existFileInSomeBranch(String repo, String filename) {
        File repoPath = new File(repo);

        try (Git git = Git.open(repoPath)) {
            List<Ref> branches = git.branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call();

            for (Ref branch : branches) {
                String branchName = branch.getName();

                ObjectId commitId = git.getRepository().resolve(branchName);
                if (commitId == null) {
                    continue;
                }
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
            }
        } catch (IOException | GitAPIException e) {
            log.warn("Error checking if file '{}' exists in repo '{}': {}", filename, repoPath, e.getMessage());
        }
        log.debug("File {} not found in repository {}", filename, repoPath);
        return false;
    }

    public static boolean isTag(File repo, String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        try (Git git = Git.open(repo)) {
            List<Ref> tags = git.tagList().call();
            for (Ref tag : tags) {
                if (tag.getName().endsWith("/" + ref) || tag.getName().equals(ref)) {
                    return true;
                }
            }
        } catch (IOException | GitAPIException e) {
            log.warn("Error checking if ref '{}' is a tag in repo '{}': {}", ref, repo, e.getMessage());
        }
        return false;
    }

    private PushCommand createPushCommand(String refSpec) {
        return getGit().push()
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
            throw new UncheckedIOException("Failed to open git repository at: " + absoluteLocalRepoTmpDir, e);
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
    }
}
