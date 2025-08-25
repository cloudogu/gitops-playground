package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scmm.jgit.InsecureCredentialProvider
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

@Slf4j
class ScmRepo {

    private ISCM scm

    Config config
    private String absoluteLocalRepoTmpDir
    CredentialsProvider credentialsProvider
    String scmmRepoTarget

    private Git gitMemoization = null

    ScmRepo(Config config, ISCM scm, String scmmRepoTarget) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.config = config
        this.scm = scm
        this.scmmRepoTarget=scmmRepoTarget


        setAbsoluteLocalRepoTmpDir()
        setCredentialProvider(this.scm.getCredentials())
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
        log.debug("Cloning $scmmRepoTarget repo")
        gitClone()
        checkoutOrCreateBranch('main')
    }

    protected Git gitClone() {
        Git.cloneRepository()
                .setURI(this.scm.getUrl())
                .setDirectory(new File(absoluteLocalRepoTmpDir))
                .setNoCheckout(true)
                .setCredentialsProvider(this.getCredentialsProvider())
                .call()
    }

    def commitAndPush(String commitMessage, String tag = null, String refSpec = 'HEAD:refs/heads/main') {
        log.debug("Adding files to repo: ${scmmRepoTarget}")
        getGit()
                .add()
                .addFilepattern(".")
                .call()

        if (getGit().status().call().hasUncommittedChanges()) {
            log.debug("Committing repo: ${scmmRepoTarget}")
            getGit()
                    .commit()
                    .setSign(false)
                    .setMessage(commitMessage)
                    .setAuthor(config.application.gitName, config.application.gitEmail)
                    .setCommitter(config.application.gitName, config.application.gitEmail)
                    .call()

            def pushCommand = createPushCommand(refSpec)

            if (tag) {
                log.debug("Setting tag '${tag}' on repo: ${scmmRepoTarget}")
                // Delete existing tags first to get idempotence
                getGit().tagDelete().setTags(tag).call()
                getGit()
                        .tag()
                        .setName(tag)
                        .call()

                pushCommand.setPushTags()
            }

            log.debug("Pushing repo: ${scmmRepoTarget}, refSpec: ${refSpec}")
            pushCommand.call()
        } else {
            log.debug("No changes after add, nothing to commit or push on repo: ${scmmRepoTarget}")
        }
    }

    private PushCommand createPushCommand(String refSpec) {
        getGit()
                .push()
                .setRemote(this.scm.getUrl())
                .setRefSpecs(new RefSpec(refSpec))
                .setCredentialsProvider(this.getCredentialsProvider())
    }

    void checkoutOrCreateBranch(String branch) {
        log.debug("Checking out $branch for repo $scmmRepoTarget")
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