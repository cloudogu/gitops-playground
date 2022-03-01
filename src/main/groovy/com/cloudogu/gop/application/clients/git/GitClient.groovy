package com.cloudogu.gop.application.clients.git

import com.cloudogu.gop.application.utils.CommandExecutor
import com.cloudogu.gop.application.utils.FileSystemUtils
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

    GitClient(Map scmmConfig) {
        String scmmProtocol = scmmConfig.scmm["protocol"]
        String scmmHost = scmmConfig.scmm["host"]
        username = scmmConfig.scmm["username"]
        password = scmmConfig.scmm["password"]
        scmmUrl = scmmProtocol + "://" + scmmHost
        scmmUrlWithCredentials = scmmProtocol + "://" + username + ":" + password + "@" + scmmHost
        scmmInternal = scmmConfig.scmm["internal"]
    }

    void clone(String localGopSrcDir, String scmmRepoTarget, String absoluteLocalRepoTmpDir) {
        String repoUrl = scmmUrlWithCredentials + "/repo/" + scmmRepoTarget
        String absoluteSrcDirLocation = FileSystemUtils.getGopRoot() + "/" + localGopSrcDir

        gitInit(absoluteLocalRepoTmpDir)

        log.debug("Creating temporary git repo folder")
        FileSystemUtils.createDirectory(absoluteLocalRepoTmpDir)

        CommandExecutor.execute("git clone ${repoUrl} ${absoluteLocalRepoTmpDir}")
        FileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir)

        if (!scmmInternal) {
            log.debug("Configuring all yaml files to use the external scmm url")
            FileSystemUtils.getAllFilesFromDirectoryWithEnding(absoluteLocalRepoTmpDir, ".yaml").forEach(file -> {
                FileSystemUtils.replaceFileContent(file.absolutePath, "http://scmm-scm-manager.default.svc.cluster.local/scm", "$scmmUrl")
            })
        }
    }

    void commitAndPush(String target, String absoluteLocalRepoTmpDir) {
        git("checkout -b main --quiet")
        git("add .")
        String[] commitCommand = ["commit", "-m", "\"Initial commit\"", "--quiet"]
        git(commitCommand)
        git("push -u $scmmUrlWithCredentials/repo/$target HEAD:main --force")

        cleanup(absoluteLocalRepoTmpDir)

        setDefaultBranchForRepo(target)
    }

    private void gitInit(String absoluteLocalRepoTmpDir) {
        gitRepoCommand = "git --git-dir=$absoluteLocalRepoTmpDir/.git/ --work-tree=$absoluteLocalRepoTmpDir"
    }

    private void git(String command) {
        String gitCommand = gitRepoCommand + " " + command
        CommandExecutor.executeAsList(gitCommand)
    }

    private void git(String[] command) {
        String[] gitCommand = gitRepoCommand.split(" ") + command
        CommandExecutor.execute(gitCommand)
    }

    private void cleanup(String dir) {
        new File(dir).deleteDir()
    }

    private void setDefaultBranchForRepo(String scmmRepoTarget) {
        def defaultBranch = "main"
        def contentType = "application/vnd.scmm-gitConfig+json"
        def json = "{\"defaultBranch\":\"$defaultBranch\"}"

        OkHttpClient client = new OkHttpClient()
        RequestBody body = RequestBody.create(MediaType.parse(contentType), json)
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
