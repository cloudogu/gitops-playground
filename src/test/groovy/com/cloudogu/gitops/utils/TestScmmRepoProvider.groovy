package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git

import static org.mockito.Mockito.spy

class TestScmmRepoProvider extends ScmmRepoProvider {
    Map<String, ScmmRepo> repos = [:]
    
    TestScmmRepoProvider(Config config, FileSystemUtils fileSystemUtils) {
        super(config, fileSystemUtils)
    }

    @Override
    ScmmRepo getRepo(String repoTarget) {
        // Check if we already have a mock for this repo
        if (repos.containsKey(repoTarget)) {
            return repos[repoTarget]
        }

        ScmmRepo repo = new ScmmRepo(config, repoTarget, fileSystemUtils) {
            @Override
            protected String getGitRepositoryUrl() {
                def tempDir = File.createTempDir('gitops-playground-repocopy')
                tempDir.deleteOnExit()
                def originalRepo = System.getProperty("user.dir") + "/src/test/groovy/com/cloudogu/gitops/utils/data/git-repository/"

                FileUtils.copyDirectory(new File(originalRepo), tempDir)

                return 'file://' + tempDir.absolutePath
            }

            @Override
            protected Git gitClone() {
                // Cloning from filepath does not work without setting branch
                Git.cloneRepository()
                        .setURI(getGitRepositoryUrl())
                        .setDirectory(new File(absoluteLocalRepoTmpDir))
                        .setNoCheckout(true)
                        .setBranch('main')
                        .call()
            }
        }
        // Create a spy to enable verification while keeping real behavior
        ScmmRepo spyRepo = spy(repo)

        repos.put(repoTarget, spyRepo)
        return spyRepo
    }
}
