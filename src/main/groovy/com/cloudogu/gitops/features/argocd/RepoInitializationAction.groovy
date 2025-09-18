package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.local.GitRepo
import com.cloudogu.gitops.git.providers.ScmUrlResolver
import freemarker.template.DefaultObjectWrapperBuilder

class RepoInitializationAction {
    private GitRepo repo
    private String copyFromDirectory
    private Config config

    RepoInitializationAction(Config config, GitRepo repo, String copyFromDirectory) {
        this.config = config
        this.repo = repo
        this.copyFromDirectory = copyFromDirectory
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

    private static Map<String, Object> buildTemplateValues(Config config){
        def model = [
                tenantName: config.application.tenantName,
                argocd    : [host: config.features.argocd.url ? new URL(config.features.argocd.url).host : ""], //TODO move this to argocd class and get the url from there
                scmm      : [
                        baseUrl       : config.scm.isInternal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm" : ScmUrlResolver.externalHost(config),
                        host          : config.scm.getUrl ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local" : config.scm.host,
                        protocol      : config.scm.isInternal ? 'http' : config.scm.protocol,
                        repoUrl       : ScmUrlResolver.tenantBaseUrl(config),
                        //TODO centralScmmURL from config.multiTenant
                        //centralScmmUrl: !config.multiTenant.internal ? config.multiTenant.get : "http://scmm.scm-manager.svc.cluster.local/scm"
                ],
                config    : config,
                // Allow for using static classes inside the templates
                statics : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ] as Map<String, Object>

        return model
    }

}