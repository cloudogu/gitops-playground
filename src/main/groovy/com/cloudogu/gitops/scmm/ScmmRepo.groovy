package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import com.cloudogu.gitops.scmm.jgit.InsecureCredentialProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import retrofit2.Response

@Slf4j
class ScmmRepo {

    static final String NAMESPACE_3RD_PARTY_DEPENDENCIES = '3rd-party-dependencies'

    private String scmmRepoTarget
    private String username
    private String password
    private String scmmUrl
    private String absoluteLocalRepoTmpDir
    protected FileSystemUtils fileSystemUtils
    private boolean insecure
    private Git gitMemoization = null
    private String gitName
    private String gitEmail
    private String rootPath
    private String scmProvider
    private Config config

    ScmmRepo(Config config, String scmmRepoTarget, FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()
        this.username = config.scmm.username
        this.password = config.scmm.password
        this.scmmUrl = "${config.scmm.protocol}://${config.scmm.host}"
        this.scmmRepoTarget = scmmRepoTarget.startsWith(NAMESPACE_3RD_PARTY_DEPENDENCIES) ? scmmRepoTarget :
                "${config.application.namePrefix}${scmmRepoTarget}"
        this.absoluteLocalRepoTmpDir = tmpDir.absolutePath
        this.fileSystemUtils = fileSystemUtils
        this.insecure = config.application.insecure
        this.gitName = config.application.gitName
        this.gitEmail = config.application.gitEmail
        this.scmProvider = config.scmm.provider
        this.rootPath = config.scmm.rootPath
        this.config = config
    }

    String getAbsoluteLocalRepoTmpDir() {
        return absoluteLocalRepoTmpDir
    }

    String getScmmRepoTarget() {
        return scmmRepoTarget
    }

    static String createScmmUrl(Config config) {
        return "${config.scmm.protocol}://${config.scmm.host}"
    }

    static String createSCMBaseUrl(Config config) {
        switch (config.scmm.provider) {
            case "scm-manager":
                if(config.scmm.internal){
                    return "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm/${config.scmm.rootPath}/${config.application.namePrefix}"
                }
                return createScmmUrl(config) + "/${config.scmm.rootPath}/${config.application.namePrefix}"
            case "gitlab":
                return createScmmUrl(config) + "/${config.application.namePrefix}${config.scmm.rootPath}"
            default:
                log.error("No SCM Provider found. Failing to create RepoBaseUrls!")
                return ""
        }
    }


    void cloneRepo() {
        log.debug("Cloning $scmmRepoTarget repo")
        gitClone()
        checkoutOrCreateBranch('main')
    }

    void create(String description, ScmmApiClient scmmApiClient) {
        def namespace = scmmRepoTarget.split('/', 2)[0]
        def repoName = scmmRepoTarget.split('/', 2)[1]

        def repositoryApi = scmmApiClient.repositoryApi()
        def repo = new Repository(namespace, repoName, description)
        def createResponse = repositoryApi.create(repo, true).execute()
        handleResponse(createResponse, repo)

        def permission = new Permission(config.scmm.gitOpsUsername as String, Permission.Role.WRITE)
        def permissionResponse = repositoryApi.createPermission(namespace, repoName, permission).execute()
        handleResponse(permissionResponse, permission, "for repo $namespace/$repoName")
    }

    private static void handleResponse(Response<Void> response, Object body, String additionalMessage = '') {
        if (response.code() == 409) {
            // Here, we could consider sending another request for changing the existing object to become proper idempotent
            log.debug("${body.class.simpleName} already exists ${additionalMessage}, ignoring: ${body}")
        } else if (response.code() != 201) {
            throw new RuntimeException("Could not create ${body.class.simpleName} ${additionalMessage}.\n${body}\n" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
    }

    void writeFile(String path, String content) {
        def file = new File("$absoluteLocalRepoTmpDir/$path")
        fileSystemUtils.createDirectory(file.parent)
        file.createNewFile()
        file.text = content
    }

    void copyDirectoryContents(String srcDir) {
        log.debug("Initializing repo $scmmRepoTarget with content of folder $srcDir")
        String absoluteSrcDirLocation = srcDir
        if (!new File(absoluteSrcDirLocation).isAbsolute()) {
            absoluteSrcDirLocation = fileSystemUtils.getRootDir() + "/" + srcDir
        }
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir)
    }

    void replaceTemplates(Map parameters) {
        new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters)
    }

    void commitAndPush(String commitMessage, String tag = null) {
        log.debug("Checking out main, adding files for repo: ${scmmRepoTarget}")
        getGit()
                .add()
                .addFilepattern(".")
                .call()

        if (getGit().status().call().hasUncommittedChanges()) {
            getGit()
                    .commit()
                    .setSign(false)
                    .setMessage(commitMessage)
                    .setAuthor(gitName, gitEmail)
                    .setCommitter(gitName, gitEmail)
                    .call()

            def pushCommand = getGit()
                    .push()
                    .setRemote(getGitRepositoryUrl())
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
                    .setCredentialsProvider(getCredentialProvider())

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

            log.debug("Pushing repo: ${scmmRepoTarget}")
            pushCommand.call()
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
                .setCredentialsProvider(getCredentialProvider())
                .call()
    }

    private CredentialsProvider getCredentialProvider() {
        if (scmProvider == "gitlab") {
            username = "oauth2"
        }
        def passwordAuthentication = new UsernamePasswordCredentialsProvider(username, password)

        if (!insecure) {
            return passwordAuthentication
        }

        return new ChainingCredentialsProvider(new InsecureCredentialProvider(), passwordAuthentication)
    }

    private Git getGit() {
        if (gitMemoization != null) {
            return gitMemoization
        }

        return gitMemoization = Git.open(new File(absoluteLocalRepoTmpDir))
    }

    protected String getGitRepositoryUrl() {
        return "${scmmUrl}/${rootPath}/${scmmRepoTarget}"
    }
}