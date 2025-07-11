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
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

@Slf4j
class ContentTest {
    // bareRepo
    static List<File> foldersToDelete = new ArrayList<File>()

    Config config = new Config(
            application: new Config.ApplicationSchema(
                    namePrefix: 'foo-'),
            registry: new Config.RegistrySchema(
                    url: 'reg-url',
                    path: 'reg-path',
                    username: 'reg-user',
                    password: 'reg-pw',
                    createImagePullSecrets: false,))

    CommandExecutorForTest k8sCommands = new CommandExecutorForTest()
    K8sClientForTest k8sClient = new K8sClientForTest(config, k8sCommands)
    TestScmmRepoProvider scmmRepoProvider = new TestScmmRepoProvider(config, new FileSystemUtils())
    TestScmmApiClient scmmApiClient = new TestScmmApiClient(config)

    List<Content.RepoCoordinate> expectedTargetRepos = [
            new Content.RepoCoordinate(namespace: "common", repo: "repo"),
            new Content.RepoCoordinate(namespace: "ns1a", repo: "repo1a1"),
            new Content.RepoCoordinate(namespace: "ns1a", repo: "repo1a2"),
            new Content.RepoCoordinate(namespace: "ns1b", repo: "repo1b1"),
            new Content.RepoCoordinate(namespace: "ns1b", repo: "repo1b2"),
            new Content.RepoCoordinate(namespace: "ns2a", repo: "repo2a1"),
            new Content.RepoCoordinate(namespace: "ns2a", repo: "repo2a2"),
            new Content.RepoCoordinate(namespace: "ns2b", repo: "repo2b1"),
            new Content.RepoCoordinate(namespace: "ns2b", repo: "repo2b2"),
            new Content.RepoCoordinate(namespace: "nonFolderBased", repo: "repo1"),
            new Content.RepoCoordinate(namespace: "nonFolderBased", repo: "repo2")
    ]

    List<Config.ContentSchema.ContentRepositorySchema> contentRepos = [
            // Non-folder-based repo writing to their own target
            new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), folderBased: false, target: 'nonFolderBased/repo1'),
            new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), folderBased: false, target: 'nonFolderBased/repo2', path: 'subPath'),
            
            // Same folder as in folderBasedRepos -> Should be combined
            new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo'),
            new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), folderBased: false, target: 'common/repo', path: 'subPath'),
            
            // Contains ftl
            new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), folderBased: true, templating: true),
            // Contains a templated file that should be ignored
            new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('folderBasedRepo2'), folderBased: true, path: 'subPath'),
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
            assertThat(new File(findRoot(repos), "${expected.namespace}/${expected.repo}/file")).exists().isFile()
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), folderBased: true, templating: true)
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo', username: 'user', password: 'pw')
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo(), ref: 'someTag', folderBased: false, target: 'common/tag'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo(), ref: '8bc1d1165468359b16d9771d4a9a3df26afc03e8', folderBased: false, target: 'common/ref'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo(), ref: 'someBranch', folderBased: false, target: 'common/branch')
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('', 'git-repo-different-default-branch'), target: 'common/default'),
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(findRoot(repos), "common/default/README.md")).exists().isFile()
        assertThat(new File(findRoot(repos), "common/default/README.md").text).contains("different")
    }

    @Test
    void 'Fails if commit refs does not exit'() {
        config.content.repos = [
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo(), ref: 'someTag', folderBased: false, target: 'common/tag'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo(), ref: 'does/not/exist', folderBased: true, target: 'does not matter'),
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('folderBasedRepo1'), ref: 'main', folderBased: true),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('folderBasedRepo2'), ref: 'main', folderBased: true, path: 'subPath'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), ref: 'main', folderBased: false, target: 'common/repo', path: 'subPath'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo'),
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
                actual.namespace == expected.namespace && actual.repo == expected.repo
            }
            assertThat(actual).withFailMessage(
                    "Could not find repo with namespace=${expected.namespace} and repo=${expected.repo} in ${actualTargetRepos}"
            ).hasSize(1)
            
            assertThat(actual[0].newContent.absolutePath).isEqualTo(
                    new File(findRoot(repos), "${expected.namespace}/${expected.repo}").absolutePath)
        }
    }

    @Test
    void 'Creates And pushes content repos, whole flow '() {
        config.content.repos = contentRepos
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'nonFolderBased/repo1'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
        def git = Git.cloneRepository().setURI(url).setBranch('main').setDirectory(repoFolder).call()


        verify(repo).create(eq(''), any(ScmmApiClient))

        def commitMsg = git.log().call().iterator().next().getFullMessage()
        assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

        assertThat(new File(repoFolder, "file").text).contains("nonFolderBasedRepo1")
        assertThat(new File(repoFolder, "nonFolderBasedRepo1")).exists().isFile()

        // Don't bother validating all other repos here.
        // If it works for the most complex one, the other ones will work as well.
        // The other tests are already asserting correct combining (including order) and parsing of the repos.
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), folderBased: false, target: 'common/repo', path: 'subPath')

        ]
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo', overrideMode: Config.OverrideMode.RESET),
        ]

        def resourceExistsAnswer = scmmApiClient.mockErrorResponse(409)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(resourceExistsAnswer)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(resourceExistsAnswer)

        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo'),
        ]
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), folderBased: false, target: 'common/repo', path: 'subPath', overrideMode: Config.OverrideMode.UPGRADE)
        ]

        def resourceExistsAnswer = scmmApiClient.mockErrorResponse(409)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(resourceExistsAnswer)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(resourceExistsAnswer)

        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), folderBased: false, target: 'common/repo', path: 'subPath')

        ]
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)

        def url = repo.getGitRepositoryUrl()
        def repoFolder = File.createTempDir('cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
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
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'main', folderBased: false, target: 'common/repo', overrideMode: Config.OverrideMode.INIT),
        ]

        def resourceExistsAnswer = scmmApiClient.mockErrorResponse(409)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(resourceExistsAnswer)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(resourceExistsAnswer)

        createContent().install()

        def folderAfterReset = File.createTempDir('second-cloned-repo')
        // clone repo, to ensure, changes in rmeote repo.
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

    String findRoot(List<Content.RepoCoordinate> repos) {
        def result = new File(repos.get(0).getNewContent().getParent()).getParent()
        return result;

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