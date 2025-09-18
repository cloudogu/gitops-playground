package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.providers.GitProvider
import com.cloudogu.gitops.git.local.GitRepo
import com.cloudogu.gitops.git.local.ScmRepoProvider
import org.apache.commons.io.FileUtils

import static org.mockito.Mockito.spy 

class TestScmmRepoProvider extends ScmRepoProvider {
    Map<String, GitRepo> repos = [:]

    TestScmmRepoProvider(Config config, FileSystemUtils fileSystemUtils) {
        super(config, fileSystemUtils)
    }
    @Override
    GitRepo getRepo(GitProvider scm, String repoTarget){
        return getRepo(repoTarget,false)
    }

    @Override
    GitRepo getRepo(GitProvider scm, String repoTarget, Boolean centralRepo) {
        // Check if we already have a mock for this repo
        GitRepo repo = repos[repoTarget]
        // Check if we already have a mock for this repo
        if (repo != null && repo.isCentralRepo == centralRepo) {
            return repo
        }

        GitRepo repoNew = new GitRepo(config,scm, repoTarget, fileSystemUtils) {
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