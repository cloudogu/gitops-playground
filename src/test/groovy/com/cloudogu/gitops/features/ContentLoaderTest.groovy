package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.git.GitHandlerForTests
import com.cloudogu.gitops.utils.git.TestGitRepoFactory
import com.cloudogu.gitops.utils.git.ScmManagerMock
import com.cloudogu.gitops.utils.git.TestScmManagerApiClient
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor

import static ContentLoader.RepoCoordinate
import static com.cloudogu.gitops.config.Config.ContentRepoType
import static com.cloudogu.gitops.config.Config.ContentSchema.ContentRepositorySchema
import static com.cloudogu.gitops.config.Config.OverwriteMode
import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

@Slf4j
@EnableKubernetesMockClient(crud=true)
class ContentLoaderTest {

    static List<File> foldersToDelete = new ArrayList<File>()

    Config config = new Config([
            application: [
                    namePrefix: 'foo-'
            ],
            scm        : [
                    scmManager: [
                            url: ''
                    ]
            ],
            registry   : [
                    url                   : 'reg-url',
                    path                  : 'reg-path',
                    username              : 'reg-user',
                    password              : 'reg-pw',
                    createImagePullSecrets: false
            ]
    ])

    KubernetesClient client
    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    K8sClientForTest k8sClient = new K8sClientForTest(config, k8sCommands)
    TestGitRepoFactory scmmRepoProvider = new TestGitRepoFactory(config, new FileSystemUtils())
    TestScmManagerApiClient scmmApiClient = new TestScmManagerApiClient(config)
    Jenkins jenkins = mock(Jenkins.class)
    ScmManagerMock scmManagerMock = new ScmManagerMock()
    GitHandler gitHandler = new GitHandlerForTests(config, scmManagerMock)

    @TempDir
    File tmpDir


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
            new RepoCoordinate(namespace: "copy", repoName: "repo1"),
            new RepoCoordinate(namespace: "copy", repoName: "repo2"),
    ]

    List<ContentRepositorySchema> contentRepos = [
            // copy-typed repo writing to their own target
            new ContentRepositorySchema(url: createContentRepo('copyRepo1'), type: ContentRepoType.COPY, target: 'copy/repo1'),
            new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'copy/repo2', path: 'subPath'),

            // Same folder as in copyRepos -> Should be combined
            new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
            new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath'),

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
        assertThat(new File(findRoot(repos), "common/repo/copyRepo1")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/repo/copyRepo2")).exists().isFile()

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
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo',  credentials: new Credentials('user', 'pw'))
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
    @DisplayName("Authenticates content Repos with secret")
    void authenticatesContentReposWithSecret() {

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName("secret-test-name")
                .withNamespace("default")
                .endMetadata()
                .withType("Opaque")
                .withData(Map.of(
                        "username", "YWRtaW4=",
                        "password", "czNjcjN0"
                ))
                .build()


        client.secrets()
                .inNamespace("default")
                .resource(secret)
                .create()

        config.content.repos = [
                new ContentRepositorySchema(
                        url: createContentRepo('copyRepo1'),
                        ref: 'main', type: ContentRepoType.COPY,
                        target: 'common/repo',
                        credentials: new Credentials(null,null,'secret-test-name','default'))
        ]

        def content = createContent()
        content.cloneContentRepos()

        ArgumentCaptor<UsernamePasswordCredentialsProvider> captor = ArgumentCaptor.forClass(UsernamePasswordCredentialsProvider)
        verify(content.cloneSpy).setCredentialsProvider(captor.capture())
        def value = captor.value
        assertThat(value.properties.username).isEqualTo('admin')
        assertThat(value.properties.password).isEqualTo('s3cr3t'.toCharArray())
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
                new ContentRepositorySchema(url: createContentRepo('', 'git-repo-different-default-branch'), target: 'common/default', type: ContentRepoType.COPY),
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(findRoot(repos), "common/default/README.md")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/default/README.md").text).contains("different")
    }

    @Test
    void 'Fails if commit ref does not exist'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: 'someTag', type: ContentRepoType.COPY, target: 'common/tag'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), ref: 'does/not/exist', type: ContentRepoType.FOLDER_BASED, target: 'does not matter'),
        ]

        def exception = shouldFail(RuntimeException) {
            createContent().cloneContentRepos()
        }

        assertThat(exception.message).startsWith("Reference 'does/not/exist' not found in content repository")
    }

    @Test
    void 'Respects order of folder-based repositories'() {
        config.content.repos = [
                // Note the different order!
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), ref: 'main', type: ContentRepoType.FOLDER_BASED),
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo2'), ref: 'main', type: ContentRepoType.FOLDER_BASED, path: 'subPath'),
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath'),
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(findRoot(repos), "common/repo/file").text).contains("copyRepo1")
        // Last repo "wins"
    }

    @Test
    void 'Is able to COPY into MIRRORED repo'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('mirrorRepo1', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'common/repo'),
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), type: ContentRepoType.FOLDER_BASED, overwriteMode: OverwriteMode.UPGRADE),
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', overwriteMode: OverwriteMode.UPGRADE, path: 'subPath')
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()

        def expectedRepo = 'common/repo'
        // clone target repo, to ensure, changes in remote repo.
        try (def git = cloneRepo(expectedRepo, tmpDir)) {
            assertThat(new File(tmpDir, "file").text).contains("copyRepo2") // Last repo "wins"
            assertThat(new File(tmpDir, "mirrorRepo1")).exists().isFile()
            assertThat(new File(tmpDir, "copyRepo2")).exists().isFile()
            assertThat(new File(tmpDir, "folderBasedRepo1")).exists().isFile()

            // Assert mirrors branches and tags of non-folderBased repos
            // Verify tag exists and points to correct content
            git.fetch().setRefSpecs("refs/*:refs/*").call() // Fetch all tags and branches

            assertTag(git, 'someTag')
            assertBranch(git, 'someBranch')
        }
    }

    @Test
    void 'Handles mirror and copy together'() {
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), type: ContentRepoType.FOLDER_BASED),
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', overwriteMode: OverwriteMode.UPGRADE, path: 'subPath'),
                new ContentRepositorySchema(url: createContentRepo('mirrorRepo1', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, overwriteMode: OverwriteMode.RESET, target: 'common/repo'),
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()

        def expectedRepo = 'common/repo'
        // clone target repo, to ensure, changes in remote repo.
        try (def git = cloneRepo(expectedRepo, tmpDir)) {
            assertThat(new File(tmpDir, "file").text).contains("mirrorRepo1") // Last repo "wins"
            assertThat(new File(tmpDir, "folderBasedRepo1")).doesNotExist()
            assertThat(new File(tmpDir, "copyRepo2")).doesNotExist()

            // Assert mirrors branches and tags of non-folderBased repos
            // Verify tag exists and points to correct content
            git.fetch().setRefSpecs("refs/*:refs/*").call() // Fetch all tags and branches

            assertTag(git, 'someTag')
            assertBranch(git, 'someBranch')
        }
    }

    @Test
    void 'Handles multiple mirrors of the same repo with different refs'() {
        def repoToMirror = createContentRepo('mirrorRepo1', 'git-repository-with-branches-tags')
        config.content.repos = [
                new ContentRepositorySchema(url: repoToMirror, type: ContentRepoType.MIRROR, ref: 'main', target: 'common/repo'),
                new ContentRepositorySchema(url: repoToMirror, type: ContentRepoType.MIRROR, ref: 'someBranch', target: 'common/repo', overwriteMode: OverwriteMode.UPGRADE),
                new ContentRepositorySchema(url: repoToMirror, type: ContentRepoType.MIRROR, ref: 'someTag', target: 'common/repo', overwriteMode: OverwriteMode.UPGRADE),
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', overwriteMode: OverwriteMode.UPGRADE, path: 'subPath')
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()

        def expectedRepo = 'common/repo'
        // clone target repo, to ensure, changes in remote repo.
        try (def git = cloneRepo(expectedRepo, tmpDir)) {
            assertThat(new File(tmpDir, "file").text).contains("copyRepo2") // Last repo "wins"
            assertThat(new File(tmpDir, "mirrorRepo1")).exists().isFile()

            git.fetch().setRefSpecs("refs/*:refs/*").call() // Fetch all tags and branches

            assertTag(git, 'someTag')
            assertBranch(git, 'someBranch')
        }
    }

    @Test
    void 'Handles targetRefs'() {
        config.content.repos = [
                // From branch to branch or tag to tag
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'mirror/tag', ref: 'someTag', targetRef: 'my-tag'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'mirror/branch', ref: 'someBranch', targetRef: 'my-branch'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.COPY, target: 'copy/tag', ref: 'someTag', targetRef: 'my-tag'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.COPY, target: 'copy/branch', ref: 'someBranch', targetRef: 'my-branch'),

                // From tag to branch or the other way round
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'mirror/tag2branch', ref: 'someTag', targetRef: 'refs/heads/my-branch'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'mirror/branch2tag', ref: 'someBranch', targetRef: 'refs/tags/my-tag'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.COPY, target: 'copy/tag2branch', ref: 'someTag', targetRef: 'refs/heads/my-branch'),
                new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.COPY, target: 'copy/branch2tag', ref: 'someBranch', targetRef: 'refs/tags/my-tag'),
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()

        // From branch to branch or tag to tag
        assertTagAndReadme('mirror/tag', 'my-tag', "someTag")
        assertBranchAndReadme('mirror/branch', 'my-branch', "someBranch")

        assertTagAndReadme('copy/tag', 'my-tag', "someTag")
        assertBranchAndReadme('copy/branch', 'my-branch', "someBranch")

        // From tag to branch or the other way round
        assertTagAndReadme('mirror/branch2tag', 'my-tag', "someBranch")
        assertBranchAndReadme('mirror/tag2branch', 'my-branch', "someTag")

        assertTagAndReadme('copy/branch2tag', 'my-tag', "someBranch")
        assertBranchAndReadme('copy/tag2branch', 'my-branch', "someTag")
    }

    @Test
    void 'Handles multiple mirrors of the same repo with different refs, where one is not pushed'() {
        // This test case does not make too much sense but used to cause git problems when we merged all content repos into a single folder, like 
        // TransportException: Missing unknown 5bcf50f0537bf4d2719a82e9b0950fbac92b3ecc
        def repoToMirror = createContentRepo('copyRepo1', 'git-repository-with-branches-tags')
        config.content.repos = [
                new ContentRepositorySchema(url: repoToMirror, type: ContentRepoType.MIRROR, ref: 'main', target: 'common/repo'),
                new ContentRepositorySchema(url: repoToMirror, type: ContentRepoType.MIRROR, ref: 'someBranch', target: 'common/repo') /* Deliberately not use overwriteMode here !*/,
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', overwriteMode: OverwriteMode.UPGRADE, path: 'subPath')
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()
        // No exception means success
    }


    @Test
    void 'Is able to MIRROR into repo that has same commits'() {
        // This test case does not make too much sense but used to cause git problems when copying .git from source to target
        // java.lang.IllegalArgumentException: File parameter 'destFile is not writable: '/tmp/../.git/objects/pack/pack-524e3f54c7b28a98a4995948dfc8e75f1642840f.pack'
        // This only occurs when the same .pack files exists in .git because they are read-only
        // So for our testcase we just mirror the same repo twice
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('mirrorRepo1', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'common/repo'),
                new ContentRepositorySchema(url: createContentRepo('mirrorRepo1', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, target: 'common/repo', overwriteMode: OverwriteMode.RESET),
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()
        // No exception means success
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

            assertThat(actual[0].clonedContentRepo.absolutePath).isEqualTo(
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
                ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()

        def expectedRepo = 'copy/repo1'
        // clone target repo, to ensure, changes in remote repo.
        try (def git = cloneRepo(expectedRepo, tmpDir)) {

            def commitMsg = git.log().call().iterator().next().getFullMessage()
            assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

            assertThat(new File(tmpDir, "file").text).contains("copyRepo1")
            assertThat(new File(tmpDir, "copyRepo1")).exists().isFile()
        }

        expectedRepo = 'common/mirror'
        try (def git = cloneRepo(expectedRepo, createRandomSubDir())) {
            // Assert mirrors branches and tags of non-folderBased repos
            // Verify tag exists and points to correct content
            git.fetch().setRefSpecs("refs/*:refs/*").call() // Fetch all tags and branches

            assertTag(git, 'someTag')
            assertBranch(git, 'someBranch')
        }

        expectedRepo = 'common/mirrorWithBranchRef'
        try (def git = cloneRepo(expectedRepo, createRandomSubDir())) {

            git.fetch().setRefSpecs("refs/*:refs/*").call()

            assertNoTags(git)
            assertOnlyBranch(git, 'main')
        }

        expectedRepo = 'common/mirrorWithTagRef'
        try (def git = cloneRepo(expectedRepo, createRandomSubDir())) {

            git.fetch().setRefSpecs("refs/*:refs/*").call()

            assertTag(git, 'someTag')
            assertOnlyBranch(git, 'main')
        }

        // Mirroring commit references is not supported 
        config.content.repos = [new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, ref: '8bc1d1165468359b16d9771d4a9a3df26afc03e8', target: 'common/mirrorWithCommitRef')]

        def exception = shouldFail(RuntimeException) {
            createContent().install()
        }
        assertThat(exception.message).startsWith('Mirroring commit references is not supported for content repos at the moment. content repository')
        assertThat(exception.message).endsWith('ref: 8bc1d1165468359b16d9771d4a9a3df26afc03e8')


        // Mirroring short commit references is not supported as well
        config.content.repos = [new ContentRepositorySchema(url: createContentRepo('', 'git-repository-with-branches-tags'), type: ContentRepoType.MIRROR, ref: '8bc1d11', target: 'common/mirrorWithShortCommitRef')]

        exception = shouldFail(RuntimeException) {
            createContent().install()
        }
        assertThat(exception.message).startsWith('Mirroring commit references is not supported for content repos at the moment. content repository')
        assertThat(exception.message).endsWith('ref: 8bc1d11')

        // Don't bother validating all other repos here.
        // If it works for the most complex one, the other ones will work as well.
        // The other tests are already asserting correct combining (including order) and parsing of the repos.
    }

    static void assertOnlyBranch(Git git, String branch) {
        def branches = assertBranch(git, branch)
        def otherBranches = branches.findAll { !it.name.contains(branch) }
        assertThat(otherBranches)
                .withFailMessage("More than the expected branch main found. Available branches: ${otherBranches.collect { it.name }}")
                .hasSize(0)
    }

    static void assertNoTags(Git git) {
        def tags = git.tagList().call()
        assertThat(tags)
                .withFailMessage("No tags in mirrored repo with ref expected. Available tags: ${tags.collect { it.name }}")
                .hasSize(0)
    }

    static List<Ref> assertBranch(Git git, String someBranch) {
        def branches = git.branchList().call()
        assertThat(branches.findAll { it.name == "refs/heads/${someBranch}" })
                .withFailMessage("Branch '${someBranch}' not found in git repository. Available branches: ${branches.collect { it.name }}")
                .hasSize(1)
        return branches
    }

    static void assertTag(Git git, String expectedTag) {
        def tags = git.tagList().call()
        assertThat(tags.findAll { it.name == "refs/tags/$expectedTag" })
                .withFailMessage("Tag '$expectedTag' not found in git repository. Available tags: ${tags.collect { it.name }}")
                .hasSize(1)
    }

    @Test
    void 'Reset common repo to repo '() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by copyRepo1 + 2
         * file content after that: copyRepo2
         *
         * Then again "RESET" to copyRepo1.
         * file content after that should be: copyRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath')

        ]
        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo, scmManagerMock)
        scmManagerMock.initOnceRepo(repo.repoTarget)
        createContent().install()

        String url = repo.getGitRepositoryUrl()
        // clone repo, to ensure, changes in remote repo.
        try (def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(tmpDir).call()) {


            verify(repo).createRepositoryAndSetPermission(any(String.class), eq(false))

            def commitMsg = git.log().call().iterator().next().getFullMessage()
            assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

            assertThat(new File(tmpDir, "file").text).contains("copyRepo2")
            assertThat(new File(tmpDir, "copyRepo2")).exists().isFile()
        }

        /**
         * End of preparation
         *
         * Now Reset to an copied repo
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', overwriteMode: OverwriteMode.RESET),
        ]

        createContent().install()
        scmManagerMock.clearInitOnce()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        folderAfterReset.deleteOnExit()
        // clone repo, to ensure, changes in remote repo.
        try (def git2 = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(folderAfterReset).call()) {

            assertThat(git2).isNotNull()
            // because copyRepo1 is only part of repo1
            assertThat(new File(folderAfterReset, "file").text).contains("copyRepo1")
            // should not exists, if RESET to first repo
            assertThat(new File(folderAfterReset, "copyRepo2").exists()).isFalse()

        }


    }

    @Test
    void 'Update common repo test '() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by copyRepo1 + 2
         * file content after that: copyRepo2
         *
         * Then again "RESET" to copyRepo1.
         * file content after that should be: copyRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
        ]

        scmmApiClient.mockRepoApiBehaviour()

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo, new ScmManagerMock())

        def url = repo.getGitRepositoryUrl()
        // clone repo, to ensure, changes in remote repo.
        try (def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(tmpDir).call()) {


            verify(repo).createRepositoryAndSetPermission(any(String.class), eq(false))

            def commitMsg = git.log().call().iterator().next().getFullMessage()
            assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

            assertThat(new File(tmpDir, "file").text).contains("copyRepo1")
            assertThat(new File(tmpDir, "copyRepo1")).exists().isFile()

        }
        /**
         * End of preparation
         *
         * Now Upgrade to type copy
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath', overwriteMode: OverwriteMode.UPGRADE)
        ]


        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        folderAfterReset.deleteOnExit()
        // clone repo, to ensure, changes in remote repo.
        try (def git2 = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(folderAfterReset).call()) {

            assertThat(git2).isNotNull()
            // because copyRepo1 is only part of repo1
            assertThat(new File(folderAfterReset, "file").text).contains("copyRepo2")
            // should not exists, if RESET to first repo
            assertThat(new File(folderAfterReset, "copyRepo2").exists()).isTrue()

        }
    }

    @Test
    void 'init common repo, expect unchanged repo'() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by copyRepo1 + 2
         * file content after that: copyRepo2
         *
         * Then again "RESET" to copyRepo1.
         * file content after that should be: copyRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo'),
                new ContentRepositorySchema(url: createContentRepo('copyRepo2'), type: ContentRepoType.COPY, target: 'common/repo', path: 'subPath')

        ]
        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo, scmManagerMock)
        scmManagerMock.initOnceRepo(repo.repoTarget)
        createContent().install()

        def url = repo.getGitRepositoryUrl()
        // clone repo, to ensure, changes in remote repo.
        try (def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(tmpDir).call()) {

            verify(repo).createRepositoryAndSetPermission(any(String.class), eq(false))

            def commitMsg = git.log().call().iterator().next().getFullMessage()
            assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

            assertThat(new File(tmpDir, "file").text).contains("copyRepo2")
            assertThat(new File(tmpDir, "copyRepo2")).exists().isFile()
        }

        /**
         * End of preparation
         *
         * Now INit to a copied repo
         * no changes expected, file still has copyRepo2 and so on
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, target: 'common/repo', overwriteMode: OverwriteMode.INIT),
        ]

        createContent().install()
        scmManagerMock.clearInitOnce()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        folderAfterReset.deleteOnExit()
        // clone repo, to ensure, changes in remote repo.
        try (def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(folderAfterReset).call()) {

            assertThat(git).isNotNull()
            // because copyRepo1 is only part of repo1
            assertThat(new File(folderAfterReset, "file").text).contains("copyRepo2")
            // should not exists, if RESET to first repo
            assertThat(new File(folderAfterReset, "copyRepo2").exists()).isTrue()

        }

    }

    @Test
    void 'ensure Jenkinsjob will be created'() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by copyRepo1 + 2
         * file content after that: copyRepo2
         *
         * Then again "RESET" to copyRepo1.
         * file content after that should be: copyRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, createJenkinsJob: true, target: 'common/repo'),
        ]
        scmmApiClient.mockRepoApiBehaviour()
        when(jenkins.isEnabled()).thenReturn(true)

        createContent().install()
        verify(jenkins).createJenkinsjob(any(), any())
    }

    @Test
    void 'ensure Jenkinsjob creation will be ignored'() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by copyRepo1 + 2
         * file content after that: copyRepo2
         *
         * Then again "RESET" to copyRepo1.
         * file content after that should be: copyRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, createJenkinsJob: false, target: 'common/repo'),
        ]
        scmmApiClient.mockRepoApiBehaviour()
        when(jenkins.isEnabled()).thenReturn(false)
        createContent().install()
        verify(jenkins, never()).createJenkinsjob(any(), any())
    }

    @Test
    void 'ensure Jenkinsjob will not be created, if jenkins is not enables'() {
        /**
         * Prepare Testcase
         * using all defined repos ->  common/repo is used by copyRepo1 + 2
         * file content after that: copyRepo2
         *
         * Then again "RESET" to copyRepo1.
         * file content after that should be: copyRepo1
         */
        config.content.repos = [
                new ContentRepositorySchema(url: createContentRepo('copyRepo1'), ref: 'main', type: ContentRepoType.COPY, createJenkinsJob: false, target: 'common/repo'),
        ]
        scmmApiClient.mockRepoApiBehaviour()
        when(jenkins.isEnabled()).thenReturn(false)

        createContent().install()
        verify(jenkins, never()).createJenkinsjob(any(), any())
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
            try (def git = Git.cloneRepository()
                    .setURI(bareRepoUri)
                    .setBranch('main')
                    .setDirectory(tempRepo)
                    .call()) {


                FileUtils.copyDirectory(new File(System.getProperty("user.dir") + '/src/test/groovy/com/cloudogu/gitops/utils/data/contentRepos/' + initPath), tempRepo)

                git.add().addFilepattern(".").call()

                // Avoid complications with local developer's git config, e.g. when  git config --global commit.gpgSign true
                SystemReader.getInstance().userConfig.clear()
                git.commit().setMessage("Initialize with $initPath").call()
                git.push().call()
                tempRepo.delete()
            }
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

    private ContentLoaderForTest createContent() {
        new ContentLoaderForTest(config, k8sClient, scmmRepoProvider, jenkins, gitHandler)
    }

    private static parseActualYaml(File pathToYamlFile) {
        def ys = new YamlSlurper()
        return ys.parse(pathToYamlFile)
    }

    private static String findRoot(List<RepoCoordinate> repos) {
        def result = new File(repos.get(0).getClonedContentRepo().getParent()).getParent()
        return result;

    }

    Git cloneRepo(String expectedRepo, File repoFolder) {
        def repo = scmmRepoProvider.getRepo(expectedRepo, new ScmManagerMock())
        def url = repo.getGitRepositoryUrl()

        def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(repoFolder).call()
        git.getRepository().getConfig().setBoolean("gc", null, "autoDetach", false)
        return git
    }


    private File createRandomSubDir(String prefix = '') {
        def randomDir = tmpDir.toPath().resolve("${prefix ? "${prefix}-" : ''}${System.currentTimeMillis()}").toFile()
        randomDir.mkdirs()
        return randomDir
    }

    void assertTagAndReadme(String repo, String expectedTag, String expectedReadmeContent) {
        def repoFolder = createRandomSubDir()
        try (def git = cloneRepo(repo, repoFolder)) {
            git.fetch().setRefSpecs("refs/*:refs/*").call()
            assertTag(git, expectedTag)

            git.checkout().setName(expectedTag).call()
            assertThat(new File(repoFolder, "README.md")).exists().isFile()
            assertThat(new File(repoFolder, "README.md").text).contains(expectedReadmeContent)
        }
    }

    void assertBranchAndReadme(String repo, String expectedBranch, String expectedReadmeContent) {
        def repoFolder = createRandomSubDir()
        try (def git = cloneRepo(repo, repoFolder)) {
            git.fetch().setRefSpecs("refs/*:refs/*").call()
            assertBranch(git, expectedBranch)

            git.checkout().setName(expectedBranch).call()
            assertThat(new File(repoFolder, "README.md")).exists().isFile()
            assertThat(new File(repoFolder, "README.md").text).contains(expectedReadmeContent)
        }
    }

    class ContentLoaderForTest extends ContentLoader {
        CloneCommand cloneSpy

        ContentLoaderForTest(Config config, K8sClient k8sClient, GitRepoFactory repoProvider, Jenkins jenkins, GitHandler gitHandler) {
            super(config, k8sClient, repoProvider, jenkins, gitHandler)
        }

        @Override
        protected CloneCommand gitClone() {
            cloneSpy = spy(super.gitClone().setNoCheckout(true))
        }
    }
}