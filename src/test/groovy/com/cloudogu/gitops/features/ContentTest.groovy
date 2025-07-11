package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

import static com.cloudogu.gitops.config.Config.*
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema
import static com.cloudogu.gitops.features.Content.RepoCoordinate
import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

@Slf4j
class ContentTest {
    // bareRepo
    static List<File> foldersToDelete = new ArrayList<File>()

    Config config = new Config(
            application: new ApplicationSchema(
                    namePrefix: 'foo-'),
            registry: new RegistrySchema(
                    url: 'reg-url',
                    path: 'reg-path',
                    username: 'reg-user',
                    password: 'reg-pw',
                    createImagePullSecrets: false,))

    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    K8sClientForTest k8sClient = new K8sClientForTest(config, k8sCommands)
    TestScmmRepoProvider scmmRepoProvider = new TestScmmRepoProvider(config, new FileSystemUtils())
    TestScmmApiClient scmmApiClient = new TestScmmApiClient(config)

    List<RepoCoordinate> expectedTargetRepos = [
            new RepoCoordinate(namespace: "common", repoName: "repo"),
            new RepoCoordinate(namespace: "ns1a", repoName: "repo1a1"),
            new RepoCoordinate(namespace: "ns1a", repoName: "repo1a2"),
            new RepoCoordinate(namespace: "ns1b", repoName: "repo1b1"),
            new RepoCoordinate(namespace: "ns1b", repoName: "repo1b2"),
            new RepoCoordinate(namespace: "ns2a", repoName: "repo2a1"),
            new RepoCoordinate(namespace: "ns2a", repoName: "repo2a2"),
            new RepoCoordinate(namespace: "ns2b", repoName: "repo2b1"),
            new RepoCoordinate(namespace: "ns2b", repoName: "repo2b2"),
            new RepoCoordinate(namespace: "nonFolderBased", repoName: "repo1"),
            new RepoCoordinate(namespace: "nonFolderBased", repoName: "repo2")
    ]

    List<ContentRepositorySchema> contentRepos = [
            // Non-folder-based repo writing to their own target
            new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), type: ContentRepoType.COPY, target: 'nonFolderBased/repo1'),
            new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), type: ContentRepoType.COPY, target: 'nonFolderBased/repo2', path: 'subPath'),

            // Same folder as in folderBasedRepos -> Should be combined
            new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
            new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath'),

            // Contains ftl
            new ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), type: ContentRepoType.FOLDER_BASED, templating: true),
            // Contains a templated file that should be ignored
            new ContentRepositorySchema(url: createContentRepo('folderBasedRepo2'), type: ContentRepoType.FOLDER_BASED, path: 'subPath'),
    ]

    @AfterAll
    static void cleanFolders() {
        foldersToDelete.each { it.deleteDir() }
    }


    @Test
    void 'deploys image pull secrets'() {
        config.content.examples = true
        config.registry.createImagePullSecrets = true
        config.content.namespaces = ['example-apps-staging', 'example-apps-production']

        createContent().install()

        assertRegistrySecrets('reg-user', 'reg-pw')
    }

    @Test
    void 'deploys image pull secrets from read-only vars'() {
        config.content.examples = true
        config.registry.createImagePullSecrets = true
        config.content.namespaces = ['example-apps-staging', 'example-apps-production']
        config.registry.readOnlyUsername = 'other-user'
        config.registry.readOnlyPassword = 'other-pw'

        createContent().install()

        assertRegistrySecrets('other-user', 'other-pw')
    }

    @Test
    void 'deploys additional image pull secrets for proxy registry'() {
        config.content.examples = true
        config.registry.createImagePullSecrets = true
        config.content.namespaces = ['example-apps-staging', 'example-apps-production']
        config.registry.twoRegistries = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'

        // Simulate argocd Namespace does not exist
        k8sCommands.enqueueOutput(new CommandExecutor.Output('namespace not found', '', 1)) // Namespace not exit
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 0)) // other kubectl
        k8sCommands.enqueueOutput(new CommandExecutor.Output('', '', 1)) // Namespace not exit

        createContent().install()

        assertRegistrySecrets('reg-user', 'reg-pw')

        k8sClient.commandExecutorForTest.assertExecuted('kubectl create namespace example-apps-staging')
        k8sClient.commandExecutorForTest.assertExecuted('kubectl create namespace example-apps-production')
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n example-apps-staging' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n example-apps-production' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
    }

    @Test
    void 'Combines content repos successfully'() {

        config.content.repos = contentRepos

        def repos = createContent().cloneContentRepos()

        expectedTargetRepos.each { expected ->
            assertThat(new File(findRoot(repos), "${expected.namespace}/${expected.repoName}/file")).exists().isFile()
        }

        assertThat(new File(findRoot(repos), "common/repo/file").text).contains("folderBasedRepo2") // Last repo "wins"

        assertThat(new File(findRoot(repos), "common/repo/folderBasedRepo1")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/repo/folderBasedRepo2")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/repo/nonFolderBasedRepo1")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/repo/nonFolderBasedRepo2")).exists().isFile()

        // Assert Templating
        assertThat(new File(findRoot(repos), "common/repo/some.yaml")).exists()
        assertThat(new File(findRoot(repos), "common/repo/some.yaml").text).contains("namePrefix: foo-")
        // Assert not templating for this folder-based repo
        assertThat(new File(findRoot(repos), "common/repo/someOther.yaml.ftl")).exists()
        assertThat(new File(findRoot(repos), "common/repo/someOther.yaml.ftl").text).contains('namePrefix: ${config.application.namePrefix}')
    }

    @Test
    void 'supports content variables'() {

        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), type: ContentRepoType.FOLDER_BASED, templating: true)
        ]
        config.content.variables.someapp = [somevalue: 'this is a custom variable']

        def repos = createContent().cloneContentRepos()

        // Assert Templating
        assertThat(new File(findRoot(repos), "common/repo/some.yaml")).exists()
        assertThat(new File(findRoot(repos), "common/repo/some.yaml").text).contains("namePrefix: foo-")
        assertThat(new File(findRoot(repos), "common/repo/some.yaml").text).contains("myvar: this is a custom variable")
    }

    @Test
    void 'Authenticates content Repos'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', username: 'user', password: 'pw')
        ]

        def content = createContent()
        content.cloneContentRepos()

        ArgumentCaptor<UsernamePasswordCredentialsProvider> captor = ArgumentCaptor.forClass(UsernamePasswordCredentialsProvider)
        verify(content.cloneSpy).setCredentialsProvider(captor.capture())


        def value = captor.value
        assertThat(value.properties.username).isEqualTo('user')
        assertThat(value.properties.password).isEqualTo('pw'.toCharArray())
    }

    @Test
    void 'Checks out commit refs, tags and non-default branches for content repos'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: 'someTag', type: ContentRepoType.COPY, target: 'common/tag'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: '8bc1d1165468359b16d9771d4a9a3df26afc03e8', type: ContentRepoType.COPY, target: 'common/ref'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: 'someBranch', type: ContentRepoType.COPY, target: 'common/branch')
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(findRoot(repos), "common/tag/README.md")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/tag/README.md").text).contains("someTag")

        assertThat(new File(findRoot(repos), "common/ref/README.md")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/ref/README.md").text).contains("main")

        assertThat(new File(findRoot(repos), "common/branch/README.md")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/branch/README.md").text).contains("someBranch")
    }

    @Test
    void 'Checks out default branch when no ref set'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('', 'git-repo-different-default-branch'), target: 'common/default'),
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(findRoot(repos), "common/default/README.md")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/default/README.md").text).contains("different")
    }

    @Test
    void 'Fails if commit refs does not exit'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: 'someTag', type: ContentRepoType.COPY, target: 'common/tag'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: 'does/not/exist', type: ContentRepoType.FOLDER_BASED, target: 'does not matter'),
        ]

        def exception = shouldFail(RuntimeException) {
            createContent().cloneContentRepos()
        }
        assertThat(exception.message).startsWith("Reference 'does/not/exist' not found in repository")
    }

    @Test
    void 'Respects order of folder-based repositories'() {
        config.content.repos = [
                // Note the different order!
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), ref: 'main', type: ContentRepoType.FOLDER_BASED),
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo2'), ref: 'main', type: ContentRepoType.FOLDER_BASED, path: 'subPath'),
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath'),
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(findRoot(repos), "common/repo/file").text).contains("nonFolderBasedRepo1")
        // Last repo "wins"
    }

    @Test
    void 'Parses Repo coordinates'() {

        config.content.repos = contentRepos

        def content = createContent()

        def actualTargetRepos = content.cloneContentRepos()
        def repos = actualTargetRepos

        assertThat(actualTargetRepos).hasSameSizeAs(expectedTargetRepos)

        expectedTargetRepos.each { expected ->

            def actual = actualTargetRepos.findAll { actual ->
                actual.namespace == expected.namespace && actual.repoName == expected.repoName
            }
            assertThat(actual).withFailMessage(
                    "Could not find repo with namespace=${expected.namespace} and repo=${expected.repoName} in ${actualTargetRepos}"
            ).hasSize(1)

            assertThat(actual[0].newContent.absolutePath).isEqualTo(
                    new File(findRoot(repos), "${expected.namespace}/${expected.repoName}").absolutePath)
        }
    }

    @Test
    void 'Creates and pushes content repos, whole flow '() {
        config.content.repos = contentRepos +
                [
                        new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'common/mirror'),
                        new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, ref: 'main', target: 'common/mirrorWithBranchRef'),
                        new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, ref: 'someTag', target: 'common/mirrorWithTagRef'),
                        // TODO does not work with commit ref, yet remote: error: invalid protocol: wanted 'old new ref'
                        //new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, ref: '8bc1d1165468359b16d9771d4a9a3df26afc03e8', target: 'common/mirrorWithCommitRef')                
                ]

        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'nonFolderBased/repo1'
        def repoFolder = File.createTempDir('cloned-repo')
        // clone target repo, to ensure, changes in remote repo.
        try (def git = cloneRepo(expectedRepo, repoFolder)) {

            def commitMsg = git.log().call().iterator().next().getFullMessage()
            assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

            assertThat(new File(repoFolder, "file").text).contains("nonFolderBasedRepo1")
            assertThat(new File(repoFolder, "nonFolderBasedRepo1")).exists().isFile()
        }

        expectedRepo = 'common/mirror'
        try (def git = cloneRepo(expectedRepo, File.createTempDir('cloned-repo'))) {
            // Assert mirrors branches and tags of non-folderBased repos
            // Verify tag exists and points to correct content
            git.fetch().setRefSpecs("refs/*:refs/*").call() // Fetch all tags and branches

            assertTag(git, 'someTag')
            assertBranch(git, 'someBranch')
        }

        expectedRepo = 'common/mirrorWithBranchRef'
        try (def git = cloneRepo(expectedRepo, File.createTempDir('cloned-repo'))) {

            git.fetch().setRefSpecs("refs/*:refs/*").call()

            assertNoTags(git)
            assertOnlyBranch(git, 'main')
        }

        expectedRepo = 'common/mirrorWithTagRef'
        try (def git = cloneRepo(expectedRepo, File.createTempDir('cloned-repo'))) {

            git.fetch().setRefSpecs("refs/*:refs/*").call()

            assertTag(git, 'someTag')
            assertOnlyBranch(git, 'main')
        }

/*        expectedRepo = 'common/mirrorWithCommitRef'
        try (def git = cloneRepo(expectedRepo, File.createTempDir('cloned-repo'))) {

            git.fetch().setRefSpecs("refs/*:refs/*").call()
            // TODO assertCommit
            assertNoTags(git)
            assertOnlyBranch(git, 'main')
        }*/

        // Don't bother validating all other repos here.
        // If it works for the most complex one, the other ones will work as well.
        // The other tests are already asserting correct combining (including order) and parsing of the repos.
    }

    static void assertOnlyBranch(Git git, String branch) {
        def branches = assertBranch(git, branch)
        def otherBranches = branches.findAll { !it.name.contains(branch) }
        assertThat(otherBranches).hasSize(0)
                .withFailMessage("More than the expected branch main found. Available branches: ${otherBranches.collect { it.name }}")
    }

    static void assertNoTags(Git git) {
        def tags = git.tagList().call()
        assertThat(tags).hasSize(0)
                .withFailMessage("No tags in mirrored repo with ref expected. Available tags: ${tags.collect { it.name }}")
    }

    static List<Ref> assertBranch(Git git, String someBranch) {
        def branches = git.branchList().call()
        assertThat(branches.findAll { it.name == "refs/heads/${someBranch}" }).hasSize(1)
                .withFailMessage("Branch '${someBranch}' not found in git repository. Available branches: ${branches.collect { it.name }}")
        return branches
    }

    static void assertTag(Git git, String expectedTag) {
        def tags = git.tagList().call()
        assertThat(tags.findAll { it.name == "refs/tags/$expectedTag" }).hasSize(1)
                .withFailMessage("Tag '$expectedTag' not found in git repository. Available tags: ${tags.collect { it.name }}")
    }

    @Test
    void 'Reset common_repo to repo '() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by nonFolderRepo1 + 2
         * file content after that: nonFolderRepo2
         *
         * Then again "RESET" to nonFolderRepo1.
         * file content after that should be: nonFolderRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath')

        ]
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in remote repo.
        def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(repoFolder).call()


        verify(repo).create(eq(''), any(ScmmApiClient))

        def commitMsg = git.log().call().iterator().next().getFullMessage()
        assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

        assertThat(new File(repoFolder, "file").text).contains("nonFolderBasedRepo2")
        assertThat(new File(repoFolder, "nonFolderBasedRepo2")).exists().isFile()

        /**
         * End of preparation
         *
         * Now Reset to nonFolderBased1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', overrideMode: OverrideMode.RESET),
        ]

        def resourceExistsAnswer = scmmApiClient.mockErrorResponse(409)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(resourceExistsAnswer)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(resourceExistsAnswer)

        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        // clone repo, to ensure, changes in remote repo.
        Git.cloneRepository().setURI(url).setBranch('main').setDirectory(folderAfterReset).call()
        // because nonFolderBasedRepo1 is only part of repo1
        assertThat(new File(folderAfterReset, "file").text).contains("nonFolderBasedRepo1")
        // should not exists, if RESET to first repo
        assertThat(new File(folderAfterReset, "nonFolderBasedRepo2").exists()).isFalse()

    }

    @Test
    void 'Update common_repo test '() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by nonFolderRepo1 + 2
         * file content after that: nonFolderRepo2
         *
         * Then again "RESET" to nonFolderRepo1.
         * file content after that should be: nonFolderRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
        ]
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in remote repo.
        def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(repoFolder).call()


        verify(repo).create(eq(''), any(ScmmApiClient))

        def commitMsg = git.log().call().iterator().next().getFullMessage()
        assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

        assertThat(new File(repoFolder, "file").text).contains("nonFolderBasedRepo1")
        assertThat(new File(repoFolder, "nonFolderBasedRepo1")).exists().isFile()

        /**
         * End of preparation
         *
         * Now Upgrade to nonFolderBased2
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath', overrideMode: OverrideMode.UPGRADE)
        ]

        def resourceExistsAnswer = scmmApiClient.mockErrorResponse(409)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(resourceExistsAnswer)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(resourceExistsAnswer)

        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        // clone repo, to ensure, changes in remote repo.
        Git.cloneRepository().setURI(url).setBranch('main').setDirectory(folderAfterReset).call()
        // because nonFolderBasedRepo1 is only part of repo1
        assertThat(new File(folderAfterReset, "file").text).contains("nonFolderBasedRepo2")
        // should not exists, if RESET to first repo
        assertThat(new File(folderAfterReset, "nonFolderBasedRepo2").exists()).isTrue()

    }

    @Test
    void 'init common_repo, expect unchanged repo'() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by nonFolderRepo1 + 2
         * file content after that: nonFolderRepo2
         *
         * Then again "RESET" to nonFolderRepo1.
         * file content after that should be: nonFolderRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath')

        ]
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in remote repo.
        def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(repoFolder).call()


        verify(repo).create(eq(''), any(ScmmApiClient))

        def commitMsg = git.log().call().iterator().next().getFullMessage()
        assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

        assertThat(new File(repoFolder, "file").text).contains("nonFolderBasedRepo2")
        assertThat(new File(repoFolder, "nonFolderBasedRepo2")).exists().isFile()

        /**
         * End of preparation
         *
         * Now INit to nonFolderBased1
         * no changes expected, file still has nonFolderBasedRepo2 and so on
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', overrideMode: OverrideMode.INIT),
        ]

        def resourceExistsAnswer = scmmApiClient.mockErrorResponse(409)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(resourceExistsAnswer)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(resourceExistsAnswer)

        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        // clone repo, to ensure, changes in remote repo.
        Git.cloneRepository().setURI(url).setBranch('main').setDirectory(folderAfterReset).call()
        // because nonFolderBasedRepo1 is only part of repo1
        assertThat(new File(folderAfterReset, "file").text).contains("nonFolderBasedRepo2")
        // should not exists, if RESET to first repo
        assertThat(new File(folderAfterReset, "nonFolderBasedRepo2").exists()).isTrue()

    }


    static String createContentRepo(String initPath = '', String baseBareRepo = 'git-repository') {
        // The bare repo works as the "remote"
        def bareRepoDir = File.createTempDir('gitops-playground-test-content-repo')
        bareRepoDir.deleteOnExit()
        foldersToDelete << bareRepoDir
        // init with bare repo
        FileUtils.copyDirectory(new File(System.getProperty("user.dir") + "/src/test/groovy/com/cloudogu/gitops/utils/data/${baseBareRepo}/"), bareRepoDir)
        def bareRepoUri = 'file://' + bareRepoDir.absolutePath
        log.debug("Repo $initPath: bare repo $bareRepoUri")

        if (initPath) {
            // Add initPath to bare repo
            def tempRepo = File.createTempDir('gitops-playground-temp-repo')
            tempRepo.deleteOnExit()
            foldersToDelete << tempRepo
            log.debug("Repo $initPath: cloned bare repo to $tempRepo")
            def git = Git.cloneRepository()
                    .setURI(bareRepoUri)
                    .setBranch('main')
                    .setDirectory(tempRepo)
                    .call()

            FileUtils.copyDirectory(new File(System.getProperty("user.dir") + '/src/test/groovy/com/cloudogu/gitops/utils/data/contentRepos/' + initPath), tempRepo)

            git.add().addFilepattern(".").call()

            // Avoid complications with local developer's git config, e.g. when  git config --global commit.gpgSign true
            SystemReader.getInstance().userConfig.clear()
            git.commit().setMessage("Initialize with $initPath").call()
            git.push().call()
            tempRepo.delete()
        }

        return bareRepoUri
    }


    private void assertRegistrySecrets(String regUser, String regPw) {
        List expectedNamespaces = ["example-apps-staging", "example-apps-production"]
        expectedNamespaces.each {

            k8sClient.commandExecutorForTest.assertExecuted(
                    "kubectl create secret docker-registry registry -n ${it}" +
                            " --docker-server reg-url --docker-username $regUser --docker-password ${regPw}" +
                            ' --dry-run=client -oyaml | kubectl apply -f-')

            def patchCommand = k8sClient.commandExecutorForTest.assertExecuted(
                    "kubectl patch serviceaccount default -n ${it}")
            String patchFile = (patchCommand =~ /--patch-file=([\S]+)/)?.findResult { (it as List)[1] }
            assertThat(parseActualYaml(new File(patchFile))['imagePullSecrets'] as List).hasSize(1)
            assertThat((parseActualYaml(new File(patchFile))['imagePullSecrets'] as List)[0] as Map).containsEntry('name', 'registry')
        }
    }

    private ContentForTest createContent() {
        new ContentForTest(config, k8sClient, scmmRepoProvider, scmmApiClient)
    }

    private parseActualYaml(File pathToYamlFile) {
        def ys = new YamlSlurper()
        return ys.parse(pathToYamlFile)
    }

    String findRoot(List<RepoCoordinate> repos) {
        def result = new File(repos.get(0).getNewContent().getParent()).getParent()
        return result;

    }

    Git cloneRepo(String expectedRepo, File repoFolder) {
        def repo = scmmRepoProvider.getRepo(expectedRepo)
        def url = repo.getGitRepositoryUrl()
        repoFolder.deleteOnExit()
        return Git.cloneRepository().setURI(url).setBranch('main').setDirectory(repoFolder).call()
    }


    class ContentForTest extends Content {
        CloneCommand cloneSpy

        ContentForTest(Config config, K8sClient k8sClient, ScmmRepoProvider repoProvider, ScmmApiClient scmmApiClient) {
            super(config, k8sClient, repoProvider, scmmApiClient)
        }

        @Override
        protected CloneCommand gitClone() {
            cloneSpy = spy(super.gitClone().setNoCheckout(true))
        }
    }
}