package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.utils.TemplatingEngine

import java.nio.file.Path
import groovy.util.logging.Slf4j

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

        log.trace("Generating RBAC for name='${name}', namespace='${namespace}', subfolder='${subfolder}'")

        File outputDir = Path.of(repo.absoluteLocalRepoTmpDir, subfolder).toFile()
        outputDir.mkdirs()

        generateRole(outputDir)

        generateRoleBinding(outputDir)
    }

    private void generateRole(File outputDir) {
        if(variant == Role.Variant.CLUSTER_ADMIN) {
            log.trace("Skipping creation of ClusterRole cluster-admin")
            return
        }

        def role = new Role(name, namespace, variant, config)

        templater.template(
                role.getTemplateFile(),
                role.getOutputFile(outputDir),
                role.toTemplateParams()
        )
    }

    private void generateRoleBinding(File outputDir) {
        String roleName = name
        if(variant == Role.Variant.CLUSTER_ADMIN) {
            roleName = "cluster-admin"
        }
        def binding = new RoleBinding(name, namespace, roleName, serviceAccounts)

        templater.template(
                binding.getTemplateFile(),
                binding.getOutputFile(outputDir),
                binding.toTemplateParams()
        )
    }

}