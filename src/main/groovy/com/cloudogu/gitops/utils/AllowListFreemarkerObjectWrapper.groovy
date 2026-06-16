package com.cloudogu.gitops.utils

import freemarker.template.*

class AllowListFreemarkerObjectWrapper extends DefaultObjectWrapper {

	Set<String> allowlist

	AllowListFreemarkerObjectWrapper(Version freemarkerVersion, Set<String> allowlist) {
		super(freemarkerVersion)
		this.allowlist = allowlist
	}

	TemplateHashModel getStaticModels() {
		final TemplateHashModel originalStaticModels = super.getStaticModels()
		final Set<String> allowlistCopy = this.allowlist

		return new TemplateHashModel() {
			@Override
			TemplateModel get(String key) throws TemplateModelException {
				if (allowlistCopy.contains(key)) {
					return originalStaticModels.get(key)
				}
				return null
			}

			@Override
			boolean isEmpty() throws TemplateModelException {
				return allowlistCopy.isEmpty()
			}
		}
	}
}