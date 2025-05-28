package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import com.cloudogu.gitops.utils.*
import groovy.yaml.YamlSlurper
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor

import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

class ContentTest {

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

    List<Content.RepoCoordinates> expectedTargetRepos = [
            new Content.RepoCoordinates(namespace: "common", repo: "repo"),
            new Content.RepoCoordinates(namespace: "ns1a", repo: "repo1a1"),
            new Content.RepoCoordinates(namespace: "ns1a", repo: "repo1a2"),
            new Content.RepoCoordinates(namespace: "ns1b", repo: "repo1b1"),
            new Content.RepoCoordinates(namespace: "ns1b", repo: "repo1b2"),
            new Content.RepoCoordinates(namespace: "ns2a", repo: "repo2a1"),
            new Content.RepoCoordinates(namespace: "ns2a", repo: "repo2a2"),
            new Content.RepoCoordinates(namespace: "ns2b", repo: "repo2b1"),
            new Content.RepoCoordinates(namespace: "ns2b", repo: "repo2b2"),
            new Content.RepoCoordinates(namespace: "nonFolderBased", repo: "repo1"),
            new Content.RepoCoordinates(namespace: "nonFolderBased", repo: "repo2")
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
    
    @Test
    void 'deploys image pull secrets'() {
        config.content.examples = true
        config.registry.createImagePullSecrets = true

        createContent().install()

        assertRegistrySecrets('reg-user', 'reg-pw')
    }

    @Test
    void 'deploys image pull secrets from read-only vars'() {
        config.content.examples = true
        config.registry.createImagePullSecrets = true
        config.registry.readOnlyUsername = 'other-user'
        config.registry.readOnlyPassword = 'other-pw'

        createContent().install()

        assertRegistrySecrets('other-user', 'other-pw')
    }

    @Test
    void 'deploys additional image pull secrets for proxy registry'() {
        config.content.examples = true
        config.registry.createImagePullSecrets = true
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

        k8sClient.commandExecutorForTest.assertExecuted('kubectl create namespace foo-example-apps-staging')
        k8sClient.commandExecutorForTest.assertExecuted('kubectl create namespace foo-example-apps-production')
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-example-apps-staging' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
        k8sClient.commandExecutorForTest.assertExecuted(
                'kubectl create secret docker-registry proxy-registry -n foo-example-apps-production' +
                        ' --docker-server proxy-url --docker-username proxy-user --docker-password proxy-pw')
    }

    @Test
    void 'Combines content repos successfully'() {

        config.content.repos = contentRepos

        def repos = createContent().cloneContentRepos()

        expectedTargetRepos.each { expected ->
            assertThat(new File(repos, "${expected.namespace}/${expected.repo}/file")).exists().isFile()
        }
        
        assertThat(new File(repos, "common/repo/file").text).contains("folderBasedRepo2") // Last repo "wins"

        assertThat(new File(repos, "common/repo/folderBasedRepo1")).exists().isFile()
        assertThat(new File(repos, "common/repo/folderBasedRepo2")).exists().isFile()
        assertThat(new File(repos, "common/repo/nonFolderBasedRepo1")).exists().isFile()
        assertThat(new File(repos, "common/repo/nonFolderBasedRepo2")).exists().isFile()

        // Assert Templating
        assertThat(new File(repos, "common/repo/some.yaml")).exists() 
        assertThat(new File(repos, "common/repo/some.yaml").text).contains("namePrefix: foo-") 
        // Assert not templating for this folder-based repo
        assertThat(new File(repos, "common/repo/someOther.yaml.ftl")).exists() 
        assertThat(new File(repos, "common/repo/someOther.yaml.ftl").text).contains('namePrefix: ${config.application.namePrefix}') 
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
    void 'Checks out commit refs and tags for content repos'() {
        config.content.repos = [
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo1'), ref: 'someTag', folderBased: false, target: 'common/tag'),
                new Config.ContentSchema.ContentRepositorySchema(url: createContentRepo('nonFolderBasedRepo2'), ref: '56d2e3f4b7c95d5645c823f7be8ea6f8a853ac40', folderBased: false, target: 'common/ref')
        ]

        def repos = createContent().cloneContentRepos()

        assertThat(new File(repos, "common/tag/README.md")).exists().isFile()
        assertThat(new File(repos, "common/ref/README.md")).exists().isFile()
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

        assertThat(new File(repos, "common/repo/file").text).contains("nonFolderBasedRepo1") // Last repo "wins"
    }

    @Test
    void 'Parses Repo coordinates'() {

        config.content.repos = contentRepos

        def content = createContent()

        def repos = content.cloneContentRepos()
        def actualTargetRepos = content.parseSrcRepos(repos)

        assertThat(actualTargetRepos).hasSameSizeAs(expectedTargetRepos)

        expectedTargetRepos.each { expected ->
            
            def actual = actualTargetRepos.findAll { actual ->
                actual.namespace == expected.namespace && actual.repo == expected.repo
            }
            assertThat(actual).withFailMessage(
                    "Could not find repo with namespace=${expected.namespace} and repo=${expected.repo} in ${actualTargetRepos}"
            ).hasSize(1)
            
            assertThat(actual[0].newContent.absolutePath).isEqualTo(
                    new File(repos, "${expected.namespace}/${expected.repo}").absolutePath)
        }
    }

    @Test
    void 'Creates And pushes content repos'() {
        config.content.repos = contentRepos
        def response = scmmApiClient.mockSuccessfulResponse(201)
        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response)

        createContent().install()

        def expectedRepo = 'common/repo'
        def repo = scmmRepoProvider.getRepo(expectedRepo)
        def repoFolder = repo.absoluteLocalRepoTmpDir
        
        verify(repo).create(eq(''), any(ScmmApiClient))

        def git = Git.open(new File(repoFolder))
        def commitMsg = git.log().call().iterator().next().getFullMessage()
        assertThat(commitMsg).isEqualTo("Initialize content repo ${expectedRepo}".toString())

        assertThat(new File(repoFolder, "file").text).contains("folderBasedRepo2") // Last repo "wins"

        assertThat(new File(repoFolder, "folderBasedRepo1")).exists().isFile()
        assertThat(new File(repoFolder, "folderBasedRepo2")).exists().isFile()
        assertThat(new File(repoFolder, "nonFolderBasedRepo1")).exists().isFile()
        assertThat(new File(repoFolder, "nonFolderBasedRepo2")).exists().isFile()
        
        // Don't bother validating all other repos here. 
        // If it works for the most complex one, the other ones will work as well. 
        // The other tests are already asserting correct combining (including order) and parsing of the repos.
    }

    static String createContentRepo(String srcPath) {
        // The bare repo works as the "remote"
        def bareRepoDir = File.createTempDir('gitops-playground-test-content-repo')
        bareRepoDir.deleteOnExit()
        // init with bare repo
        FileUtils.copyDirectory(new File(System.getProperty("user.dir") + "/src/test/groovy/com/cloudogu/gitops/utils/data/git-repository/"), bareRepoDir)
        def bareRepoUri = 'file://' + bareRepoDir.absolutePath
        println("Repo $srcPath: bare repo $bareRepoUri")

        // Add srcPath to bare repo
        def tempRepo = File.createTempDir('gitops-playground-temp-repo')
        println("Repo $srcPath: cloned bare repo to $tempRepo")
        def git = Git.cloneRepository()
                .setURI(bareRepoUri)
                .setBranch('main')
                .setDirectory(tempRepo)
                .call()

        FileUtils.copyDirectory(new File(System.getProperty("user.dir") + '/src/test/groovy/com/cloudogu/gitops/utils/data/contentRepos/' + srcPath), tempRepo)

        git.add().addFilepattern(".").call()

        // Avoid complications with local developer's git config, e.g. when  git config --global commit.gpgSign true
        SystemReader.getInstance().userConfig.clear()
        git.commit().setMessage("Initialize with $srcPath").call()
        git.push().call()
        tempRepo.delete()

        return bareRepoUri
    }


    private void assertRegistrySecrets(String regUser, String regPw) {
        List expectedNamespaces = ["foo-example-apps-staging", "foo-example-apps-production"]
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