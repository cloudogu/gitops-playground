package com.cloudogu.gitops.utils

import freemarker.template.DefaultObjectWrapper
import freemarker.template.TemplateHashModel
import freemarker.template.TemplateModel
import freemarker.template.TemplateModelException
import freemarker.template.Version


class AllowlistFreemarkerObjectWrapper extends DefaultObjectWrapper {

    private Set<String> allowlist = []

    AllowlistFreemarkerObjectWrapper(Version freemarkerVersion, Set<String> allowlist) {
        super(freemarkerVersion)
        this.allowlist = allowlist
    }

    @Override
    public TemplateHashModel getStaticModels() {
        // Hole alle statischen Modelle
        TemplateHashModel staticModels = super.getStaticModels()

        // Filtere die Modelle basierend auf der Allowlist
        return new TemplateHashModel() {
            @Override
            TemplateModel get(String key) throws TemplateModelException {
                if (allowlist.contains(key)) {
                    return staticModels.get(key)
                }
                return null
            }

            @Override
            boolean isEmpty() throws TemplateModelException {
                return allowlist.isEmpty()
            }
        }
    }
}