package com.cloudogu.gitops.utils

import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.Version

class TemplatingEngine {
    private Configuration engine

    TemplatingEngine(Configuration engine = null) {
        this.engine = engine ?: new Configuration(new Version("2.3.32"))
    }

    /**
     * Executes template with parameters and replaces the .ftl in the file name.
     */
    File replaceTemplate(File templateFile, Map parameters) {
        def targetFile = new File(templateFile.toString().replace(".ftl", ""))

        template(templateFile, targetFile, parameters)

        templateFile.delete()

        return targetFile
    }

    /**
     * Executes template and writes to targetFile, keeping the template file.
     */
    File template(File templateFile, File targetFile, Map parameters) {
        Template template = prepareTemplate(templateFile)
        template.process(parameters, targetFile.newWriter())

        return targetFile
    }

    String template(File templateFile, Map parameters) {
        Template template = prepareTemplate(templateFile)

        StringWriter writer = new StringWriter()
        template.process(parameters, writer)
        
        return writer.toString()
    }

    protected Template prepareTemplate(File templateFile) {
        if (!templateFile.name.contains(".ftl")) {
            throw new RuntimeException("File must contain .ftl to be a template")
        }

        engine.setDirectoryForTemplateLoading(templateFile.parentFile)

        def template = engine.getTemplate(templateFile.name)
        template
    }
}
