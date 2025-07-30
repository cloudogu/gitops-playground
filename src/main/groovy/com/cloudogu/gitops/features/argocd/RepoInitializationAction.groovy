package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmUrlResolver
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

    ScmmRepo getRepo() {
        return repo
    }

    private static Map<String, Object> buildTemplateValues(Config config){
        def model = [
                tenantName: tenantName(config.application.namePrefix),
                argocd    : [host: config.features.argocd.url ? new URL(config.features.argocd.url).host : ""],
                scmm      : [
                        baseUrl       : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm" : ScmUrlResolver.externalHost(config),
                        host          : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local" : config.scmm.host,
                        protocol      : config.scmm.internal ? 'http' : config.scmm.protocol,
                        repoUrl       : ScmUrlResolver.tenantBaseUrl(config),
                        centralScmmUrl: !config.multiTenant.internal ? config.multiTenant.centralScmUrl : "http://scmm.scm-manager.svc.cluster.local/scm"
                ],
                config    : config,
                // Allow for using static classes inside the templates
                statics : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ] as Map<String, Object>

        return model
    }

    private static String tenantName(String namePrefix) {
        if (!namePrefix) return ""
        return namePrefix.replaceAll(/-$/, "")
    }

}