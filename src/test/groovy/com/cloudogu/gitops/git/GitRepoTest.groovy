package com.cloudogu.gitops.git

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.AccessRole
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.providers.Scope
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.git.ScmManagerMock
import com.cloudogu.gitops.utils.git.TestGitRepoFactory

class GitRepoTest {


    public static final String expectedNamespace = "namespace"
    public static final String expectedRepo = "repo"
    Config config = Config.fromMap([
            application: [
                    gitName : "Cloudogu",
                    gitEmail: "hello@cloudogu.com"
            ],
            scm        : [
                    scmManager: [
                            username: "dont-care-username",
                            password: "dont-care-password"
                    ]
            ]
    ])

    TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, new FileSystemUtils())

    @Mock
    GitProvider gitProvider

    ScmManagerMock scmManagerMock

    @BeforeEach
    void setup() {
        scmManagerMock = new ScmManagerMock()
    }


    @Test
    void "writes file"() {
        def repo = getRepo("", scmManagerMock)
        repo.writeFile("test.txt", "the file's content")

        def expectedFile = new File("$repo.absoluteLocalRepoTmpDir/test.txt")
        assertThat(expectedFile.getText()).is("the file's content")
    }

    @Test
    void "overwrites file"() {
        def repo = getRepo("", scmManagerMock)
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
        def repo = getRepo("", scmManagerMock)
        def tempDir = repo.absoluteLocalRepoTmpDir
        repo.writeFile("subdirectory/test.txt", "the file's content")

        def expectedFile = new File("$tempDir/subdirectory/test.txt")
        assertThat(expectedFile.getText()).is("the file's content")
    }

    @Test
    void "throws error when directory conflicts with existing file"() {
        def repo = getRepo("", scmManagerMock)
        def tempDir = repo.absoluteLocalRepoTmpDir
        new File("$tempDir/test.txt").mkdir()

        shouldFail(FileNotFoundException) {
            repo.writeFile("test.txt", "the file's content")
        }
    }

    @Test
    void 'Creates repo with empty name-prefix'() {
        def repo = getRepo('expectedRepoTarget', scmManagerMock)
        assertThat(repo.repoTarget).isEqualTo('expectedRepoTarget')
    }

    @Test
    void 'Creates repo with name-prefix'() {
        config.application.namePrefix = 'abc-'
        def repo = getRepo('expectedRepoTarget', scmManagerMock)
        assertThat(repo.repoTarget).isEqualTo('abc-expectedRepoTarget')
    }

    @Test
    void 'Creates repo with name-prefix when in namespace 3rd-party-deps'() {
        config.application.namePrefix = 'abc-'
        def repo = getRepo("${GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/foo", scmManagerMock)
        assertThat(repo.repoTarget).isEqualTo("${config.application.namePrefix}${GitRepo.NAMESPACE_3RD_PARTY_DEPENDENCIES}/foo".toString())
    }

    @Test
    void 'Clones and checks out main'() {
        def repo = getRepo("", scmManagerMock)

        repo.cloneRepo()
        def HEAD = new File(repo.absoluteLocalRepoTmpDir, '.git/HEAD')
        assertThat(HEAD.text).isEqualTo("ref: refs/heads/main\n")
        assertThat(new File(repo.absoluteLocalRepoTmpDir, 'README.md')).exists()
    }

    @Test
    void 'pushes changes to remote directory'() {
        def repo = getRepo("", scmManagerMock)

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
        assertThat(commits[0].committerIdent.name).contains("Cloudogu - GOP v")

        List<Ref> tags = Git.open(new File(repo.absoluteLocalRepoTmpDir)).tagList().call()
        assertThat(tags.size()).isEqualTo(0)
    }

    @Test
    void 'pushes changes to remote directory with tag'() {
        def repo = getRepo("", scmManagerMock)
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
    void 'creates repository and sets permission when new and username present'() {

        def repoTarget = "foo/bar"
        def repo = getRepo(repoTarget, scmManagerMock)
        scmManagerMock.nextCreateResults = [true]            // simulate "new repo"
        scmManagerMock.gitOpsUsername = 'foo-gitops'         // username available

        def created = repo.createRepositoryAndSetPermission('testdescription', true)

        assertThat(created).isTrue()

        // Verify that repo was created
        assertThat(scmManagerMock.createdRepos).containsExactly(repoTarget)

        // Verify permission call
        assertThat(scmManagerMock.permissionCalls).hasSize(1)
        def call = scmManagerMock.permissionCalls[0]
        assertThat(call.repoTarget).isEqualTo(repoTarget)
        assertThat(call.principal).isEqualTo('foo-gitops')
        assertThat(call.role).isEqualTo(AccessRole.WRITE)
        assertThat(call.scope).isEqualTo(Scope.USER)
    }

    @Test
    void 'does not set permission when no GitOps username is configured'() {
        def repoTarget = "foo/bar"
        def scmManagerMock = new ScmManagerMock()
        def repo = getRepo(repoTarget, scmManagerMock)

        scmManagerMock.nextCreateResults = [true]            // repo is new
        scmManagerMock.gitOpsUsername = null                 // no username

        def created = repo.createRepositoryAndSetPermission('desc', true)

        assertThat(created).isTrue()

        // Repo created
        assertThat(scmManagerMock.createdRepos).containsExactly(repoTarget)

        // No permission calls because username missing
        assertThat(scmManagerMock.permissionCalls).isEmpty()
    }


    private GitRepo getRepo(String repoTarget = "${expectedNamespace}/${expectedRepo}", ScmManagerMock scmManagerMock) {
        return repoProvider.getRepo(repoTarget, scmManagerMock)
    }
}