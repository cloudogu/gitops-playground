package com.cloudogu.gop.tools.git

import com.cloudogu.gop.utils.CommandExecutor
import com.cloudogu.gop.utils.FileSystemUtils
import groovy.util.logging.Slf4j
import okhttp3.*

@Slf4j
class Git {

    private String scmmUrlWithCredentials
    private boolean scmmInternal
    private String gitRepoCommand
    private String scmmUrl
    private String username
    private String password

    Git(Map scmmConfig) {
        scmmUrl = scmmConfig.protocol as String + "://" + scmmConfig.host as String
        scmmUrlWithCredentials = scmmConfig.protocol as String + "://" + scmmConfig.username as String + ":" + scmmConfig.password as String + "@" + scmmConfig.host as String
        scmmInternal = scmmConfig.internal
        username = scmmConfig.username
        password = scmmConfig.password
    }

    void initRepoWithSource(String source, String target, Closure evalInRepo = null) {

        String repoUrl = scmmUrlWithCredentials + "/repo/" + target
        String absoluteTmpDirLocation = FileSystemUtils.getGopRoot() + "/" + "repoTmpDir"
        String absoluteSrcDirLocation = FileSystemUtils.getGopRoot() + "/" + source

        gitInit(absoluteTmpDirLocation)

        CommandExecutor.execute("git clone ${repoUrl} ${absoluteTmpDirLocation}")
        FileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteTmpDirLocation)

        if (!scmmInternal) {
            FileSystemUtils.getAllFilesFromDirectoryWithEnding(absoluteTmpDirLocation, ".yaml").forEach(file -> {
                FileSystemUtils.replaceFileContent(file.absolutePath, "http://scmm-scm-manager.default.svc.cluster.local/scm", "$scmmUrl")
            })
        }

        if (evalInRepo != null) {
            evalInRepo.curry(absoluteTmpDirLocation).run()
        }

        git("checkout main --quiet")
        git("add .")
        git("commit -am 'Init' --quiet")
        git("push --force")

        cleanup(absoluteTmpDirLocation)

        setDefaultBranchForRepo(target)
    }

    private void gitInit(String absoluteTmpDirLocation) {
        gitRepoCommand = "git --git-dir=$absoluteTmpDirLocation/.git/ --work-tree=$absoluteTmpDirLocation"
    }

    private void git(String command) {
        String gitCommand = gitRepoCommand + " " + command
        CommandExecutor.execute(gitCommand)
    }

    private void cleanup(String dir) {
        new File(dir).deleteDir()
    }

    private void setDefaultBranchForRepo(String target) {
        def defaultBranch = "main"
        def contentType = "application/vnd.scmm-gitConfig+json"
        def json = "{\"defaultBranch\":\"$defaultBranch\"}"

        OkHttpClient client = new OkHttpClient()
        RequestBody body = RequestBody.create(MediaType.parse(contentType), json)
        String postUrl = scmmUrl + "/api/v2/config/git/" + target
        Request request = new Request.Builder()
                .header("Authorization", Credentials.basic(username, password))
                .url(postUrl)
                .put(body)
                .build()
        try (Response response = client.newCall(request).execute()) {
            log.debug("Setting default branch to $defaultBranch for repository $target yields -> " + response.code() + ": " + response.message())
        }
    }
}
