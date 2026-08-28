package com.cloudogu.gitops.application.content;

import com.cloudogu.gitops.application.context.ContextBuilder;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.orchestration.GitHandler;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.infrastructure.deployment.Deployer;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.testhelper.git.GitHandlerForTests;
import com.cloudogu.gitops.testhelper.git.ScmManagerProviderMock;
import com.cloudogu.gitops.testhelper.git.TestGitRepoFactory;
import com.cloudogu.gitops.testhelper.git.TestScmManagerApiClient;
import com.cloudogu.gitops.tools.common.HelmChartConfig;
import com.cloudogu.gitops.tools.core.Jenkins;
import com.cloudogu.gitops.utils.FileSystemUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.cloudogu.gitops.config.Config.ContentRepoType;
import static com.cloudogu.gitops.config.Config.OverwriteMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@EnableKubernetesMockClient(crud = true)
@SuppressWarnings("unchecked")
class ContentLoaderTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {
	};
	private static final List<File> foldersToDelete = new ArrayList<>();

	private final Config config = createConfig();
	private final K8sClient k8sClient = new K8sClient();
	private final TestGitRepoFactory scmmRepoProvider = new TestGitRepoFactory(config, new FileSystemUtils());
	private final TestScmManagerApiClient scmmApiClient = new TestScmManagerApiClient(config);
	private final Jenkins jenkins = mock(Jenkins.class);
	private final ScmManagerProviderMock scmManagerMock = new ScmManagerProviderMock();
	private final GitHandler gitHandler = new GitHandlerForTests(scmManagerMock);
	private final Deployer deployer = mock(Deployer.class);
	private final RepositoryWorkspace repositoryWorkspace = mock(RepositoryWorkspace.class);
	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();

	KubernetesClient client;

	@TempDir
	File tmpDir;

	private final List<ContentLoader.RepoCoordinate> expectedTargetRepos = List.of(
		repoCoordinate("common", "repo"),
		repoCoordinate("ns1a", "repo1a1"),
		repoCoordinate("ns1a", "repo1a2"),
		repoCoordinate("ns1b", "repo1b1"),
		repoCoordinate("ns1b", "repo1b2"),
		repoCoordinate("ns2a", "repo2a1"),
		repoCoordinate("ns2a", "repo2a2"),
		repoCoordinate("ns2b", "repo2b1"),
		repoCoordinate("ns2b", "repo2b2"),
		repoCoordinate("copy", "repo1"),
		repoCoordinate("copy", "repo2")
	);

	private final List<Config.ContentSchema.ContentRepositorySchema> contentRepos = List.of(
		repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("copy/repo1");
		}),
		repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo2"));
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("copy/repo2");
			repo.setPath("subPath");
		}),
		repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("common/repo");
		}),
		repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo2"));
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("common/repo");
			repo.setPath("subPath");
		}),
		repository(repo -> {
			repo.setUrl(createContentRepo("folderBasedRepo1"));
			repo.setType(ContentRepoType.FOLDER_BASED);
			repo.setTemplating(true);
		}),
		repository(repo -> {
			repo.setUrl(createContentRepo("folderBasedRepo2"));
			repo.setType(ContentRepoType.FOLDER_BASED);
			repo.setPath("subPath");
		})
	);

	@AfterAll
	static void cleanFolders() {
		for (File folder : foldersToDelete) {
			FileUtils.deleteQuietly(folder);
		}
	}

	@Disabled("TODO: Does not run on Jenkins: Caused by: java.net.UnknownHostException: kubernetes.default.svc: Name or service not known")
	@Test
	void deploysImagePullSecrets() {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getContent().setNamespaces(List.of("example-apps-staging", "example-apps-production"));

		install(createContent(config), config);

		assertRegistrySecrets("reg-user", "reg-pw");
	}

	@Disabled("TODO: Does not run on Jenkins: Caused by: java.net.UnknownHostException: kubernetes.default.svc: Name or service not known")
	@Test
	void deploysImagePullSecretsFromReadOnlyVars() {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getContent().setNamespaces(List.of("example-apps-staging", "example-apps-production"));
		config.getRegistry().setReadOnlyUsername("other-user");
		config.getRegistry().setReadOnlyPassword("other-pw");

		install(createContent(config), config);

		assertRegistrySecrets("other-user", "other-pw");
	}

	@Disabled("TODO: Does not run on Jenkins: Caused by: java.net.UnknownHostException: kubernetes.default.svc: Name or service not known")
	@Test
	void deploysAdditionalImagePullSecretsForProxyRegistry() {
		config.getRegistry().setCreateImagePullSecrets(true);
		config.getContent().setNamespaces(List.of("example-apps-staging", "example-apps-production"));
		config.getRegistry().setTwoRegistries(true);
		config.getRegistry().setProxyUrl("proxy-url");
		config.getRegistry().setProxyUsername("proxy-user");
		config.getRegistry().setProxyPassword("proxy-pw");

		install(createContent(config), config);

		assertRegistrySecrets("reg-user", "reg-pw");
	}

	@Test
	void combinesContentReposSuccessfully() throws Exception {
		config.getContent().setRepos(contentRepos);

		List<ContentLoader.RepoCoordinate> repos = cloneContentRepos(createContent(config), config);

		for (ContentLoader.RepoCoordinate expected : expectedTargetRepos) {
			assertThat(new File(findRoot(repos), expected.getNamespace() + "/" + expected.getRepoName() + "/file"))
				.exists()
				.isFile();
		}

		assertThat(Files.readString(new File(findRoot(repos), "common/repo/file").toPath()))
			.contains("folderBasedRepo2");

		assertThat(new File(findRoot(repos), "common/repo/folderBasedRepo1")).exists().isFile();
		assertThat(new File(findRoot(repos), "common/repo/folderBasedRepo2")).exists().isFile();
		assertThat(new File(findRoot(repos), "common/repo/copyRepo1")).exists().isFile();
		assertThat(new File(findRoot(repos), "common/repo/copyRepo2")).exists().isFile();

		assertThat(new File(findRoot(repos), "common/repo/some.yaml")).exists();
		assertThat(Files.readString(new File(findRoot(repos), "common/repo/some.yaml").toPath()))
			.contains("namePrefix: foo-");
		assertThat(new File(findRoot(repos), "common/repo/someOther.yaml.ftl")).exists();
		assertThat(Files.readString(new File(findRoot(repos), "common/repo/someOther.yaml.ftl").toPath()))
			.contains("namePrefix: ${config.application.namePrefix}");
	}

	@Test
	void supportsContentVariables() throws Exception {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("folderBasedRepo1"));
			repo.setType(ContentRepoType.FOLDER_BASED);
			repo.setTemplating(true);
		})));
		config.getContent().getVariables().put("someapp", Map.of("somevalue", "this is a custom variable"));

		List<ContentLoader.RepoCoordinate> repos = cloneContentRepos(createContent(config), config);

		assertThat(new File(findRoot(repos), "common/repo/some.yaml")).exists();
		assertThat(Files.readString(new File(findRoot(repos), "common/repo/some.yaml").toPath()))
			.contains("namePrefix: foo-");
		assertThat(Files.readString(new File(findRoot(repos), "common/repo/some.yaml").toPath()))
			.contains("myvar: this is a custom variable");
	}

	@Test
	void authenticatesContentRepos() throws Exception {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("common/repo");
			repo.setCredentials(new Credentials("user", "pw"));
		})));

		ContentLoaderForTest content = createContent(config);
		cloneContentRepos(content, config);

		ArgumentCaptor<UsernamePasswordCredentialsProvider> captor =
			ArgumentCaptor.forClass(UsernamePasswordCredentialsProvider.class);
		verify(content.cloneSpy).setCredentialsProvider(captor.capture());

		UsernamePasswordCredentialsProvider value = captor.getValue();
		assertThat(readPrivateField(value, "username")).isEqualTo("user");
		assertThat((char[]) readPrivateField(value, "password")).isEqualTo("pw".toCharArray());
	}

	@Test
	@DisplayName("Authenticates content Repos with secret")
	void authenticatesContentReposWithSecret() throws Exception {
		k8sClient.setClient(client);
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
			.build();

		k8sClient.getClient().secrets()
				 .inNamespace("default")
				 .resource(secret)
				 .create();

		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("common/repo");
			repo.setCredentials(new Credentials(null, null, "secret-test-name", "default"));
		})));

		ContentLoaderForTest content = createContent(config);
		cloneContentRepos(content, config);

		ArgumentCaptor<UsernamePasswordCredentialsProvider> captor =
			ArgumentCaptor.forClass(UsernamePasswordCredentialsProvider.class);
		verify(content.cloneSpy).setCredentialsProvider(captor.capture());

		UsernamePasswordCredentialsProvider value = captor.getValue();
		assertThat(readPrivateField(value, "username")).isEqualTo("admin");
		assertThat((char[]) readPrivateField(value, "password")).isEqualTo("s3cr3t".toCharArray());
	}

	@Test
	void checksOutCommitRefsTagsAndNonDefaultBranchesForContentRepos() throws Exception {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setRef("someTag");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/tag");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setRef("8bc1d1165468359b16d9771d4a9a3df26afc03e8");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/ref");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setRef("someBranch");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/branch");
			})
		));

		List<ContentLoader.RepoCoordinate> repos = cloneContentRepos(createContent(config), config);

		assertThat(new File(findRoot(repos), "common/tag/README.md")).exists().isFile();
		assertThat(Files.readString(new File(findRoot(repos), "common/tag/README.md").toPath())).contains("someTag");
		assertThat(new File(findRoot(repos), "common/ref/README.md")).exists().isFile();
		assertThat(Files.readString(new File(findRoot(repos), "common/ref/README.md").toPath())).contains("main");
		assertThat(new File(findRoot(repos), "common/branch/README.md")).exists().isFile();
		assertThat(Files.readString(new File(findRoot(repos), "common/branch/README.md").toPath())).contains(
			"someBranch");
	}

	@Test
	void checksOutDefaultBranchWhenNoRefSet() throws Exception {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("", "git-repo-different-default-branch"));
			repo.setTarget("common/default");
			repo.setType(ContentRepoType.COPY);
		})));

		List<ContentLoader.RepoCoordinate> repos = cloneContentRepos(createContent(config), config);

		assertThat(new File(findRoot(repos), "common/default/README.md")).exists().isFile();
		assertThat(Files.readString(new File(findRoot(repos), "common/default/README.md").toPath())).contains(
			"different");
	}

	@Test
	void failsIfCommitRefDoesNotExist() {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setRef("someTag");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/tag");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setRef("does/not/exist");
				repo.setType(ContentRepoType.FOLDER_BASED);
				repo.setTarget("does not matter");
			})
		));

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> cloneContentRepos(createContent(config), config)
		);

		assertThat(exception.getMessage()).startsWith("Reference 'does/not/exist' not found in content repository");
	}

	@Test
	void respectsOrderOfFolderBasedRepositories() throws Exception {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("folderBasedRepo1"));
				repo.setRef("main");
				repo.setType(ContentRepoType.FOLDER_BASED);
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("folderBasedRepo2"));
				repo.setRef("main");
				repo.setType(ContentRepoType.FOLDER_BASED);
				repo.setPath("subPath");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setRef("main");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setPath("subPath");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo1"));
				repo.setRef("main");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
			})
		));

		List<ContentLoader.RepoCoordinate> repos = cloneContentRepos(createContent(config), config);

		assertThat(Files.readString(new File(findRoot(repos), "common/repo/file").toPath())).contains("copyRepo1");
	}

	@Test
	void isAbleToCopyIntoMirroredRepo() throws IOException, GitAPIException {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("mirrorRepo1", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("folderBasedRepo1"));
				repo.setType(ContentRepoType.FOLDER_BASED);
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
				repo.setPath("subPath");
			})
		));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);

		try (Git git = cloneRepo("common/repo", tmpDir)) {
			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("copyRepo2");
			assertThat(new File(tmpDir, "mirrorRepo1")).exists().isFile();
			assertThat(new File(tmpDir, "copyRepo2")).exists().isFile();
			assertThat(new File(tmpDir, "folderBasedRepo1")).exists().isFile();

			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertTag(git, "someTag");
			assertBranch(git, "someBranch");
		}
	}

	@Test
	void handlesMirrorAndCopyTogether() throws IOException, GitAPIException {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("folderBasedRepo1"));
				repo.setType(ContentRepoType.FOLDER_BASED);
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
				repo.setPath("subPath");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("mirrorRepo1", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setOverwriteMode(OverwriteMode.RESET);
				repo.setTarget("common/repo");
			})
		));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);

		try (Git git = cloneRepo("common/repo", tmpDir)) {
			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("mirrorRepo1");
			assertThat(new File(tmpDir, "folderBasedRepo1")).doesNotExist();
			assertThat(new File(tmpDir, "copyRepo2")).doesNotExist();

			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertTag(git, "someTag");
			assertBranch(git, "someBranch");
		}
	}

	@Test
	void handlesMultipleMirrorsOfTheSameRepoWithDifferentRefs() throws IOException, GitAPIException {
		String repoToMirror = createContentRepo("mirrorRepo1", "git-repository-with-branches-tags");
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(repoToMirror);
				repo.setType(ContentRepoType.MIRROR);
				repo.setRef("main");
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(repoToMirror);
				repo.setType(ContentRepoType.MIRROR);
				repo.setRef("someBranch");
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
			}),
			repository(repo -> {
				repo.setUrl(repoToMirror);
				repo.setType(ContentRepoType.MIRROR);
				repo.setRef("someTag");
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
				repo.setPath("subPath");
			})
		));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);

		try (Git git = cloneRepo("common/repo", tmpDir)) {
			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("copyRepo2");
			assertThat(new File(tmpDir, "mirrorRepo1")).exists().isFile();

			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertTag(git, "someTag");
			assertBranch(git, "someBranch");
		}
	}

	@Test
	void handlesTargetRefs() throws IOException, GitAPIException {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("mirror/tag");
				repo.setRef("someTag");
				repo.setTargetRef("my-tag");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("mirror/branch");
				repo.setRef("someBranch");
				repo.setTargetRef("my-branch");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("copy/tag");
				repo.setRef("someTag");
				repo.setTargetRef("my-tag");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("copy/branch");
				repo.setRef("someBranch");
				repo.setTargetRef("my-branch");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("mirror/tag2branch");
				repo.setRef("someTag");
				repo.setTargetRef("refs/heads/my-branch");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("mirror/branch2tag");
				repo.setRef("someBranch");
				repo.setTargetRef("refs/tags/my-tag");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("copy/tag2branch");
				repo.setRef("someTag");
				repo.setTargetRef("refs/heads/my-branch");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("copy/branch2tag");
				repo.setRef("someBranch");
				repo.setTargetRef("refs/tags/my-tag");
			})
		));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);

		assertTagAndReadme("mirror/tag", "my-tag", "someTag");
		assertBranchAndReadme("mirror/branch", "my-branch", "someBranch");
		assertTagAndReadme("copy/tag", "my-tag", "someTag");
		assertBranchAndReadme("copy/branch", "my-branch", "someBranch");
		assertTagAndReadme("mirror/branch2tag", "my-tag", "someBranch");
		assertBranchAndReadme("mirror/tag2branch", "my-branch", "someTag");
		assertTagAndReadme("copy/branch2tag", "my-tag", "someBranch");
		assertBranchAndReadme("copy/tag2branch", "my-branch", "someTag");
	}

	@Test
	void handlesMultipleMirrorsOfSameRepoWhereOneIsNotPushed() {
		String repoToMirror = createContentRepo("copyRepo1", "git-repository-with-branches-tags");
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(repoToMirror);
				repo.setType(ContentRepoType.MIRROR);
				repo.setRef("main");
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(repoToMirror);
				repo.setType(ContentRepoType.MIRROR);
				repo.setRef("someBranch");
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.UPGRADE);
				repo.setPath("subPath");
			})
		));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);
	}

	@Test
	void isAbleToMirrorIntoRepoThatHasSameCommits() {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("mirrorRepo1", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("mirrorRepo1", "git-repository-with-branches-tags"));
				repo.setType(ContentRepoType.MIRROR);
				repo.setTarget("common/repo");
				repo.setOverwriteMode(OverwriteMode.RESET);
			})
		));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);
	}

	@Test
	void parsesRepoCoordinates() throws Exception {
		config.getContent().setRepos(contentRepos);

		ContentLoaderForTest content = createContent(config);
		List<ContentLoader.RepoCoordinate> actualTargetRepos = cloneContentRepos(content, config);
		List<ContentLoader.RepoCoordinate> repos = actualTargetRepos;

		assertThat(actualTargetRepos).hasSameSizeAs(expectedTargetRepos);

		for (ContentLoader.RepoCoordinate expected : expectedTargetRepos) {
			List<ContentLoader.RepoCoordinate> actual = actualTargetRepos.stream()
																		 .filter(candidate -> candidate.getNamespace().equals(
																			 expected.getNamespace())
																			 && candidate.getRepoName().equals(expected.getRepoName()))
																		 .toList();

			assertThat(actual)
				.withFailMessage(
					"Could not find repo with namespace=%s and repo=%s in %s",
					expected.getNamespace(),
					expected.getRepoName(),
					actualTargetRepos
				)
				.hasSize(1);

			assertThat(actual.get(0).getClonedContentRepo().getAbsolutePath())
				.isEqualTo(new File(
					findRoot(repos),
					expected.getNamespace() + "/" + expected.getRepoName()
				).getAbsolutePath());
		}
	}

	@Test
	void createsAndPushesContentReposWholeFlow() throws IOException, GitAPIException {
		List<Config.ContentSchema.ContentRepositorySchema> repos = new ArrayList<>(contentRepos);
		repos.add(repository(repo -> {
			repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
			repo.setType(ContentRepoType.MIRROR);
			repo.setTarget("common/mirror");
		}));
		repos.add(repository(repo -> {
			repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
			repo.setType(ContentRepoType.MIRROR);
			repo.setRef("main");
			repo.setTarget("common/mirrorWithBranchRef");
		}));
		repos.add(repository(repo -> {
			repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
			repo.setType(ContentRepoType.MIRROR);
			repo.setRef("someTag");
			repo.setTarget("common/mirrorWithTagRef");
		}));
		config.getContent().setRepos(repos);

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);

		String expectedRepo = "copy/repo1";
		try (Git git = cloneRepo(expectedRepo, tmpDir)) {
			String commitMsg = git.log().call().iterator().next().getFullMessage();
			assertThat(commitMsg).isEqualTo("Initialize content repo " + expectedRepo);

			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("copyRepo1");
			assertThat(new File(tmpDir, "copyRepo1")).exists().isFile();
		}

		expectedRepo = "common/mirror";
		try (Git git = cloneRepo(expectedRepo, createRandomSubDir())) {
			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertTag(git, "someTag");
			assertBranch(git, "someBranch");
		}

		expectedRepo = "common/mirrorWithBranchRef";
		try (Git git = cloneRepo(expectedRepo, createRandomSubDir())) {
			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertNoTags(git);
			assertOnlyBranch(git, "main");
		}

		expectedRepo = "common/mirrorWithTagRef";
		try (Git git = cloneRepo(expectedRepo, createRandomSubDir())) {
			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertTag(git, "someTag");
			assertOnlyBranch(git, "main");
		}

		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
			repo.setType(ContentRepoType.MIRROR);
			repo.setRef("8bc1d1165468359b16d9771d4a9a3df26afc03e8");
			repo.setTarget("common/mirrorWithCommitRef");
		})));

		RuntimeException exception = assertThrows(RuntimeException.class, () -> install(createContent(config), config));
		assertThat(exception.getMessage())
			.startsWith(
				"Mirroring commit references is not supported for content repos at the moment. content repository");
		assertThat(exception.getMessage())
			.endsWith("ref: 8bc1d1165468359b16d9771d4a9a3df26afc03e8");

		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("", "git-repository-with-branches-tags"));
			repo.setType(ContentRepoType.MIRROR);
			repo.setRef("8bc1d11");
			repo.setTarget("common/mirrorWithShortCommitRef");
		})));

		exception = assertThrows(RuntimeException.class, () -> install(createContent(config), config));
		assertThat(exception.getMessage())
			.startsWith(
				"Mirroring commit references is not supported for content repos at the moment. content repository");
		assertThat(exception.getMessage()).endsWith("ref: 8bc1d11");
	}

	@Test
	void resetCommonRepoToRepo() throws IOException, GitAPIException {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo1"));
				repo.setRef("main");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setPath("subPath");
			})
		));

		String expectedRepo = "common/repo";
		GitRepo repo = scmmRepoProvider.create(expectedRepo, scmManagerMock);
		scmManagerMock.initOnceRepo(repo.getRepoTarget());
		install(createContent(config), config);

		String url = repo.getGitRepositoryUrl();
		try (Git git = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(tmpDir).call()) {
			verify(repo).createRepositoryAndSetPermission(any(String.class), eq(false));

			String commitMsg = git.log().call().iterator().next().getFullMessage();
			assertThat(commitMsg).isEqualTo("Initialize content repo " + expectedRepo);
			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("copyRepo2");
			assertThat(new File(tmpDir, "copyRepo2")).exists().isFile();
		}

		config.getContent().setRepos(List.of(repository(contentRepo -> {
			contentRepo.setUrl(createContentRepo("copyRepo1"));
			contentRepo.setRef("main");
			contentRepo.setType(ContentRepoType.COPY);
			contentRepo.setTarget("common/repo");
			contentRepo.setOverwriteMode(OverwriteMode.RESET);
		})));

		install(createContent(config), config);
		scmManagerMock.clearInitOnce();

		File folderAfterReset = Files.createTempDirectory("second-cloned-repo").toFile();
		folderAfterReset.deleteOnExit();
		try (Git git2 = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(folderAfterReset).call()) {
			assertThat(git2).isNotNull();
			assertThat(Files.readString(new File(folderAfterReset, "file").toPath())).contains("copyRepo1");
			assertThat(new File(folderAfterReset, "copyRepo2").exists()).isFalse();
		}
	}

	@Test
	void updateCommonRepoTest() throws IOException, GitAPIException {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setTarget("common/repo");
		})));

		scmmApiClient.mockRepoApiBehaviour();
		install(createContent(config), config);

		String expectedRepo = "common/repo";
		GitRepo repo = scmmRepoProvider.create(expectedRepo, new ScmManagerProviderMock());
		String url = repo.getGitRepositoryUrl();

		try (Git git = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(tmpDir).call()) {
			verify(repo).createRepositoryAndSetPermission(any(String.class), eq(false));

			String commitMsg = git.log().call().iterator().next().getFullMessage();
			assertThat(commitMsg).isEqualTo("Initialize content repo " + expectedRepo);
			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("copyRepo1");
			assertThat(new File(tmpDir, "copyRepo1")).exists().isFile();
		}

		config.getContent().setRepos(List.of(repository(contentRepo -> {
			contentRepo.setUrl(createContentRepo("copyRepo2"));
			contentRepo.setType(ContentRepoType.COPY);
			contentRepo.setTarget("common/repo");
			contentRepo.setPath("subPath");
			contentRepo.setOverwriteMode(OverwriteMode.UPGRADE);
		})));

		install(createContent(config), config);

		File folderAfterReset = Files.createTempDirectory("second-cloned-repo").toFile();
		folderAfterReset.deleteOnExit();
		try (Git git2 = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(folderAfterReset).call()) {
			assertThat(git2).isNotNull();
			assertThat(Files.readString(new File(folderAfterReset, "file").toPath())).contains("copyRepo2");
			assertThat(new File(folderAfterReset, "copyRepo2").exists()).isTrue();
		}
	}

	@Test
	void initCommonRepoExpectUnchangedRepo() throws IOException, GitAPIException {
		config.getContent().setRepos(List.of(
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo1"));
				repo.setRef("main");
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
			}),
			repository(repo -> {
				repo.setUrl(createContentRepo("copyRepo2"));
				repo.setType(ContentRepoType.COPY);
				repo.setTarget("common/repo");
				repo.setPath("subPath");
			})
		));

		String expectedRepo = "common/repo";
		GitRepo repo = scmmRepoProvider.create(expectedRepo, scmManagerMock);
		scmManagerMock.initOnceRepo(repo.getRepoTarget());
		install(createContent(config), config);

		String url = repo.getGitRepositoryUrl();
		try (Git git = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(tmpDir).call()) {
			verify(repo).createRepositoryAndSetPermission(any(String.class), eq(false));

			String commitMsg = git.log().call().iterator().next().getFullMessage();
			assertThat(commitMsg).isEqualTo("Initialize content repo " + expectedRepo);
			assertThat(Files.readString(new File(tmpDir, "file").toPath())).contains("copyRepo2");
			assertThat(new File(tmpDir, "copyRepo2")).exists().isFile();
		}

		config.getContent().setRepos(List.of(repository(contentRepo -> {
			contentRepo.setUrl(createContentRepo("copyRepo1"));
			contentRepo.setRef("main");
			contentRepo.setType(ContentRepoType.COPY);
			contentRepo.setTarget("common/repo");
			contentRepo.setOverwriteMode(OverwriteMode.INIT);
		})));

		install(createContent(config), config);
		scmManagerMock.clearInitOnce();

		File folderAfterReset = Files.createTempDirectory("second-cloned-repo").toFile();
		folderAfterReset.deleteOnExit();
		try (Git git = Git.cloneRepository().setURI(url).setBranch("main").setDirectory(folderAfterReset).call()) {
			assertThat(git).isNotNull();
			assertThat(Files.readString(new File(folderAfterReset, "file").toPath())).contains("copyRepo2");
			assertThat(new File(folderAfterReset, "copyRepo2").exists()).isTrue();
		}
	}

	@Test
	void ensureJenkinsJobWillBeCreated() {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setCreateJenkinsJob(true);
			repo.setTarget("common/repo");
		})));
		scmmApiClient.mockRepoApiBehaviour();
		when(jenkins.isEnabled(any(DeploymentContext.class))).thenReturn(true);

		install(createContent(config), config);

		verify(jenkins).createJenkinsjob(any(), any());
	}

	@Test
	void ensureJenkinsJobCreationWillBeIgnored() {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setCreateJenkinsJob(false);
			repo.setTarget("common/repo");
		})));
		scmmApiClient.mockRepoApiBehaviour();
		when(jenkins.isEnabled(any(DeploymentContext.class))).thenReturn(false);

		install(createContent(config), config);

		verify(jenkins, never()).createJenkinsjob(any(), any());
	}

	@Test
	void ensureJenkinsJobWillNotBeCreatedIfJenkinsIsNotEnabled() {
		config.getContent().setRepos(List.of(repository(repo -> {
			repo.setUrl(createContentRepo("copyRepo1"));
			repo.setRef("main");
			repo.setType(ContentRepoType.COPY);
			repo.setCreateJenkinsJob(false);
			repo.setTarget("common/repo");
		})));
		scmmApiClient.mockRepoApiBehaviour();
		when(jenkins.isEnabled(any(DeploymentContext.class))).thenReturn(false);

		install(createContent(config), config);

		verify(jenkins, never()).createJenkinsjob(any(), any());
	}

	@Test
	void deployHelmReleasesFromContentSkipsWhenHelmReleasesMissingOrEmpty() {
		ContentLoaderForTest contentLoader = createContent(config);
		install(contentLoader, config);

		assertThat(contentLoader.deployCalls).isEmpty();
	}

	@Test
	void deployHelmReleasesFromContentCallsDeployHelmChartWithValuesPathAndHelmConfig() throws IOException {
		Path valuesFile = Files.createTempFile("harbor-values-", ".yaml");
		Files.writeString(
			valuesFile, """
				expose:
				  type: ingress
				"""
		);

		Config cfg = Config.fromMap(Map.of(
			"content", Map.of(
				"helmReleases", List.of(Map.of(
					"name", "harbor",
					"repoURL", "https://helm.goharbor.io",
					"chart", "harbor",
					"version", "1.18.2",
					"namespace", "my-prefix-harbor",
					"releaseName", "harbor",
					"valuesPath", valuesFile.toString()
				))
			)
		));

		ContentLoaderForTest contentLoader = createContent(cfg);
		install(contentLoader, cfg);

		assertThat(contentLoader.deployCalls).hasSize(1);
		DeployCall call = contentLoader.deployCalls.get(0);

		assertThat(call.featureName).isEqualTo("harbor");
		assertThat(call.releaseName).isEqualTo("harbor");
		assertThat(call.namespace).isEqualTo("my-prefix-harbor");
		assertThat(call.valuesPath).isNotBlank();
		assertThat(Path.of(call.valuesPath).toFile()).exists();
		assertThat(call.helmConfig.repoURL()).isEqualTo("https://helm.goharbor.io");
		assertThat(call.helmConfig.chart()).isEqualTo("harbor");
		assertThat(call.helmConfig.version()).isEqualTo("1.18.2");
		assertThat(call.config).isSameAs(cfg);
	}

	@Test
	void deployHelmReleasesFromContentReadsValuesFileAndInlineValuesOverrideFileValues(@TempDir Path tempDir)
		throws IOException {
		Path valuesFile = tempDir.resolve("harbor-values.yaml");
		Files.writeString(
			valuesFile, """
				replicas: 1
				service:
				  type: ClusterIP
				"""
		);

		Config cfg = Config.fromMap(Map.of(
			"content", Map.of(
				"helmReleases", List.of(Map.ofEntries(
					Map.entry("name", "harbor"),
					Map.entry("repoURL", "https://helm.goharbor.io"),
					Map.entry("chart", "harbor"),
					Map.entry("version", "1.18.2"),
					Map.entry("namespace", "my-prefix-harbor"),
					Map.entry("releaseName", "harbor"),
					Map.entry("valuesPath", valuesFile.toString()),
					Map.entry(
						"values", Map.of(
							"replicas", 2,
							"service", Map.of("type", "NodePort")
						)
					)
				))
			)
		));

		ContentLoaderForTest contentLoader = createContent(cfg);
		install(contentLoader, cfg);

		assertThat(contentLoader.deployCalls).hasSize(1);
		DeployCall call = contentLoader.deployCalls.get(0);
		Path mergedTemp = Path.of(call.valuesPath);
		assertThat(mergedTemp).exists();

		Map<String, Object> mergedYaml = readYaml(mergedTemp.toFile());
		assertThat(mergedYaml.get("replicas")).isEqualTo(2);
		assertThat(((Map<String, Object>) mergedYaml.get("service")).get("type")).isEqualTo("NodePort");
	}

	@Test
	void deployHelmReleasesFromContentUsesValuesFileWhenInlineValuesAreEmpty(@TempDir Path tempDir)
		throws IOException {
		Path valuesFile = tempDir.resolve("values.yaml");
		Files.writeString(
			valuesFile, """
				replicas: 1
				"""
		);

		Config cfg = Config.fromMap(Map.of(
			"content", Map.of(
				"helmReleases", List.of(Map.of(
					"name", "elasticsearch",
					"repoURL", "https://helm.elastic.co",
					"chart", "elasticsearch",
					"version", "8.5.1",
					"namespace", "my-prefix-elasticsearch",
					"valuesPath", valuesFile.toString()
				))
			)
		));

		ContentLoaderForTest contentLoader = createContent(cfg);
		install(contentLoader, cfg);

		assertThat(contentLoader.deployCalls).hasSize(1);
		DeployCall call = contentLoader.deployCalls.get(0);
		Path mergedTemp = Path.of(call.valuesPath);
		assertThat(mergedTemp).exists();

		Map<String, Object> mergedYaml = readYaml(mergedTemp.toFile());
		assertThat(mergedYaml.get("replicas")).isEqualTo(1);
	}

	@Test
	void deployHelmReleasesFromContentUsesInlineValuesWhenNoHelmValuesPathIsSet() throws IOException {
		Config cfg = Config.fromMap(Map.of(
			"content", Map.of(
				"helmReleases", List.of(Map.of(
					"name", "elasticsearch",
					"repoURL", "https://helm.elastic.co",
					"chart", "elasticsearch",
					"version", "8.5.1",
					"namespace", "my-prefix-elasticsearch",
					"values", Map.of("replicas", 2)
				))
			)
		));

		ContentLoaderForTest contentLoader = createContent(cfg);
		install(contentLoader, cfg);

		assertThat(contentLoader.deployCalls).hasSize(1);
		DeployCall call = contentLoader.deployCalls.get(0);
		Path mergedTemp = Path.of(call.valuesPath);
		assertThat(mergedTemp).exists();

		Map<String, Object> mergedYaml = readYaml(mergedTemp.toFile());
		assertThat(mergedYaml.get("replicas")).isEqualTo(2);
	}

	@Test
	void deployHelmReleasesFromContentDefaultsChartVersionToWildcardWhenMissing() {
		Config cfg = Config.fromMap(Map.of(
			"content", Map.of(
				"helmReleases", List.of(Map.of(
					"name", "harbor",
					"repoURL", "https://helm.goharbor.io",
					"chart", "harbor",
					"version", "   ",
					"namespace", "my-prefix-harbor",
					"releaseName", "harbor",
					"values", Map.of("foo", "bar")
				))
			)
		));

		ContentLoaderForTest contentLoader = createContent(cfg);
		install(contentLoader, cfg);

		assertThat(contentLoader.deployCalls).hasSize(1);
		DeployCall call = contentLoader.deployCalls.get(0);
		assertThat(call.helmConfig.version()).isEqualTo("*");
	}

	static String createContentRepo() {
		return createContentRepo("", "git-repository");
	}

	static String createContentRepo(String initPath) {
		return createContentRepo(initPath, "git-repository");
	}

	static String createContentRepo(String initPath, String baseBareRepo) {
		try {
			File bareRepoDir = Files.createTempDirectory("gitops-playground-test-content-repo").toFile();
			bareRepoDir.deleteOnExit();
			foldersToDelete.add(bareRepoDir);

			FileUtils.copyDirectory(
				new File(System.getProperty("user.dir")
					+ "/src/test/resources/com/cloudogu/gitops/utils/data/" + baseBareRepo + "/"),
				bareRepoDir
			);
			String bareRepoUri = "file://" + bareRepoDir.getAbsolutePath();
			log.debug("Repo {}: bare repo {}", initPath, bareRepoUri);

			if (!initPath.isEmpty()) {
				File tempRepo = Files.createTempDirectory("gitops-playground-temp-repo").toFile();
				tempRepo.deleteOnExit();
				foldersToDelete.add(tempRepo);
				log.debug("Repo {}: cloned bare repo to {}", initPath, tempRepo);

				try (Git git = Git.cloneRepository()
								  .setURI(bareRepoUri)
								  .setBranch("main")
								  .setDirectory(tempRepo)
								  .call()) {

					FileUtils.copyDirectory(
						new File(System.getProperty("user.dir")
							+ "/src/test/resources/com/cloudogu/gitops/utils/data/contentRepos/" + initPath),
						tempRepo
					);

					git.add().addFilepattern(".").call();
					SystemReader.getInstance().getUserConfig().clear();
					git.commit().setMessage("Initialize with " + initPath).call();
					git.push().call();
					tempRepo.delete();
				}
			}

			return bareRepoUri;
		} catch (IOException | GitAPIException | ConfigInvalidException e) {
			throw new IllegalStateException("Failed to create test content repository", e);
		}
	}

	private Map<String, Object> parseYaml(String path) throws IOException {
		return readYaml(new File(path));
	}

	private void assertRegistrySecrets(String regUser, String regPw) {
	}

	private ContentLoaderForTest createContent(Config contentConfig) {
		return new ContentLoaderForTest(
			contentConfig,
			k8sClient,
			scmmRepoProvider,
			jenkins,
			gitHandler,
			fileSystemUtils,
			deployer
		);
	}

	private boolean install(ContentLoaderForTest contentLoader, Config contentConfig) {
		return contentLoader.execute(new ContextBuilder(contentConfig).build(), repositoryWorkspace);
	}

	private List<ContentLoader.RepoCoordinate> cloneContentRepos(
		ContentLoaderForTest contentLoader,
		Config contentConfig) throws Exception {
		return contentLoader.cloneContentRepos(new ContextBuilder(contentConfig).build());
	}

	private static Map<String, Object> parseActualYaml(File pathToYamlFile) throws IOException {
		return readYaml(pathToYamlFile);
	}

	private static Map<String, Object> readYaml(File file) throws IOException {
		return YAML_MAPPER.readValue(file, YAML_MAP_TYPE);
	}

	private static String findRoot(List<ContentLoader.RepoCoordinate> repos) {
		return new File(repos.get(0).getClonedContentRepo().getParent()).getParent();
	}

	Git cloneRepo(String expectedRepo, File repoFolder) throws GitAPIException {
		GitRepo repo = scmmRepoProvider.create(expectedRepo, new ScmManagerProviderMock());
		String url = repo.getGitRepositoryUrl();

		Git git = Git.cloneRepository()
					 .setURI(url)
					 .setBranch("main")
					 .setDirectory(repoFolder)
					 .call();
		git.getRepository().getConfig().setBoolean("gc", null, "autoDetach", false);
		return git;
	}

	private File createRandomSubDir() {
		return createRandomSubDir("");
	}

	private File createRandomSubDir(String prefix) {
		String directoryName = (prefix.isEmpty() ? "" : prefix + "-") + System.currentTimeMillis();
		File randomDir = tmpDir.toPath().resolve(directoryName).toFile();
		randomDir.mkdirs();
		return randomDir;
	}

	void assertTagAndReadme(String repo, String expectedTag, String expectedReadmeContent)
		throws GitAPIException, IOException {
		File repoFolder = createRandomSubDir();
		try (Git git = cloneRepo(repo, repoFolder)) {
			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertTag(git, expectedTag);

			git.checkout().setName(expectedTag).call();
			assertThat(new File(repoFolder, "README.md")).exists().isFile();
			assertThat(Files.readString(new File(repoFolder, "README.md").toPath())).contains(expectedReadmeContent);
		}
	}

	void assertBranchAndReadme(String repo, String expectedBranch, String expectedReadmeContent)
		throws GitAPIException, IOException {
		File repoFolder = createRandomSubDir();
		try (Git git = cloneRepo(repo, repoFolder)) {
			git.fetch().setRefSpecs("refs/*:refs/*").call();
			assertBranch(git, expectedBranch);

			git.checkout().setName(expectedBranch).call();
			assertThat(new File(repoFolder, "README.md")).exists().isFile();
			assertThat(Files.readString(new File(repoFolder, "README.md").toPath())).contains(expectedReadmeContent);
		}
	}

	private static void assertOnlyBranch(Git git, String branch) throws GitAPIException {
		List<Ref> branches = assertBranch(git, branch);
		List<Ref> otherBranches = branches.stream()
										  .filter(ref -> !ref.getName().contains(branch))
										  .toList();

		assertThat(otherBranches)
			.withFailMessage(
				"More than the expected branch main found. Available branches: %s",
				otherBranches.stream().map(Ref::getName).toList()
			)
			.hasSize(0);
	}

	private static void assertNoTags(Git git) throws GitAPIException {
		List<Ref> tags = git.tagList().call();
		assertThat(tags)
			.withFailMessage(
				"No tags in mirrored repo with ref expected. Available tags: %s",
				tags.stream().map(Ref::getName).toList()
			)
			.hasSize(0);
	}

	private static List<Ref> assertBranch(Git git, String someBranch) throws GitAPIException {
		List<Ref> branches = git.branchList().call();
		assertThat(branches.stream()
						   .filter(ref -> ref.getName().equals("refs/heads/" + someBranch))
						   .toList())
			.withFailMessage(
				"Branch '%s' not found in git repository. Available branches: %s",
				someBranch,
				branches.stream().map(Ref::getName).toList()
			)
			.hasSize(1);
		return branches;
	}

	private static void assertTag(Git git, String expectedTag) throws GitAPIException {
		List<Ref> tags = git.tagList().call();
		assertThat(tags.stream()
					   .filter(ref -> ref.getName().equals("refs/tags/" + expectedTag))
					   .toList())
			.withFailMessage(
				"Tag '%s' not found in git repository. Available tags: %s",
				expectedTag,
				tags.stream().map(Ref::getName).toList()
			)
			.hasSize(1);
	}

	private static Config createConfig() {
		Config config = new Config();
		config.getApplication().setNamePrefix("foo-");

		ScmTenantSchema.ScmManagerTenantConfig scmManager = new ScmTenantSchema.ScmManagerTenantConfig();
		scmManager.setUrl("");
		config.getScm().setScmManager(scmManager);

		config.getRegistry().setUrl("reg-url");
		config.getRegistry().setPath("reg-path");
		config.getRegistry().setUsername("reg-user");
		config.getRegistry().setPassword("reg-pw");
		config.getRegistry().setCreateImagePullSecrets(false);
		return config;
	}

	private static ContentLoader.RepoCoordinate repoCoordinate(String namespace, String repoName) {
		ContentLoader.RepoCoordinate coordinate = new ContentLoader.RepoCoordinate();
		coordinate.setNamespace(namespace);
		coordinate.setRepoName(repoName);
		return coordinate;
	}

	private static Config.ContentSchema.ContentRepositorySchema repository(
		Consumer<Config.ContentSchema.ContentRepositorySchema> configurator) {
		Config.ContentSchema.ContentRepositorySchema repository =
			new Config.ContentSchema.ContentRepositorySchema();
		configurator.accept(repository);
		return repository;
	}

	private static Object readPrivateField(Object target, String fieldName) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(target);
	}

	class ContentLoaderForTest extends ContentLoader {

		private final Config contentConfig;
		final List<DeployCall> deployCalls = new ArrayList<>();
		CloneCommand cloneSpy;

		ContentLoaderForTest(
			Config config,
			K8sClient k8sClient,
			GitRepoFactory repoProvider,
			Jenkins jenkins,
			GitHandler gitHandler,
			FileSystemUtils fileSystemUtils,
			Deployer deployer) {
			super(config, k8sClient, repoProvider, jenkins, gitHandler, fileSystemUtils, deployer);
			this.contentConfig = config;
		}

		List<ContentLoader.RepoCoordinate> cloneContentRepos(DeploymentContext context) throws Exception {
			this.context = context;
			return super.cloneContentRepos();
		}

		@Override
		protected void deployHelmChart(
			String featureName,
			String releaseName,
			String namespace,
			HelmChartConfig helmConfig,
			String helmValuesTemplatePath,
			DeploymentContext context,
			boolean initByHelm) {
			DeployCall call = new DeployCall();
			call.featureName = featureName;
			call.releaseName = releaseName;
			call.namespace = namespace;
			call.helmConfig = helmConfig;
			call.valuesPath = helmValuesTemplatePath;
			call.config = contentConfig;
			call.initByHelm = initByHelm;
			deployCalls.add(call);
		}

		@Override
		protected CloneCommand gitClone() {
			return cloneSpy = spy(super.gitClone().setNoCheckout(true));
		}
	}

	static class DeployCall {
		String featureName;
		String releaseName;
		String namespace;
		HelmChartConfig helmConfig;
		String valuesPath;
		Config config;
		boolean initByHelm;
	}
}
