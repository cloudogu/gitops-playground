package com.cloudogu.gitops.kubernetes.argocd

import com.cloudogu.gitops.gitHandling.local.LocalRepository
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class ArgoApplication {

    final String ARGOCD = ("templates/kubernetes/argocd/application.ftl.yaml")

    String name
    String namespace
    String destinationNamespace
    String path
    String repoUrl
    String project

    private final TemplatingEngine templater = new TemplatingEngine()

    ArgoApplication(String name, String repoUrl, String namespace, String destinationNamespace, String path, String project = 'default') {
        this.name = name
        this.namespace = namespace
        this.destinationNamespace = destinationNamespace
        this.project = project
        this.repoUrl = repoUrl
        this.path = path
    }

    Map<String, Object> toTemplateParams() {
        return [
                name                : this.name,
                namespace           : this.namespace,
                project             : this.project,
                path                : this.path,
                destinationNamespace: this.destinationNamespace,
                repoUrl             : this.repoUrl
        ]
    }

    File getTemplateFile() {
        return new File(ARGOCD)
    }

    File getOutputFile(File outputDir) {
        String filename = "argocd-application-${name}-${namespace}.yaml"
        return new File(outputDir, filename)
    }

    void generate(LocalRepository repo, String subfolder) {
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