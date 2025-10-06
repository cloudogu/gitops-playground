package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import freemarker.template.DefaultObjectWrapperBuilder

class RepoInitializationAction {
    private GitRepo repo
    private String copyFromDirectory
    private Config config
    private GitHandler gitHandler

    RepoInitializationAction(Config config, GitRepo repo,GitHandler gitHandler, String copyFromDirectory) {
        this.config = config
        this.repo = repo
        this.copyFromDirectory = copyFromDirectory
        this.gitHandler = gitHandler
    }

    /**
     * Clone repo from SCMHandler and initialize it with default basic files. Afterwards we can edit these files.
     */
    void initLocalRepo() {
        repo.cloneRepo()
        repo.copyDirectoryContents(copyFromDirectory)
        replaceTemplates()
    }

    void replaceTemplates() {
        Map<String, Object> templateModel = buildTemplateValues(config)
        repo.replaceTemplates(templateModel)
    }

    GitRepo getRepo() {
        return repo
    }

    private Map<String, Object> buildTemplateValues(Config config) {
        def model = [
                tenantName: config.application.tenantName,
                argocd    : [host: config.features.argocd.url ? new URL(config.features.argocd.url).host : ""], //TODO move this to argocd class and get the url from there
                scmm      : [
                        baseUrl : this.gitHandler.tenant.url,
                        host    : this.gitHandler.tenant.host,
                        protocol: this.gitHandler.tenant.protocol,
                        repoUrl : this.gitHandler.tenant.computeRepoUrlPrefixForInCluster(true),
                        centralScmmUrl: this.gitHandler.central.url
                ],
                config    : config,
                // Allow for using static classes inside the templates
                statics   : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ] as Map<String, Object>

        return model
    }

}