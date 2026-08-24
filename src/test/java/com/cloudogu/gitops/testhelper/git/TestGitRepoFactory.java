package com.cloudogu.gitops.testhelper.git;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.infrastructure.git.GitRepoFactory;
import com.cloudogu.gitops.infrastructure.git.providers.GitProvider;
import com.cloudogu.gitops.utils.FileSystemUtils;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

public class TestGitRepoFactory extends GitRepoFactory {

	@Getter
	private final Map<String, GitRepo> repos = new HashMap<>();

	@Getter
	@Setter
	private GitProvider defaultProvider;

	public TestGitRepoFactory(Config config, FileSystemUtils fileSystemUtils) {
		super(config, fileSystemUtils);
	}

	@Override
	public GitRepo create(String repoTarget, GitProvider scm) {
		GitProvider effectiveProvider = scm != null ? scm : defaultProvider;

		if (effectiveProvider == null) {
			throw new IllegalStateException(
				"No GitProvider provided for repo '" + repoTarget + "' and defaultProvider is null."
			);
		}

		GitRepo existingRepo = repos.get(repoTarget);
		if (existingRepo != null) {
			return existingRepo;
		}

		String prefixedRepoTarget = config.getApplication().getNamePrefix() + repoTarget;
		GitRepo repoNew = new GitRepo(config, scm, prefixedRepoTarget, fileSystemUtils) {
			private String remoteGitRepoUrl = "";

			@Override
			public String getGitRepositoryUrl() {
				if (remoteGitRepoUrl.isEmpty()) {
					try {
						File tempDir = Files.createTempDirectory("gitops-playground-repocopy").toFile();
						tempDir.deleteOnExit();
						String originalRepo = System.getProperty("user.dir")
							+ "/src/test/resources/com/cloudogu/gitops/utils/data/git-repository/";

						FileUtils.copyDirectory(new File(originalRepo), tempDir);
						remoteGitRepoUrl = "file://" + tempDir.getAbsolutePath();
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
				return remoteGitRepoUrl;
			}
		};

		GitRepo spyRepo = spy(repoNew);

		// Test-only: remove local clone target before cloning to avoid "not empty" errors
		try {
			doAnswer(invocation -> {
				File target = new File(spyRepo.getAbsoluteLocalRepoTmpDir());
				if (target.exists()) {
					FileUtils.deleteDirectory(target);
				}
				return invocation.callRealMethod();
			}).when(spyRepo).cloneRepo();
		} catch (GitAPIException e) {
			throw new IllegalStateException("Failed to configure GitRepo test spy", e);
		}

		repos.put(repoTarget, spyRepo);
		return spyRepo;
	}
}
