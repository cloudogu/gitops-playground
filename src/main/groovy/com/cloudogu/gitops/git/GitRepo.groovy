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
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

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

        this.repoTarget = repoTarget.startsWith(NAMESPACE_3RD_PARTY_DEPENDENCIES) ? repoTarget :
                "${config.application.namePrefix}${repoTarget}"

        this.insecure = config.application.insecure
        this.gitName = config.application.gitName
        this.gitEmail = config.application.gitEmail
    }

    String getRepoTarget() {
        return repoTarget
    }
    
    boolean createRepositoryAndSetPermission(String repoTarget, String description, boolean initialize = true) {
        def isNewRepo = this.gitProvider.createRepository(repoTarget, description, initialize)
        if (isNewRepo && gitProvider.getGitOpsUsername()) {
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

    private PushCommand createPushCommand(String refSpec) {
        getGit()
                .push()
                .setRemote(getGitRepositoryUrl())
                .setRefSpecs(new RefSpec(refSpec))
                .setCredentialsProvider(getCredentialProvider())
    }

    String getGitRepositoryUrl() {
        return this.gitProvider.repoUrl(repoTarget, RepoUrlScope.CLIENT)
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