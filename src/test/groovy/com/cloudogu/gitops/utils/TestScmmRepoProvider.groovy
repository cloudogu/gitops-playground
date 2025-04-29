package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.Git

class TestScmmRepoProvider extends ScmmRepoProvider {
    Map<String, ScmmRepo> repos = [:]
    
    TestScmmRepoProvider(Config config, FileSystemUtils fileSystemUtils) {
        super(config, fileSystemUtils)
    }

    @Override
    ScmmRepo getRepo(String repoTarget) {
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
        repos.put(repoTarget, repo)
        return repo
    }

    @Override
    ScmmRepo getRepo(String repoTarget, Boolean centralized) {
        ScmmRepo repo = new ScmmRepo(config, repoTarget, fileSystemUtils, centralized) {
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
        repos.put(repoTarget, repo)
        return repo
    }
}