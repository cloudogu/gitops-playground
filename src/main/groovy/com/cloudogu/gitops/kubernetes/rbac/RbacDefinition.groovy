package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.local.GitRepo
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j

import java.nio.file.Path

@Slf4j
class RbacDefinition {

    private final Role.Variant variant
    private String name
    private String namespace
    private List<ServiceAccountRef> serviceAccounts = []
    private String subfolder = "rbac"
    private GitRepo repo
    private Config config

    private final TemplatingEngine templater = new TemplatingEngine()

    RbacDefinition(Role.Variant variant) {
        this.variant = variant
    }

    RbacDefinition withName(String name) {
        this.name = name
        return this
    }

    RbacDefinition withNamespace(String namespace) {
        this.namespace = namespace
        return this
    }

    RbacDefinition withServiceAccounts(List<ServiceAccountRef> accounts) {
        this.serviceAccounts = accounts
        return this
    }

    RbacDefinition withServiceAccountsFrom(String saNamespace, List<String> saNames) {
        return withServiceAccounts(ServiceAccountRef.fromNames(saNamespace, saNames))
    }

    RbacDefinition withSubfolder(String subfolder) {
        this.subfolder = subfolder
        return this
    }

    RbacDefinition withRepo(GitRepo repo) {
        this.repo = repo
        return this
    }

    RbacDefinition withConfig(Config config) {
        this.config = config
        return this
    }

    void generate() {
        if (!repo) {
            throw new IllegalStateException("SCMM repo must be set using withRepo() before calling generate()")
        }

        def role = new Role(name, namespace, variant, config)
        def binding = new RoleBinding(name, namespace, name, serviceAccounts)

        log.trace("Generating RBAC for name='${name}', namespace='${namespace}', subfolder='${subfolder}'")

        def outputDir = Path.of(repo.absoluteLocalRepoTmpDir, subfolder).toFile()
        outputDir.mkdirs()

        templater.template(
                role.getTemplateFile(),
                role.getOutputFile(outputDir),
                role.toTemplateParams()
        )

        templater.template(
                binding.getTemplateFile(),
                binding.getOutputFile(outputDir),
                binding.toTemplateParams()
        )
    }

}
