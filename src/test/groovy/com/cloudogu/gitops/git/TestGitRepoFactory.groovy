package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.utils.FileSystemUtils
import org.apache.commons.io.FileUtils

import static org.mockito.Mockito.doAnswer
import static org.mockito.Mockito.spy


class TestGitRepoFactory extends GitRepoFactory {
    Map<String, GitRepo> repos = [:]
    GitProvider defaultProvider

    TestGitRepoFactory(Config config, FileSystemUtils fileSystemUtils) {
        super(config, fileSystemUtils)
    }

    @Override
    GitRepo getRepo(String repoTarget, GitProvider scm) {
        def effectiveProvider = scm ?: defaultProvider

        if (!effectiveProvider) {
            throw new IllegalStateException(
                    "No GitProvider provided for repo '${repoTarget}' and defaultProvider is null."
            )
        }

        if (repos[repoTarget]) {
            return repos[repoTarget]
        }

        GitRepo repoNew = new GitRepo(config, scm, repoTarget, fileSystemUtils) {
            String remoteGitRepoUrl = ''

            @Override
            String getGitRepositoryUrl() {
                if (!remoteGitRepoUrl) {

                    def tempDir = File.createTempDir('gitops-playground-repocopy')
                    tempDir.deleteOnExit()
                    def originalRepo = System.getProperty("user.dir") + "/src/test/groovy/com/cloudogu/gitops/utils/data/git-repository/"

                    FileUtils.copyDirectory(new File(originalRepo), tempDir)
                    remoteGitRepoUrl = 'file://' + tempDir.absolutePath
                }
                return remoteGitRepoUrl
            }
        }


        GitRepo spyRepo = spy(repoNew)

        // Test-only: remove local clone target before cloning to avoid "not empty" errors
        doAnswer { invocation ->
            File target = new File(spyRepo.absoluteLocalRepoTmpDir)
            if (target?.exists()) {
                FileUtils.deleteDirectory(target)
            }
            invocation.callRealMethod()
        }.when(spyRepo).cloneRepo()
        repos.put(repoTarget, spyRepo)
        return spyRepo
    }
}