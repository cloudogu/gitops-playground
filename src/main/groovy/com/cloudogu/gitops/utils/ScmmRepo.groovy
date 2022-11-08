package com.cloudogu.gitops.utils


import groovy.util.logging.Slf4j
import okhttp3.*

@Slf4j
class ScmmRepo {

    private String gitRepoCommand
    private String scmmRepoTarget
    private String username
    private String password
    private String scmmUrl
    private String scmmUrlWithCredentials
    private String localSrcDir
    private String absoluteLocalRepoTmpDir
    protected FileSystemUtils fileSystemUtils = new FileSystemUtils()
    protected CommandExecutor commandExecutor = new CommandExecutor()

    ScmmRepo(Map config, String localSrcDir, String scmmRepoTarget, String absoluteLocalRepoTmpDir) {
        this.username = config.scmm["username"]
        this.password = config.scmm["password"]
        this.scmmUrl = createScmmUrl(config)
        this.scmmUrlWithCredentials = "${config.scmm["protocol"]}://${username}:${password}@${config.scmm["host"]}"
        this.scmmRepoTarget = scmmRepoTarget
        this.localSrcDir = localSrcDir
        this.scmmRepoTarget = scmmRepoTarget
        this.absoluteLocalRepoTmpDir = absoluteLocalRepoTmpDir
        gitRepoCommandInit(absoluteLocalRepoTmpDir)
    }

    static String createScmmUrl(Map config) {
        return "${config.scmm["protocol"]}://${config.scmm["host"]}"
    }

    void cloneRepo() {
        String repoUrl = scmmUrlWithCredentials + "/repo/" + scmmRepoTarget
        String absoluteSrcDirLocation = fileSystemUtils.getRootDir() + "/" + localSrcDir

        log.debug("Cloning $scmmRepoTarget repo")
        commandExecutor.execute("git clone ${repoUrl} ${absoluteLocalRepoTmpDir}")
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir)
    }

    void commitAndPush() {
        log.debug("Checking out main, adding files for repo: ${scmmRepoTarget}")
        checkoutOrCreateBranch('main')
        git("add .")
        // "git commit" fails if no changes
        if (areChangesStagedForCommit()) {
            log.debug("Pushing repo: ${scmmRepoTarget}")
            // Passing this as a single string leads to failing command
            git(["commit", "-m", "\"Initial commit\""] as String[])
            git("push -u $scmmUrlWithCredentials/repo/$scmmRepoTarget HEAD:main --force")
        }

        // TODO do we still need this?
        setDefaultBranchForRepo(scmmRepoTarget)
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

    private void setDefaultBranchForRepo(String scmmRepoTarget) {
        def defaultBranch = "main"
        def contentType = "application/vnd.scmm-gitConfig+json"
        def json = "{\"defaultBranch\":\"$defaultBranch\"}"

        RequestBody body = RequestBody.create(json, MediaType.parse(contentType))
        String postUrl = scmmUrl + "/api/v2/config/git/" + scmmRepoTarget
        Request request = createRequest()
                .header("Authorization", Credentials.basic(username, password))
                .url(postUrl)
                .put(body)
                .build()
        try (Response response = newHttpClient().newCall(request).execute()) {
            log.debug("Setting default branch to $defaultBranch for repository $scmmRepoTarget yields -> " + response.code() + ": " + response.message())
        }
    }

    /**
     * Overridable for testing
     */
    protected Request.Builder createRequest() {
        new Request.Builder()
    }

    protected OkHttpClient newHttpClient() {
        new OkHttpClient()
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
