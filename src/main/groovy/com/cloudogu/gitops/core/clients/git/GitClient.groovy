package com.cloudogu.gitops.core.clients.git

import com.cloudogu.gitops.core.utils.CommandExecutor
import com.cloudogu.gitops.core.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import okhttp3.*

@Slf4j
class GitClient {

    private String scmmUrlWithCredentials
    private boolean scmmInternal
    private String gitRepoCommand
    private String scmmUrl
    private String username
    private String password
    private FileSystemUtils fileSystemUtils
    private CommandExecutor commandExecutor

    GitClient(Map config, FileSystemUtils fileSystemUtils = new FileSystemUtils(), CommandExecutor commandExecutor = new CommandExecutor()) {
        String scmmProtocol = config.scmm["protocol"]
        String scmmHost = config.scmm["host"]
        this.username = config.scmm["username"]
        this.password = config.scmm["password"]
        this.scmmUrl = scmmProtocol + "://" + scmmHost
        this.scmmUrlWithCredentials = scmmProtocol + "://" + username + ":" + password + "@" + scmmHost
        this.scmmInternal = config.scmm["internal"]
        this.fileSystemUtils = fileSystemUtils
        this.commandExecutor = commandExecutor
    }

    void clone(String localSrcDir, String scmmRepoTarget, String absoluteLocalRepoTmpDir) {

        String repoUrl = scmmUrlWithCredentials + "/repo/" + scmmRepoTarget
        String absoluteSrcDirLocation = fileSystemUtils.getRootDir() + "/" + localSrcDir

        gitRepoCommandInit(absoluteLocalRepoTmpDir)

        log.debug("Creating temporary git repo folder")
        fileSystemUtils.createDirectory(absoluteLocalRepoTmpDir)

        log.debug("Cloning $scmmRepoTarget repo")
        commandExecutor.execute("git clone ${repoUrl} ${absoluteLocalRepoTmpDir}")
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir)

        if (!scmmInternal) {
            log.debug("Configuring all yaml files to use the external scmm url")
            fileSystemUtils.getAllFilesFromDirectoryWithEnding(absoluteLocalRepoTmpDir, ".yaml").forEach(file -> {
                fileSystemUtils.replaceFileContent(file.absolutePath, "http://scmm-scm-manager.default.svc.cluster.local/scm", "$scmmUrl")
            })
        }
    }

    void commitAndPush(String scmmRepoTarget, String absoluteLocalRepoTmpDir) {
        log.debug("Pushing configured $scmmRepoTarget repo")
        git("checkout -b main --quiet")
        git("add .")
        String[] commitCommand = ["commit", "-m", "\"Initial commit\"", "--quiet"]
        git(commitCommand)
        git("push -u $scmmUrlWithCredentials/repo/$scmmRepoTarget HEAD:main --force")

        cleanup(absoluteLocalRepoTmpDir)

        setDefaultBranchForRepo(scmmRepoTarget)
    }

    private void gitRepoCommandInit(String absoluteLocalRepoTmpDir) {
        gitRepoCommand = "git --git-dir=$absoluteLocalRepoTmpDir/.git/ --work-tree=$absoluteLocalRepoTmpDir"
    }

    private void git(String command) {
        String gitCommand = gitRepoCommand + " " + command
        commandExecutor.executeAsList(gitCommand)
    }

    private void git(String[] command) {
        String[] gitCommand = gitRepoCommand.split(" ") + command
        commandExecutor.execute(gitCommand)
    }

    private void cleanup(String dir) {
        new File(dir).deleteDir()
    }

    private void setDefaultBranchForRepo(String scmmRepoTarget) {
        def defaultBranch = "main"
        def contentType = "application/vnd.scmm-gitConfig+json"
        def json = "{\"defaultBranch\":\"$defaultBranch\"}"

        OkHttpClient client = new OkHttpClient()
        RequestBody body = RequestBody.create(json, MediaType.parse(contentType))
        String postUrl = scmmUrl + "/api/v2/config/git/" + scmmRepoTarget
        Request request = new Request.Builder()
                .header("Authorization", Credentials.basic(username, password))
                .url(postUrl)
                .put(body)
                .build()
        try (Response response = client.newCall(request).execute()) {
            log.debug("Setting default branch to $defaultBranch for repository $scmmRepoTarget yields -> " + response.code() + ": " + response.message())
        }
    }
}
