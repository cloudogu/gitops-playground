package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.FileSystemUtils
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j

@Slf4j
class RepoInitializationAction {
    private GitRepo repo
    private String copyFromDirectory
    Set<String> subDirsToCopy = [] as Set<String>
    private Config config
    private GitHandler gitHandler

    Closure afterCopyHook

    RepoInitializationAction(Config config, GitRepo repo,GitHandler gitHandler, String copyFromDirectory) {
        this.config = config
        this.repo = repo
        this.copyFromDirectory = copyFromDirectory
        this.gitHandler = gitHandler
    }

    /**
     * Clone repo from SCM and initialize it by copying only the configured subdirectories.
     * Afterwards we can edit these files.
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

    private FileFilter createSubdirFilter() {
        File srcRoot = new File(copyFromDirectory).canonicalFile

        // Normalize entries like "argocd", "apps/monitoring" to "argocd/" or "apps/monitoring/"
        Set<String> prefixes = subDirsToCopy.collect { String s ->
            def norm = s.replace('\\', '/')
            norm = norm.replaceAll('^/+', '').replaceAll('/+$', '')
            return norm + '/'
        } as Set<String>

        boolean hasPrefixes = !prefixes.isEmpty()

        return { File f ->
            File canon = f.canonicalFile
            String rel = srcRoot.toURI().relativize(canon.toURI()).toString()
            rel = rel.replace('\\', '/')

            // Always copy the root (copyFromDirectory itself), otherwise we can't build up the directory structure
            if (rel == '' || rel == '.') {
                return true
            }

            // Global excludes for feature templates:
            // do NOT copy anything under apps/**/templates/** into the SCM repo
            if (rel.startsWith('apps/') && rel.contains('/templates/')) {
                return false
            }

            boolean isDir = f.isDirectory()
            // For directories, always compare using a trailing slash
            String relDir = rel.endsWith('/') ? rel : rel + '/'

            // If no prefixes are configured, copy everything (except templates)
            if (!hasPrefixes) {
                return true
            }

            if (isDir) {
                // Allow a directory if it is:
                // - exactly one of the requested subdirs, or
                // - inside one of them, or
                // - a parent of one of them (needed to keep the tree structure).
                //
                // Example (subDirsToCopy = ["argocd", "apps/monitoring"]):
                //   relDir = "argocd/"          → allowed
                //   relDir = "argocd/operator/" → allowed
                //   relDir = "apps/"            → allowed (parent of "apps/monitoring/")
                //   relDir = "apps/secrets/"    → rejected
                return prefixes.any { String p ->
                    relDir == p || relDir.startsWith(p) || p.startsWith(relDir)
                }
            } else {
                // Only copy files that are directly under one of the allowed subtrees
                return prefixes.any { String p ->
                    rel.startsWith(p)
                }
            }
        } as FileFilter
    }


}