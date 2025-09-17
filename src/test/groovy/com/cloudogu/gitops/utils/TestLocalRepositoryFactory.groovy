package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.local.LocalRepository
import com.cloudogu.gitops.git.local.LocalRepositoryFactory
import org.apache.commons.io.FileUtils

import static org.mockito.Mockito.spy 

class TestLocalRepositoryFactory extends LocalRepositoryFactory {
    Map<String, LocalRepository> repos = [:]

    TestLocalRepositoryFactory(Config config, FileSystemUtils fileSystemUtils) {
        super(config, fileSystemUtils)
    }
    @Override
    LocalRepository getRepo(String repoTarget){
        return getRepo(repoTarget,false)
    }

    @Override
    LocalRepository getRepo(String repoTarget, Boolean centralRepo) {
        // Check if we already have a mock for this repo
        LocalRepository repo = repos[repoTarget]
        // Check if we already have a mock for this repo
        if (repo != null && repo.isCentralRepo == centralRepo) {
            return repo
        }

        LocalRepository repoNew = new LocalRepository(config, repoTarget, fileSystemUtils, centralRepo) {
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
        LocalRepository spyRepo = spy(repoNew)
        repos.put(repoTarget, spyRepo)
        return spyRepo
    }
}