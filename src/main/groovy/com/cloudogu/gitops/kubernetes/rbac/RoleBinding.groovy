package com.cloudogu.gitops.kubernetes.rbac

class RoleBinding {
    String name
    String kind
    String namespace
    String roleName
    String roleKind
    List<ServiceAccountRef> serviceAccounts

    RoleBinding(String name, String namespace, String roleName, List<ServiceAccountRef> serviceAccounts) {
        if (!name?.trim()) throw new IllegalArgumentException("RoleBinding name must not be blank")
        if (!namespace?.trim()) throw new IllegalArgumentException("RoleBinding namespace must not be blank")
        if (!roleName?.trim()) throw new IllegalArgumentException("Role name must not be blank")
        if (!serviceAccounts || serviceAccounts.isEmpty()) throw new IllegalArgumentException("At least one service account is required")

        this.name = name
        this.kind = "RoleBinding"
        this.namespace = namespace
        this.roleName = roleName
        this.roleKind = "Role"
        this.serviceAccounts = serviceAccounts

        if(roleName == "cluster-admin") {
            this.kind = "ClusterRoleBinding"
            this.roleKind = "ClusterRole"
        }
    }

    Map<String, Object> toTemplateParams() {
        return [
                name           : name,
                kind           : kind,
                namespace      : namespace,
                roleName       : roleName,
                roleKind       : roleKind,
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