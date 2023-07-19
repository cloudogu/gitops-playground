package com.cloudogu.gitops.utils

import freemarker.template.Configuration
import freemarker.template.Version

class TemplatingEngine {
    private Configuration engine

    TemplatingEngine(Configuration engine = null) {
        this.engine = engine ?: new Configuration(new Version("2.3.32"))
    }

    File replaceTemplate(File file, Map parameters) {
        if (!file.name.contains(".ftl")) {
            throw new RuntimeException("File must contain .ftl to be a template")
        }

        engine.setDirectoryForTemplateLoading(file.parentFile)

        def targetFile = new File(file.toString().replace(".ftl", ""))
        def templ = engine.getTemplate(file.name)
        templ.process(parameters, targetFile.newWriter())

        file.delete()

        return targetFile
    }
}
