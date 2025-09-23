package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.config.ScmCentralSchema
import com.cloudogu.gitops.git.GitRepo
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import retrofit2.Call

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.*

class GitRepoTest {


    public static final String expectedNamespace = "namespace"
    public static final String expectedRepo = "repo"
    Config config = new Config(
            application: new Config.ApplicationSchema(
                    gitName: "Cloudogu",
                    gitEmail: "hello@cloudogu.com",)
            ,
            scmm: new ScmCentralSchema.ScmmCentralConfig(
                    username: "dont-care-username",
                    password: "dont-care-password",
//                    gitOpsUsername: 'foo-gitops' // TODO:
            ))
    TestGitRepoFactory scmmRepoProvider = new TestGitRepoFactory(config, new FileSystemUtils())
    TestScmmApiClient scmmApiClient = new TestScmmApiClient(config)
    Call<Void>  response201 = TestScmmApiClient.mockSuccessfulResponse(201)
    Call<Void> response409 = scmmApiClient.mockErrorResponse(409)
    Call<Void> response500 = scmmApiClient.mockErrorResponse(500)
    
    @Test
    void "writes file"() {
        def repo = createRepo()
        repo.writeFile("test.txt", "the file's content")

        def expectedFile = new File("$repo.absoluteLocalRepoTmpDir/test.txt")
        assertThat(expectedFile.getText()).is("the file's content")
    }

    @Test
    void "overwrites file"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir

        def existingFile = new File("$tempDir/already-exists.txt")
        existingFile.createNewFile()
        existingFile.text = "already existing content"

        repo.writeFile("already-exists.txt", "overwritten content")

        def expectedFile = new File("$tempDir/already-exists.txt")
        assertThat(expectedFile.getText()).is("overwritten content")
    }

    @Test
    void "writes file and creates subdirectory"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir
        repo.writeFile("subdirectory/test.txt", "the file's content")

        def expectedFile = new File("$tempDir/subdirectory/test.txt")
        assertThat(expectedFile.getText()).is("the file's content")
    }

    @Test
    void "throws error when directory conflicts with existing file"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir
        new File("$tempDir/test.txt").mkdir()

        shouldFail(FileNotFoundException) {
            repo.writeFile("test.txt", "the file's content")
        }
    }

    @Test
    void 'Creates repo with empty name-prefix'() {
        def repo = createRepo('expectedRepoTarget')
        assertThat(repo.scmmRepoTarget).isEqualTo('expectedRepoTarget')
    }

    @Test
    void 'Creates repo with name-prefix'() {
        config.application.namePrefix = 'abc-'
        def repo = createRepo('expectedRepoTarget')
        assertThat(repo.scmmRepoTarget).isEqualTo('abc-expectedRepoTarget')
    }

    @Test
    void 'Creates repo without name-prefix when in namespace 3rd-party-deps'() {
        config.application.namePrefix = 'abc-'
        def repo = createRepo("${ScmmRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/foo")
        assertThat(repo.scmmRepoTarget).isEqualTo("${ScmmRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/foo".toString())
    }

    @Test
    void 'Clones and checks out main'() {
        def repo = createRepo()

        repo.cloneRepo()
        def HEAD = new File(repo.absoluteLocalRepoTmpDir, '.git/HEAD')
        assertThat(HEAD.text).isEqualTo("ref: refs/heads/main\n")
        assertThat(new File(repo.absoluteLocalRepoTmpDir, 'README.md')).exists()
    }

    @Test
    void 'pushes changes to remote directory'() {
        def repo = createRepo()

        repo.cloneRepo()
        def readme = new File(repo.absoluteLocalRepoTmpDir, 'README.md')
        readme.text = 'This text should be in the readme afterwards'
        repo.commitAndPush("The commit message")

        def commits = Git.open(new File(repo.absoluteLocalRepoTmpDir)).log().setMaxCount(1).all().call().collect()
        assertThat(commits.size()).isEqualTo(1)
        assertThat(commits[0].fullMessage).isEqualTo("The commit message")
        assertThat(commits[0].authorIdent.emailAddress).isEqualTo('hello@cloudogu.com')
        assertThat(commits[0].authorIdent.name).isEqualTo('Cloudogu')
        assertThat(commits[0].committerIdent.emailAddress).isEqualTo('hello@cloudogu.com')
        assertThat(commits[0].committerIdent.name).isEqualTo("Cloudogu")

        List<Ref> tags = Git.open(new File(repo.absoluteLocalRepoTmpDir)).tagList().call()
        assertThat(tags.size()).isEqualTo(0)
    }

    @Test
    void 'pushes changes to remote directory with tag'() {
        def repo = createRepo()
        def expectedTag = '1.0'

        repo.cloneRepo()
        def readme = new File(repo.absoluteLocalRepoTmpDir, 'README.md')
        readme.text = 'This text should be in the readme afterwards'
        // Create existing tag to test for idempotence
        Git.open(new File(repo.absoluteLocalRepoTmpDir)).tag().setName(expectedTag).call()

        repo.commitAndPush("The commit message", expectedTag)


        List<Ref> tags = Git.open(new File(repo.absoluteLocalRepoTmpDir)).tagList().call()
        assertThat(tags.size()).isEqualTo(1)
        assertThat(tags[0].name).isEqualTo("refs/tags/$expectedTag".toString())
        // It would be a good idea to check if the git tag is set on the commit. 
        // However, it's extremely complicated with jgit
        // The "official" example code throws an exception here: Ref peeledRef = repository.getRefDatabase().peel(ref)
        // https://github.com/centic9/jgit-cookbook/blob/d923e18b2ce2e55761858fd2e8e402dd252e0766/src/main/java/org/dstadler/jgit/porcelain/ListTags.java
        // 🤷
    }
    
    @Test
    void 'Create repo'() {
        def repo = createRepo()

        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response201)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response201)
        
        repo.create('description', scmmApiClient)

        assertCreatedRepo()
    }

    @Test
    void 'Create repo: Ignores existing Repos'() {
        def repo = createRepo()

        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response409)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response201)
        
        repo.create('description', scmmApiClient)

        assertCreatedRepo()
    }
    
    @Test
    void 'Create repo: Ignore existing Repos'() {
        def repo = createRepo()

        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response409)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response201)
        
        repo.create('description', scmmApiClient)

        assertCreatedRepo()
    }
    
    @Test
    void 'Create repo: Ignore existing Permissions'() {
        def repo = createRepo()

        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response201)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response409)

        repo.create('description', scmmApiClient)

        assertCreatedRepo()
    }

    @Test
    void 'Create repo: Handle failures to SCMM-API for Repos'() {
        def repo = createRepo()

        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response500)

        def exception = shouldFail(RuntimeException) {
            repo.create('description', scmmApiClient)
        }
        assertThat(exception.message).startsWith('Could not create Repository')
        assertThat(exception.message).contains(expectedNamespace)
        assertThat(exception.message).contains(expectedRepo)
        assertThat(exception.message).contains('500')
    }

    @Test
    void 'Create repo: Handle failures to SCMM-API for Permissions'() {
        def repo = createRepo()

        when(scmmApiClient.repositoryApi.create(any(Repository), anyBoolean())).thenReturn(response201)
        when(scmmApiClient.repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(response500)

        def exception = shouldFail(RuntimeException) {
            repo.create('description', scmmApiClient)
        }
        assertThat(exception.message).startsWith("Could not create Permission for repo $expectedNamespace/$expectedRepo")
        assertThat(exception.message).contains('foo-gitops')
        assertThat(exception.message).contains(Permission.Role.WRITE.name())
        assertThat(exception.message).contains('500')
    }

    protected void assertCreatedRepo() {
        def repoCreateArgument = ArgumentCaptor.forClass(Repository)
        verify(scmmApiClient.repositoryApi, times(1)).create(repoCreateArgument.capture(), eq(true))
        assertThat(repoCreateArgument.allValues[0].namespace).isEqualTo(expectedNamespace)
        assertThat(repoCreateArgument.allValues[0].name).isEqualTo(expectedRepo)
        assertThat(repoCreateArgument.allValues[0].description).isEqualTo('description')

        def permissionCreateArgument = ArgumentCaptor.forClass(Permission)
        verify(scmmApiClient.repositoryApi, times(1)).createPermission(anyString(), anyString(), permissionCreateArgument.capture())
        assertThat(permissionCreateArgument.allValues[0].name).isEqualTo('foo-gitops')
        assertThat(permissionCreateArgument.allValues[0].role).isEqualTo(Permission.Role.WRITE)
    }
    
    private GitRepo createRepo(String repoTarget = "${expectedNamespace}/${expectedRepo}") {
        return scmmRepoProvider.getRepo(repoTarget)
    }
}
