package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.scmm.ScmmRepo
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
    private ScmmRepo repo

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

    RbacDefinition withRepo(ScmmRepo repo) {
        this.repo = repo
        return this
    }

    void generate() {
        validateInputs()

        log.debug("Generating RBAC for name='${name}', namespace='${namespace}', subfolder='${subfolder}'")

        def outputDir = Path.of(repo.absoluteLocalRepoTmpDir, subfolder).toFile()
        outputDir.mkdirs()

        def role = new Role(name, namespace, variant)
        def binding = new RoleBinding(name, namespace, name, serviceAccounts)

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

    private void validateInputs() {
        if (!repo) {
            throw new IllegalStateException("SCMM repo must be set using withRepo() before calling generate()")
        }
        if (!name?.trim()) {
            throw new IllegalStateException("RBAC definition requires a non-empty name")
        }
        if (!namespace?.trim()) {
            throw new IllegalStateException("RBAC definition requires a non-empty namespace")
        }
        if (!serviceAccounts || serviceAccounts.isEmpty()) {
            throw new IllegalStateException("RBAC definition requires at least one service account")
        }
    }
}
