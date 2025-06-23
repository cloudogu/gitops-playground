package com.cloudogu.gitops.kubernetes.rbac

class Role {
    String name
    String namespace
    Variant variant

    Role(String name, String namespace, Variant variant) {
        this.name = name
        this.namespace = namespace
        this.variant = variant
    }

    enum Variant {
        ARGOCD("templates/kubernetes/rbac/argocd-role.ftl.yaml");

        final String templatePath

        Variant(String templatePath) {
            this.templatePath = templatePath
        }
    }

    Map<String, Object> toTemplateParams() {
        return [
                name     : name,
                namespace: namespace
        ]
    }

    File getTemplateFile() {
        return new File(variant.getTemplatePath())
    }

    File getOutputFile(File outputDir) {
        String filename = "role-${name}-${namespace}.yaml"
        return new File(outputDir, filename)
    }
}
