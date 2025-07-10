package com.cloudogu.gitops.kubernetes.rbac

class RoleBinding {
    String name
    String namespace
    String roleName
    List<ServiceAccountRef> serviceAccounts

    RoleBinding(String name, String namespace, String roleName, List<ServiceAccountRef> serviceAccounts) {
        if (!name?.trim()) throw new IllegalArgumentException("RoleBinding name must not be blank")
        if (!namespace?.trim()) throw new IllegalArgumentException("RoleBinding namespace must not be blank")
        if (!roleName?.trim()) throw new IllegalArgumentException("Role name must not be blank")
        if (!serviceAccounts || serviceAccounts.isEmpty()) throw new IllegalArgumentException("At least one service account is required")

        this.name = name
        this.namespace = namespace
        this.roleName = roleName
        this.serviceAccounts = serviceAccounts
    }

    Map<String, Object> toTemplateParams() {
        return [
                name           : name,
                namespace      : namespace,
                roleName       : roleName,
                serviceAccounts: serviceAccounts.collect { it.toMap() }
        ]
    }

    String getTemplatePath() {
        return "templates/kubernetes/rbac/rolebinding.ftl.yaml"
    }

    File getTemplateFile() {
        return new File(getTemplatePath())
    }

    File getOutputFile(File outputDir) {
        String filename = "rolebinding-${name}-${namespace}.yaml"
        return new File(outputDir, filename)
    }
}
