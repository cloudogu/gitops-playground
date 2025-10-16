package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.git.providers.GitProvider
import org.apache.commons.io.FileUtils

import static org.mockito.Mockito.spy

class TestGitRepoFactory extends GitRepoFactory {
    Map<String, GitRepo> repos = [:]

    TestGitRepoFactory(Config config, FileSystemUtils fileSystemUtils) {
        super(config, fileSystemUtils)
    }

    @Override
    GitRepo getRepo(String repoTarget, GitProvider scm) {

        if (repos[repoTarget]) {
            return repos[repoTarget]
        }

        GitRepo repoNew = new GitRepo(config, scm, repoTarget, fileSystemUtils) {
            String remoteGitRepopUrl = ''

            @Override
            String getGitRepositoryUrl() {
                if (!remoteGitRepopUrl) {

                    def tempDir = File.createTempDir('gitops-playground-repocopy')
                    tempDir.deleteOnExit()
                    def originalRepo = System.getProperty("user.dir") + "/src/test/groovy/com/cloudogu/gitops/utils/data/git-repository/"

                    FileUtils.copyDirectory(new File(originalRepo), tempDir)
                    remoteGitRepopUrl = 'file://' + tempDir.absolutePath
                }
                return remoteGitRepopUrl
            }

        }

        // Create a spy to enable verification while keeping real behavior
        GitRepo spyRepo = spy(repoNew)
        repos.put(repoTarget, spyRepo)
        return spyRepo
    }
}