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
     * Clone repo from SCM and initialize it with default basic files. Afterwards we can edit these files.
     */
    void initLocalRepo() {
        repo.cloneRepo()

        log.debug("Initializing repo ${repo.repoTarget} from ${copyFromDirectory} with subdirs: ${subDirsToCopy}")
        repo.copyDirectoryContents(copyFromDirectory, createSubdirFilter())

        replaceTemplates()
    }

    void replaceTemplates() {
        Map<String, Object> templateModel = buildTemplateValues(config)
        repo.replaceTemplates(templateModel)
    }

    GitRepo getRepo() {
        return repo
    }

    /**
     * Erzeugt einen FileFilter, der nur Dateien unterhalb der gewünschten
     * Subdirs (z.B. "argocd/", "apps/monitoring/") durchlässt.
     * Die Verzeichnisstruktur relativ zu copyFromDirectory bleibt dabei erhalten.
     */
    private FileFilter createSubdirFilter() {
        File srcRoot = new File(copyFromDirectory).canonicalFile

        // "argocd" -> "argocd/", "apps/monitoring" -> "apps/monitoring/"
        Set<String> prefixes = subDirsToCopy.collect { String s ->
            s.endsWith('/') ? s : s + '/'
        } as Set<String>

        return { File f ->
            // Verzeichnisse immer kopieren, sonst bricht die Baumstruktur auseinander
            if (f.isDirectory()) {
                return true
            }

            File canon = f.canonicalFile
            String rel = srcRoot.toURI().relativize(canon.toURI()).toString()
            rel = rel.replace('\\', '/')

            // rel sieht z.B. so aus:
            // "argocd/operator/argocd.yaml" oder "apps/monitoring/rbac/...yaml"
            for (String p : prefixes) {
                if (rel.startsWith(p)) {
                    return true   // Datei gehört zu einem gewünschten Subtree
                }
            }
            return false            // alle anderen Dateien werden nicht kopiert
        } as FileFilter
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