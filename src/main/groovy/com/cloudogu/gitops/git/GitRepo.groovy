package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.jgit.helpers.InsecureCredentialProvider
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.RepoUrlScope
import com.cloudogu.gitops.git.providers.Scope
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

@Slf4j
class GitRepo {

    static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = '3rd-party-dependencies'

    private final Config config
    public GitProvider gitProvider
    private final FileSystemUtils fileSystemUtils

    private final String repoTarget
    private final boolean insecure
    private final String gitName
    private final String gitEmail

    private Git gitMemoization
    private final String absoluteLocalRepoTmpDir

    GitRepo(Config config,
            GitProvider gitProvider,
            String repoTarget,
            FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
        this.config = config
        this.gitProvider = gitProvider
        this.fileSystemUtils = fileSystemUtils

        this.repoTarget = "${config.application.namePrefix}${repoTarget}"

        this.insecure = config.application.insecure
        this.gitName = config.application.gitName
        this.gitEmail = config.application.gitEmail
    }

    String getRepoTarget() {
        return repoTarget
    }
    
    boolean createRepositoryAndSetPermission(String description, boolean initialize = true) {
        def isNewRepo = this.gitProvider.createRepository(repoTarget, description, initialize)
        if (gitProvider.getGitOpsUsername()) {
            gitProvider.setRepositoryPermission(
                    repoTarget,
                    gitProvider.getGitOpsUsername(),
                    AccessRole.WRITE,
                    Scope.USER
            )
        }
        return isNewRepo

    }

    String getAbsoluteLocalRepoTmpDir() {
        return absoluteLocalRepoTmpDir
    }

    void cloneRepo() {
        def cloneUrl = getGitRepositoryUrl()
        log.debug("Cloning ${repoTarget}, Origin: ${cloneUrl}")
        Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(new File(absoluteLocalRepoTmpDir))
                .setCredentialsProvider(getCredentialProvider())
                .call()
    }

    void commitAndPush(String message, String tag) {
        commitAndPush(message, tag, 'HEAD:refs/heads/main')
    }


    void commitAndPush(String commitMessage, String tag, String refSpec) {
        log.debug("Adding files to ${repoTarget}")
        def git = getGit()
        git.add().addFilepattern(".").call()

        if (git.status().call().hasUncommittedChanges()) {
            log.debug("Commiting ${repoTarget}")
            git.commit()
                    .setSign(false)
                    .setMessage(commitMessage)
                    .setAuthor(gitName, gitEmail)
                    .setCommitter(gitName, gitEmail)
                    .call()

            def pushCommand = createPushCommand(refSpec)

            if (tag) {
                log.debug("Setting tag '${tag}' on repo: ${repoTarget}")
                // Delete existing tags first to get idempotence
                git.tagDelete().setTags(tag).call()
                git.tag()
                        .setName(tag)
                        .call()
                pushCommand.setPushTags()
            }

            log.debug("Pushing repo: ${repoTarget}, refSpec: ${refSpec}")
            pushCommand.call()
        } else {
            log.debug("No changes after add, nothing to commit or push on repo: ${repoTarget}")
        }
    }


    void commitAndPush(String commitMessage) {
        commitAndPush(commitMessage, null, 'HEAD:refs/heads/main')
    }
    /**
     * Push all refs, i.e. all tags and branches
     */

    void pushAll(boolean force) {
        createPushCommand('refs/*:refs/*').setForce(force).call()
    }


    void pushRef(String ref, boolean force) {
        pushRef(ref, ref, force)
    }


    void pushRef(String ref, String targetRef, boolean force) {
        createPushCommand("${ref}:${targetRef}").setForce(force).call()
    }


    /**
     * Delete all files in this repository
     */
    void clearRepo() {
        fileSystemUtils.deleteFilesExcept(new File(absoluteLocalRepoTmpDir), ".git")
    }


    void copyDirectoryContents(String srcDir) {
        copyDirectoryContents(srcDir, (FileFilter) null)
    }


    void copyDirectoryContents(String srcDir, FileFilter fileFilter) {
        if (!srcDir) {
            log.warn("Source directory is not defined. Nothing to copy?")
            return
        }

        log.debug("Initializing repo $repoTarget from $srcDir")
        String absoluteSrcDirLocation = new File(srcDir).isAbsolute()
                ? srcDir
                : "${fileSystemUtils.getRootDir()}/${srcDir}"
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir, fileFilter)
    }


    void writeFile(String path, String content) {
        def file = new File("$absoluteLocalRepoTmpDir/$path")
        fileSystemUtils.createDirectory(file.parent)
        file.createNewFile()
        file.text = content
    }

    void replaceTemplates(Map parameters) {
        new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters)
    }

    String getGitRepositoryUrl() {
        return this.gitProvider.repoUrl(repoTarget, RepoUrlScope.CLIENT)
    }

    static boolean isCommit(File repoPath, String ref) {
        if (!ref) {
            return false
        }

        try (Git git = Git.open(repoPath)) {
            // Get all branch and tag names
            def allRefs = []

            // Add all branch names (without refs/heads/ prefix)
            git.branchList().call().each { branch ->
                allRefs.add(branch.name.replaceFirst('refs/heads/', ''))
            }

            // Add all tag names (without refs/tags/ prefix)
            git.tagList().call().each { tag ->
                allRefs.add(tag.name.replaceFirst('refs/tags/', ''))
            }

            // If the ref matches any branch or tag name, it's not a commit hash
            if (allRefs.contains(ref)) {
                return false
            }

            // If it's not a branch or tag, try to resolve it as a commit
            def objectId = git.repository.resolve(ref)
            return objectId != null

        }
    }

    /**
     * checks, if file exists in repo in some branch.
     * @param pathToRepo
     * @param filename
     */
    static boolean existFileInSomeBranch(String repo, String filename) {
        String filenameToSearch = filename
        File repoPath = new File(repo + '/.git')

        try (def git = Git.open(repoPath)) {
            List<Ref> branches = git
                    .branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call()

            for (Ref branch : branches) {
                String branchName = branch.getName()

                ObjectId commitId = git.repository.resolve(branchName)
                if (commitId == null) {
                    continue
                }
                try (RevWalk revWalk = new RevWalk(git.repository)) {
                    RevCommit commit = revWalk.parseCommit(commitId)
                    try (TreeWalk treeWalk = new TreeWalk(git.repository)) {

                        treeWalk.addTree(commit.getTree())
                        treeWalk.setFilter(PathFilter.create(filenameToSearch))

                        if (treeWalk.next()) {
                            log.debug("File ${filename} found in branch ${branchName}")

                            return true
                        }
                    }
                }
            }
        }
        log.debug("File ${filename} not found in repository ${repoPath}")
        return false
    }

    static boolean isTag(File repo, String ref) {
        if (!ref) {
            return false
        }
        try (def git = Git.open(repo)) {
            git.tagList().call().any { it.name.endsWith("/" + ref) || it.name == ref }
        }
    }

    private PushCommand createPushCommand(String refSpec) {
        getGit()
                .push()
                .setRemote(getGitRepositoryUrl())
                .setRefSpecs(new RefSpec(refSpec))
                .setCredentialsProvider(getCredentialProvider())
    }

    private Git getGit() {
        if (gitMemoization != null) {
            return gitMemoization
        }

        return gitMemoization = Git.open(new File(absoluteLocalRepoTmpDir))
    }

    private CredentialsProvider getCredentialProvider() {
        def auth = this.gitProvider.getCredentials()
        def passwordAuthentication = new UsernamePasswordCredentialsProvider(auth.username, auth.password)
        return insecure ? new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication) : passwordAuthentication
    }

}