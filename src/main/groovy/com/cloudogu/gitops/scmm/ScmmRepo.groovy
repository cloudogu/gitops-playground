package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.scm.ISCM
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
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
    private Config.ScmProviderType scmProvider
    private Config config

    private ISCM scm
    Boolean isCentralRepo

    ScmmRepo(Config config, ISCM scm,String scmmRepoTarget, FileSystemUtils fileSystemUtils) {
        def tmpDir = File.createTempDir()
        tmpDir.deleteOnExit()

        this.scm.credentials

        this.isCentralRepo = isCentralRepo
        this.username = !this.isCentralRepo ? config.scmm.username : config.multiTenant.username
        this.password = !this.isCentralRepo ? config.scmm.password : config.multiTenant.password

        //switching from normal scm path to the central path
        this.scmmUrl = "${config.scmm.protocol}://${config.scmm.host}"
        if(this.isCentralRepo) {
            boolean useInternal = config.multiTenant.internal
            String internalUrl = "http://scmm.${config.multiTenant.centralSCMamespace}.svc.cluster.local/scm"
            String externalUrl = config.multiTenant.centralScmUrl.toString()

            this.scmmUrl = useInternal ? internalUrl : externalUrl
        }

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
            case Config.ScmProviderType.SCM_MANAGER:
                if(config.scmm.internal){
                    return "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm/${config.scmm.rootPath}/${config.application.namePrefix}"
                }
                return createScmmUrl(config) + "/${config.scmm.rootPath}/${config.application.namePrefix}"
            case Config.ScmProviderType.GITLAB :
                return createScmmUrl(config) + "/${config.application.namePrefix}${config.scmm.rootPath}"
            default:
                log.error("No SCMHandler Provider found. Failing to create RepoBaseUrls!")
                return ""
        }
    }


    void cloneRepo() {
        log.debug("Cloning $scmmRepoTarget repo")
        Git.cloneRepository()
                .setURI(getGitRepositoryUrl())
                .setDirectory(new File(absoluteLocalRepoTmpDir))
                .setCredentialsProvider(getCredentialProvider())
                .call()
    }

    /**
     * @return true if created, false if already exists. Throw exception on all other errors
     */
    boolean create(String description, ScmmApiClient scmmApiClient, boolean initialize = true) {
        def namespace = scmmRepoTarget.split('/', 2)[0]
        def repoName = scmmRepoTarget.split('/', 2)[1]

        def repositoryApi = scmmApiClient.repositoryApi()
        def repo = new Repository(namespace, repoName, description)
        log.debug("Creating repo: ${namespace}/${repoName}")
        def createResponse = repositoryApi.create(repo, initialize).execute()
        handleResponse(createResponse, repo)

        def permission = new Permission(config.scmm.gitOpsUsername as String, Permission.Role.WRITE)
        def permissionResponse = repositoryApi.createPermission(namespace, repoName, permission).execute()
        return handleResponse(permissionResponse, permission, "for repo $namespace/$repoName")
    }

    private static boolean handleResponse(Response<Void> response, Object body, String additionalMessage = '') {
        if (response.code() == 409) {
            // Here, we could consider sending another request for changing the existing object to become proper idempotent
            log.debug("${body.class.simpleName} already exists ${additionalMessage}, ignoring: ${body}")
            return false // because repo exists
        } else if (response.code() != 201) {
            throw new RuntimeException("Could not create ${body.class.simpleName} ${additionalMessage}.\n${body}\n" +
                    "HTTP Details: ${response.code()} ${response.message()}: ${response.errorBody().string()}")
        }
        return true// because its created
    }

    void writeFile(String path, String content) {
        def file = new File("$absoluteLocalRepoTmpDir/$path")
        fileSystemUtils.createDirectory(file.parent)
        file.createNewFile()
        file.text = content
    }

    void copyDirectoryContents(String srcDir, FileFilter fileFilter = null) {
        if (!srcDir) {
            println "Source directory is not defined. Nothing to copy?"
            return
        }

        log.debug("Initializing repo $scmmRepoTarget with content of folder $srcDir")
        String absoluteSrcDirLocation = srcDir
        if (!new File(absoluteSrcDirLocation).isAbsolute()) {
            absoluteSrcDirLocation = fileSystemUtils.getRootDir() + "/" + srcDir
        }
        fileSystemUtils.copyDirectory(absoluteSrcDirLocation, absoluteLocalRepoTmpDir, fileFilter)
    }

    void replaceTemplates(Map parameters) {
        new TemplatingEngine().replaceTemplates(new File(absoluteLocalRepoTmpDir), parameters)
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
                    .setAuthor(gitName, gitEmail)
                    .setCommitter(gitName, gitEmail)
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
                .setRemote(getGitRepositoryUrl())
                .setRefSpecs(new RefSpec(refSpec))
                .setCredentialsProvider(getCredentialProvider())
    }

    private CredentialsProvider getCredentialProvider() {
        if (scmProvider == Config.ScmProviderType.GITLAB) {
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

    String getGitRepositoryUrl() {
        return "${scmmUrl}/${rootPath}/${scmmRepoTarget}"
    }
    /**
     * Delete all files in this repository
     */
    void clearRepo() {
        fileSystemUtils.deleteFilesExcept(new File(absoluteLocalRepoTmpDir), ".git")
    }
}