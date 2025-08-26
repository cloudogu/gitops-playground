package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo

class RepoInitializationAction {
    private ScmmRepo repo
    private String copyFromDirectory
    private Config config

    RepoInitializationAction(Config config, ScmmRepo repo, String copyFromDirectory) {
        this.config = config
        this.repo = repo
        this.copyFromDirectory = copyFromDirectory
    }

    /**
     * Clone repo from SCM and initialize it with default basic files. Afterwards we can edit these files.
     */
    void initLocalRepo() {
        repo.cloneRepo()
        repo.copyDirectoryContents(copyFromDirectory)
        replaceTemplates()
    }

    void replaceTemplates() {
        Map<String, Object> templateValues = new ArgoCDValuesBuilder(config).build()
        repo.replaceTemplates(templateValues)
    }

    ScmmRepo getRepo() {
        return repo
    }
}