package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j

@Slf4j
class RepoInitializationAction {
    private GitRepo repo
    private String copyFromDirectory
    Set<String> subDirsToCopy = [] as Set<String>
    private Config config
    private GitHandler gitHandler

    RepoInitializationAction(Config config, GitRepo repo,GitHandler gitHandler, String copyFromDirectory) {
        this.config = config
        this.repo = repo
        this.copyFromDirectory = copyFromDirectory
        this.gitHandler = gitHandler
    }

    /**
     * Clone repo from SCM and initialize it by copying only the configured subdirectories.
     * Afterwards we can edit these files.
     * Clone repo from SCM and initialize it with default basic files. Afterwards we can edit these files.
     */
    void initLocalRepo() {
        repo.cloneRepo()

        log.debug("Initializing repo ${repo.repoTarget} from ${copyFromDirectory} with subdirs: ${subDirsToCopy}")
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
                argocd    : [host: config.features.argocd.url ? new URL(config.features.argocd.url).host : ""],
                scm      : [
                        baseUrl : this.repo.gitProvider.url,
                        host    : this.repo.gitProvider.host,
                        protocol: this.repo.gitProvider.protocol,
                        repoUrl : this.repo.gitProvider.repoPrefix(),
                        centralScmUrl: this.gitHandler.central?.repoPrefix() ?: ''
                ],
                config    : config,
                // Allow for using static classes inside the templates
                statics   : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ] as Map<String, Object>

        return model
    }
}