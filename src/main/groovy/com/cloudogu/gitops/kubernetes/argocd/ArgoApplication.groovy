package com.cloudogu.gitops.kubernetes.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class ArgoApplication {

    final String ARGOCD = ("templates/kubernetes/argocd/application.ftl.yaml")

    String name
    String namespace
    String path
    String repoUrl
    String project

    private final TemplatingEngine templater = new TemplatingEngine()

    ArgoApplication(String name, String repoUrl, String project, String path, String namespace) {
        this.name = name
        this.repoUrl = repoUrl
        this.project = project
        this.path = path
        this.namespace = namespace
    }

    Map<String, Object> toTemplateParams() {
        return [
                name      : name,
                namespace : namespace,
                project   : project,
                path      : path,
                namePrefix: new Config().application.namePrefix,
                repoUrl   : repoUrl
        ]
    }

    File getTemplateFile() {
        return new File(ARGOCD)
    }

    File getOutputFile(File outputDir) {
        String filename = "argocd-application-${name}-${namespace}.yaml"
        return new File(outputDir, filename)
    }

    void generate(ScmmRepo repo, String subfolder) {
        log.debug("Generating ArgoCDApplication for name='${name}', namespace='${namespace}''")

        def outputDir = Path.of(repo.absoluteLocalRepoTmpDir, subfolder).toFile()
        outputDir.mkdirs()

        templater.template(
                this.getTemplateFile(),
                this.getOutputFile(outputDir),
                this.toTemplateParams()
        )

    }

}