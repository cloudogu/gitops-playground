package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitabstraction.serverOps.GitProvider
import com.cloudogu.gitops.gitabstraction.worktreeOps.BaseGitRepo
import com.cloudogu.gitops.gitabstraction.worktreeOps.GitRepo
import com.cloudogu.gitops.scmm.jgit.InsecureCredentialProvider
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
class ScmmRepo extends BaseGitRepo implements GitRepo {

    static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = '3rd-party-dependencies'

    private final Config config
    private final GitProvider gitProvider
    private final FileSystemUtils fileSystemUtils

    private final String repoTarget        // before scmmRepoTarget (neutral)
    private final boolean isCentralRepo
    private final boolean insecure
    private final String gitName
    private final String gitEmail
    private final String usernameForAuth   // inclusive central/normal switch
    private final String passwordForAuth

    private Git gitMemoization
    private final String absoluteLocalRepoTmpDir

    ScmmRepo(Config config, GitProvider gitProvider, String scmmRepoTarget, FileSystemUtils fileSystemUtils, Boolean isCentralRepo = false) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
        this.config = config
        this.gitProvider = gitProvider
        this.fileSystemUtils = fileSystemUtils
        this.isCentralRepo = isCentralRepo

        this.repoTarget = scmmRepoTarget.startsWith(NAMESPACE_3RD_PARTY_DEPENDENCIES) ? scmmRepoTarget :
                "${config.application.namePrefix}${scmmRepoTarget}"

        this.insecure = config.application.insecure
        this.gitName = config.application.gitName
        this.gitEmail = config.application.gitEmail

        // Auth from config (central vs. normal)
        this.usernameForAuth = isCentralRepo ? (config.multiTenant.username as String) : (config.scmm.username as String)
        this.passwordForAuth = isCentralRepo ? (config.multiTenant.password as String) : (config.scmm.password as String)
    }

    // ---------- GitRepo ----------
    @Override
    String getRepoTarget() {
        return repoTarget
    }

    @Override
    String getAbsoluteLocalRepoTmpDir() {
        return absoluteLocalRepoTmpDir
    }

    @Override
    void cloneRepo() {
        log.debug("Cloning ${repoTarget}")
        Git.cloneRepository()
                .setURI(gitProvider.computePushUrl(repoTarget))     // URL from provider
                .setDirectory(new File(absoluteLocalRepoTmpDir))
                .setCredentialsProvider(getCredentialProvider())
                .call()
    }

    @Override
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

    @Override
    void commitAndPush(String commitMessage) {
        commitAndPush(commitMessage, null, 'HEAD:refs/heads/main')
    }
    /**
     * Push all refs, i.e. all tags and branches
     */
    @Override
    void pushAll(boolean force) {
        createPushCommand('refs/*:refs/*').setForce(force).call()
    }

    @Override
    void pushRef(String ref, String targetRef, boolean force) {
        createPushCommand("${ref}:${targetRef}").setForce(force).call()
    }

    @Override
    /**
     * Delete all files in this repository
     */
    void clearRepo() {
        fileSystemUtils.deleteFilesExcept(new File(absoluteLocalRepoTmpDir), ".git")
    }



    // ---------- extras (like before) ----------
    void writeFile(String path, String content) {
        def file = new File("$absoluteLocalRepoTmpDir/$path")
        fileSystemUtils.createDirectory(file.parent)
        file.createNewFile()
        file.text = content
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

    void replaceTemplates(Map parameters) {
        new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters)
    }

    // ---------- intern ----------
    private PushCommand createPushCommand(String refSpec) {
        getGit()
                .push()
                .setRemote(gitProvider.computePushUrl(repoTarget))
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
        def auth = gitProvider.pushAuth(isCentralRepo)
        def passwordAuthentication = new UsernamePasswordCredentialsProvider(auth.username, auth.password)
        return insecure ? new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication) : passwordAuthentication
    }

}