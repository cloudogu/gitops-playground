package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.util.logging.Slf4j

@Slf4j
class ScmmRepo {

    private String gitRepoCommand
    private String scmmRepoTarget
    private String username
    private String password
    private String scmmUrlWithCredentials
    private String scmmUrl
    private String absoluteLocalRepoTmpDir
    protected CommandExecutor commandExecutor
    protected FileSystemUtils fileSystemUtils

    ScmmRepo(Map config, String scmmRepoTarget, CommandExecutor commandExecutor, FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()

        this.username =  config.scmm["internal"] ? config.application["username"] : config.scmm["username"]
        this.password = config.scmm["internal"] ? config.application["password"] : config.scmm["password"]
        this.scmmUrl = createScmmUrl(config)
        this.scmmUrlWithCredentials = "${config.scmm["protocol"]}://${username}:${password}@${config.scmm["host"]}"
        this.scmmRepoTarget = scmmRepoTarget
        this.scmmRepoTarget = scmmRepoTarget
        this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        gitRepoCommandInit(absoluteLocalRepoTmpDir)
    }

    String getAbsoluteLocalRepoTmpDir() {
        return absoluteLocalRepoTmpDir
    }

    static String createScmmUrl(Map config) {
        return "${config.scmm["protocol"]}://${config.scmm["host"]}"
    }

    void cloneRepo() {
        String repoUrl = scmmUrlWithCredentials + "/repo/" + scmmRepoTarget

        log.debug("Cloning $scmmRepoTarget repo")
        commandExecutor.execute("git clone ${repoUrl} ${absoluteLocalRepoTmpDir}")
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

    void commitAndPush(String commitMessage) {
        log.debug("Checking out main, adding files for repo: ${scmmRepoTarget}")
        git("add .")
        // "git commit" fails if no changes
        if (areChangesStagedForCommit()) {
            log.debug("Pushing repo: ${scmmRepoTarget}")
            // Passing this as a single string leads to failing command
            git(["commit", "-m", commitMessage] as String[])
            git("push -u $scmmUrlWithCredentials/repo/$scmmRepoTarget HEAD:refs/heads/main --force")
        }
    }

    boolean areChangesStagedForCommit() {
        // See https://stackoverflow.com/a/5139346/
        boolean changesStageForCommit = !git('status --porcelain').isEmpty()
        log.debug("Stages changed for commit: ${changesStageForCommit}")
        return changesStageForCommit
    }
    
    private void gitRepoCommandInit(String absoluteLocalRepoTmpDir) {
        gitRepoCommand = "git --git-dir=$absoluteLocalRepoTmpDir/.git/ --work-tree=$absoluteLocalRepoTmpDir"
    }

    String git(String command) {
        String gitCommand = gitRepoCommand + " " + command
        commandExecutor.execute(gitCommand).stdOut
    }

    String git(String[] command) {
        String[] gitCommand = gitRepoCommand.split(" ") + command
        commandExecutor.execute(gitCommand).stdOut
    }

    void checkoutOrCreateBranch(String branch) {
        if (branchExists(branch)) {
            git("checkout ${branch}")
        } else {
            git("checkout -b ${branch}")
        }
    }

    private boolean branchExists(String branch) {
        git('branch').split(" ").contains(branch)
    }

}
