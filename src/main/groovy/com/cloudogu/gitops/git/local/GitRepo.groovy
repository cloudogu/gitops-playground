package com.cloudogu.gitops.git.local

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.git.GitProvider
import com.cloudogu.gitops.git.local.jgit.helpers.InsecureCredentialProvider
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

    GitProvider gitProvider

    Config config
    String absoluteLocalRepoTmpDir
    CredentialsProvider credentialsProvider
    String scmRepoTarget

    private Git gitMemoization = null
    FileSystemUtils fileSystemUtils

    GitRepo(Config config, GitProvider scm, String scmRepoTarget, FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.config = config
        this.gitProvider = scm
        this.scmRepoTarget = scmRepoTarget
        this.fileSystemUtils = fileSystemUtils

        setAbsoluteLocalRepoTmpDir()
        setCredentialProvider(this.gitProvider.getCredentials())
    }

    void writeFile(String path, String content) {
        def file = new File("$absoluteLocalRepoTmpDir/$path")
        this.fileSystemUtils.createDirectory(file.parent)
        file.createNewFile()
        file.text = content
    }

    void copyDirectoryContents(String srcDir, FileFilter fileFilter = null) {
        if (!srcDir) {
            println "Source directory is not defined. Nothing to copy?"
            return
        }

        log.debug("Initializing repo $scmRepoTarget with content of folder $srcDir")
        String absoluteSrcDirLocation = srcDir
        if (!new File(absoluteSrcDirLocation).isAbsolute()) {
            absoluteSrcDirLocation = fileSystemUtils.getRootDir() + "/" + srcDir
        }
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir, fileFilter)
    }

    void replaceTemplates(Map parameters) {
        new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters)
    }

/*
GIT Functions
 */

    private Git getGit() {
        if (gitMemoization != null) {
            return gitMemoization
        }

        return gitMemoization = Git.open(new File(absoluteLocalRepoTmpDir))
    }

    void cloneRepo() {
        log.debug("Cloning $scmRepoTarget repo")
        gitClone()
        checkoutOrCreateBranch('main')
    }

    protected Git gitClone() {
        Git.cloneRepository()
                .setURI(this.gitProvider.getUrl())
                .setDirectory(new File(absoluteLocalRepoTmpDir))
                .setNoCheckout(true)
                .setCredentialsProvider(this.getCredentialsProvider())
                .call()
    }

    def commitAndPush(String commitMessage, String tag = null, String refSpec = 'HEAD:refs/heads/main') {
        log.debug("Adding files to repo: ${scmRepoTarget}")
        getGit()
                .add()
                .addFilepattern(".")
                .call()

        if (getGit().status().call().hasUncommittedChanges()) {
            log.debug("Committing repo: ${scmRepoTarget}")
            getGit()
                    .commit()
                    .setSign(false)
                    .setMessage(commitMessage)
                    .setAuthor(config.application.gitName, config.application.gitEmail)
                    .setCommitter(config.application.gitName, config.application.gitEmail)
                    .call()

            def pushCommand = createPushCommand(refSpec)

            if (tag) {
                log.debug("Setting tag '${tag}' on repo: ${scmRepoTarget}")
                // Delete existing tags first to get idempotence
                getGit().tagDelete().setTags(tag).call()
                getGit()
                        .tag()
                        .setName(tag)
                        .call()

                pushCommand.setPushTags()
            }

            log.debug("Pushing repo: ${scmRepoTarget}, refSpec: ${refSpec}")
            pushCommand.call()
        } else {
            log.debug("No changes after add, nothing to commit or push on repo: ${scmRepoTarget}")
        }
    }

    /**
     * Push all refs, i.e. all tags and branches
     */
    def pushAll(boolean force = false) {
        createPushCommand('refs/*:refs/*').setForce(force).call()
    }

    def pushRef(String ref, String targetRef, boolean force = false) {
        createPushCommand("${ref}:${targetRef}").setForce(force).call()
    }

    def pushRef(String ref, boolean force = false) {
        pushRef(ref, ref, force)
    }

    private PushCommand createPushCommand(String refSpec) {
        getGit()
                .push()
                .setRemote(this.gitProvider.getUrl())
                .setRefSpecs(new RefSpec(refSpec))
                .setCredentialsProvider(this.getCredentialsProvider())
    }

    void checkoutOrCreateBranch(String branch) {
        log.debug("Checking out $branch for repo $scmRepoTarget")
        getGit()
                .checkout()
                .setCreateBranch(!branchExists(branch))
                .setName(branch)
                .call()
    }

    private boolean branchExists(String branch) {
        return getGit()
                .branchList()
                .call()
                .collect { it.name.replace("refs/heads/", "") }
                .contains(branch)
    }

    String setAbsoluteLocalRepoTmpDir() {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
    }


    private CredentialsProvider setCredentialProvider(Credentials credentials) {
        def passwordAuthentication = new UsernamePasswordCredentialsProvider(credentials.username, credentials.password)

        if (!config.application.insecure) {
            return passwordAuthentication
        }
        return new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication)
    }

}