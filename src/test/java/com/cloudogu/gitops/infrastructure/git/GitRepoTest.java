package com.cloudogu.gitops.infrastructure.git;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.providers.AccessRole;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.infrastructure.git.providers.Scope;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.utils.FileSystemUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitRepoTest {

	public static final String expectedNamespace = "namespace";
	public static final String expectedRepo = "repo";

	private final Config config = Config.fromMap(Map.of(
		"application", Map.of(
			"gitName", "Cloudogu",
			"gitEmail", "hello@cloudogu.com"
		),
		"scm", Map.of(
			"scmManager", Map.of(
				"username", "dont-care-username",
				"password", "dont-care-password"
			)
		)
	));

	private final TestGitRepoFactory repoProvider = new TestGitRepoFactory(config, new FileSystemUtils());

	@Mock
	GitProvider gitProvider;

	private ScmManagerProviderMock scmManagerMock;

	@BeforeEach
	void setup() {
		scmManagerMock = new ScmManagerProviderMock();
	}

	@Test
	void writesFile() throws IOException {
		GitRepo repo = getRepo("", scmManagerMock);
		repo.writeFile("test.txt", "the file's content");

		File expectedFile = new File(repo.getAbsoluteLocalRepoTmpDir(), "test.txt");
		assertThat(Files.readString(expectedFile.toPath())).isEqualTo("the file's content");
	}

	@Test
	void overwritesFile() throws IOException {
		GitRepo repo = getRepo("", scmManagerMock);
		String tempDir = repo.getAbsoluteLocalRepoTmpDir();

		File existingFile = new File(tempDir, "already-exists.txt");
		existingFile.createNewFile();
		Files.writeString(existingFile.toPath(), "already existing content");

		repo.writeFile("already-exists.txt", "overwritten content");

		File expectedFile = new File(tempDir, "already-exists.txt");
		assertThat(Files.readString(expectedFile.toPath())).isEqualTo("overwritten content");
	}

	@Test
	void writesFileAndCreatesSubdirectory() throws IOException {
		GitRepo repo = getRepo("", scmManagerMock);
		String tempDir = repo.getAbsoluteLocalRepoTmpDir();
		repo.writeFile("subdirectory/test.txt", "the file's content");

		File expectedFile = new File(tempDir, "subdirectory/test.txt");
		assertThat(Files.readString(expectedFile.toPath())).isEqualTo("the file's content");
	}

	@Test
	void throwsErrorWhenDirectoryConflictsWithExistingFile() {
		GitRepo repo = getRepo("", scmManagerMock);
		String tempDir = repo.getAbsoluteLocalRepoTmpDir();
		new File(tempDir, "test.txt").mkdir();

		assertThrows(FileNotFoundException.class, () -> repo.writeFile("test.txt", "the file's content"));
	}

	@Test
	void usesRepositoryTargetAsProvided() {
		config.getApplication().setNamePrefix("abc-");

		GitRepo repo = new GitRepo(config, scmManagerMock, "expectedRepoTarget", new FileSystemUtils());

		assertThat(repo.getRepoTarget()).isEqualTo("expectedRepoTarget");
	}

	@Test
	void clonesAndChecksOutMain() throws GitAPIException, IOException {
		GitRepo repo = getRepo("", scmManagerMock);

		repo.cloneRepo();
		File head = new File(repo.getAbsoluteLocalRepoTmpDir(), ".git/HEAD");
		assertThat(Files.readString(head.toPath())).isEqualTo("ref: refs/heads/main\n");
		assertThat(new File(repo.getAbsoluteLocalRepoTmpDir(), "README.md")).exists();
	}

	@Test
	void pushesChangesToRemoteDirectory() throws GitAPIException, IOException {
		GitRepo repo = getRepo("", scmManagerMock);

		repo.cloneRepo();
		File readme = new File(repo.getAbsoluteLocalRepoTmpDir(), "README.md");
		Files.writeString(readme.toPath(), "This text should be in the readme afterwards");
		repo.commitAndPush("The commit message");

		List<RevCommit> commits = new ArrayList<>();
		Git.open(new File(repo.getAbsoluteLocalRepoTmpDir()))
			.log().setMaxCount(1).all().call().forEach(commits::add);
		assertThat(commits.size()).isEqualTo(1);
		assertThat(commits.get(0).getFullMessage()).isEqualTo("The commit message");
		assertThat(commits.get(0).getAuthorIdent().getEmailAddress()).isEqualTo("hello@cloudogu.com");
		assertThat(commits.get(0).getAuthorIdent().getName()).isEqualTo("Cloudogu");
		assertThat(commits.get(0).getCommitterIdent().getEmailAddress()).isEqualTo("hello@cloudogu.com");
		assertThat(commits.get(0).getCommitterIdent().getName()).contains("Cloudogu - GOP v");

		List<Ref> tags = Git.open(new File(repo.getAbsoluteLocalRepoTmpDir())).tagList().call();
		assertThat(tags.size()).isEqualTo(0);
	}

	@Test
	void pushesChangesToRemoteDirectoryWithTag() throws GitAPIException, IOException {
		GitRepo repo = getRepo("", scmManagerMock);
		String expectedTag = "1.0";

		repo.cloneRepo();
		File readme = new File(repo.getAbsoluteLocalRepoTmpDir(), "README.md");
		Files.writeString(readme.toPath(), "This text should be in the readme afterwards");
		// Create existing tag to test for idempotence
		Git.open(new File(repo.getAbsoluteLocalRepoTmpDir())).tag().setName(expectedTag).call();

		repo.commitAndPush("The commit message", expectedTag);

		List<Ref> tags = Git.open(new File(repo.getAbsoluteLocalRepoTmpDir())).tagList().call();
		assertThat(tags.size()).isEqualTo(1);
		assertThat(tags.get(0).getName()).isEqualTo("refs/tags/" + expectedTag);
		// It would be a good idea to check if the git tag is set on the commit.
		// However, it's extremely complicated with jgit
		// The "official" example code throws an exception here: Ref peeledRef = repository.getRefDatabase().peel(ref)
		// https://github.com/centic9/jgit-cookbook/blob/d923e18b2ce2e55761858fd2e8e402dd252e0766/src/main/java/org/dstadler/jgit/porcelain/ListTags.java
		// 🤷
	}

	@Test
	void createsRepositoryAndSetsPermissionWhenNewAndUsernamePresent() {
		String repoTarget = "foo/bar";
		GitRepo repo = getRepo(repoTarget, scmManagerMock);
		scmManagerMock.setNextCreateResults(new ArrayList<>(List.of(true))); // simulate "new repo"
		scmManagerMock.setGitOpsUsername("foo-gitops"); // username available

		boolean created = repo.createRepositoryAndSetPermission("testdescription", true);

		assertThat(created).isTrue();

		// Verify that repo was created
		assertThat(scmManagerMock.getCreatedRepos()).containsExactly(repoTarget);

		// Verify permission call
		assertThat(scmManagerMock.getPermissionCalls()).hasSize(1);
		Map<String, Object> call = scmManagerMock.getPermissionCalls().get(0);
		assertThat(call.get("repoTarget")).isEqualTo(repoTarget);
		assertThat(call.get("principal")).isEqualTo("foo-gitops");
		assertThat(call.get("role")).isEqualTo(AccessRole.WRITE);
		assertThat(call.get("scope")).isEqualTo(Scope.USER);
	}

	@Test
	void doesNotSetPermissionWhenNoGitOpsUsernameIsConfigured() {
		String repoTarget = "foo/bar";
		ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();
		GitRepo repo = getRepo(repoTarget, scmManagerMock);

		scmManagerMock.setNextCreateResults(new ArrayList<>(List.of(true))); // repo is new
		scmManagerMock.setGitOpsUsername(null); // no username

		boolean created = repo.createRepositoryAndSetPermission("desc", true);

		assertThat(created).isTrue();

		// Repo created
		assertThat(scmManagerMock.getCreatedRepos()).containsExactly(repoTarget);

		// No permission calls because username missing
		assertThat(scmManagerMock.getPermissionCalls()).isEmpty();
	}

	private GitRepo getRepo(String repoTarget, ScmManagerProviderMock scmManagerMock) {
		return repoProvider.create(repoTarget, scmManagerMock);
	}

	@SuppressWarnings("unused")
	private GitRepo getRepo(ScmManagerProviderMock scmManagerMock) {
		return getRepo(expectedNamespace + "/" + expectedRepo, scmManagerMock);
	}
}
