package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.config.Config

class Role {
    String name
    String namespace
    Variant variant
    Config config

    Role(String name, String namespace, Variant variant, Config config) {
        if (!name?.trim()) throw new IllegalArgumentException("Role name must not be blank")
        if (!namespace?.trim()) throw new IllegalArgumentException("Role namespace must not be blank")
        if (!variant) throw new IllegalArgumentException("Role variant must not be null")
        if (!config) throw new IllegalArgumentException("Config must not be null")

        this.name = name
        this.namespace = namespace
        this.variant = variant
        this.config = config
    }

    enum Variant {
        ARGOCD("templates/kubernetes/rbac/argocd-role.ftl.yaml")

        final String templatePath

        Variant(String templatePath) {
            this.templatePath = templatePath
        }
    }

    Map<String, Object> toTemplateParams() {
        return [
                name     : name,
                namespace: namespace,
                config   : config
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