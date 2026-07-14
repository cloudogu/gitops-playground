package com.cloudogu.gitops.utils;

import freemarker.template.*;
import java.util.Set;

public class AllowListFreemarkerObjectWrapper extends DefaultObjectWrapper {

    private final Set<String> allowlist;

    public AllowListFreemarkerObjectWrapper(Version freemarkerVersion, Set<String> allowlist) {
        super(freemarkerVersion);
        this.allowlist = allowlist;
    }

    @Override
    public TemplateHashModel getStaticModels() {
        final TemplateHashModel originalStaticModels = super.getStaticModels();
        final Set<String> allowlistCopy = this.allowlist;

        return new TemplateHashModel() {
            @Override
            public TemplateModel get(String key) throws TemplateModelException {
                if (allowlistCopy.contains(key)) {
                    return originalStaticModels.get(key);
                }
                return null;
            }

            @Override
            public boolean isEmpty() throws TemplateModelException {
                return allowlistCopy.isEmpty();
            }
        };
    }
}
