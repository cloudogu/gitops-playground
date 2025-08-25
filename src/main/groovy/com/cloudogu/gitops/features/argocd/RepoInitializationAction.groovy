package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import freemarker.template.DefaultObjectWrapperBuilder

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
        Map<String, Object> templateModel = new ArgoCDTemplateContextBuilder(config).build()
        repo.replaceTemplates(templateModel)
    }

    ScmmRepo getRepo() {
        return repo
    }
}