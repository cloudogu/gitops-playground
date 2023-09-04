package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

@Slf4j
class ScmmRepo {

    private String scmmRepoTarget
    private String username
    private String password
    private String scmmUrl
    private String absoluteLocalRepoTmpDir
    protected FileSystemUtils fileSystemUtils

    ScmmRepo(Map config, String scmmRepoTarget, FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()

        this.username =  config.scmm["internal"] ? config.application["username"] : config.scmm["username"]
        this.password = config.scmm["internal"] ? config.application["password"] : config.scmm["password"]
        this.scmmUrl = "${config.scmm["protocol"]}://${config.scmm["host"]}"
        this.scmmRepoTarget =  "${config.application['namePrefix']}${scmmRepoTarget}"
        this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
        this.fileSystemUtils = fileSystemUtils
    }

    String getAbsoluteLocalRepoTmpDir() {
        return absoluteLocalRepoTmpDir
    }

    String getScmmRepoTarget() {
        return scmmRepoTarget
    }

    static String createScmmUrl(Map config) {
        return "${config.scmm["protocol"]}://${config.scmm["host"]}"
    }

    void cloneRepo() {
        log.debug("Cloning $scmmRepoTarget repo")
        gitClone()
        checkoutOrCreateBranch('main')
    }

    void writeFile(String path, String content) {
        def file = new File("$absoluteLocalRepoTmpDir/$path")
        fileSystemUtils.createDirectory(file.parent)
        file.createNewFile()
        file.text = content
    }

    void copyDirectoryContents(String srcDir) {
        String absoluteSrcDirLocation = srcDir
        if (!new File(absoluteSrcDirLocation).isAbsolute()) {
            absoluteSrcDirLocation = fileSystemUtils.getRootDir() + "/" + srcDir
        }
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir)
    }

    void replaceTemplates(Pattern filepathMatches, Map parameters) {
        def engine = new TemplatingEngine()
        Files.walk(Path.of(absoluteLocalRepoTmpDir))
                .filter { filepathMatches.matcher(it.toString()).find() }
                .each { Path it -> engine.replaceTemplate(it.toFile(), parameters) }
    }

    void commitAndPush(String commitMessage) {
        log.debug("Checking out main, adding files for repo: ${scmmRepoTarget}")
        getGit()
                .add()
                .addFilepattern(".")
                .call()

        if (getGit().status().call().hasUncommittedChanges()) {
            log.debug("Pushing repo: ${scmmRepoTarget}")
            getGit()
                    .commit()
                    .setSign(false)
                    .setMessage(commitMessage)
                    .call()
            getGit()
                .push()
                .setForce(true)
                .setRemote(getGitRepositoryUrl())
                .setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                .call()
        }
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

    protected Git gitClone() {
        Git.cloneRepository()
                .setURI(getGitRepositoryUrl())
                .setDirectory(new File(absoluteLocalRepoTmpDir))
                .setNoCheckout(true)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                .call()
    }

    private Git getGit() {
        return Git.open(new File(absoluteLocalRepoTmpDir))
    }

    protected String getGitRepositoryUrl() {
        return scmmUrl + "/repo/" + scmmRepoTarget
    }
}
