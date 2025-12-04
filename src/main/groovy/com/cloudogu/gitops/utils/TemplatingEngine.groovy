package com.cloudogu.gitops.utils

import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.Version
import groovy.yaml.YamlSlurper

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern 

class TemplatingEngine {
    private Configuration engine

    TemplatingEngine(Configuration engine = null) {
        def configuration = new Configuration(new Version("2.3.32"))
        this.engine = engine ?: configuration
        this.engine.setSharedVariable("nullToEmpty", '');
    }

    /**
     * Executes template with parameters and replaces the .ftl in the file name.
     */
    File replaceTemplate(File templateFile, Map parameters) {
        def targetFile = new File(templateFile.toString().replace(".ftl", ""))
        def rendered = template(templateFile, parameters)

        // Only write file if template has non-empty output.
        // This avoids creating empty files when the entire template is skipped via <#if>.
        if (rendered?.trim()) {
            targetFile.text = rendered
        } else {
            targetFile.delete()
        }

        templateFile.delete()
        return targetFile
    }

    /**
     * Recursively templates all .ftl files in <code>path</code>.
     *
     * That is, apply {@link #replaceTemplate(java.io.File, java.util.Map)} to all files matching <code>filepathMatches</code>.  
     */
    void replaceTemplates(File path, Map parameters, Pattern filepathMatches = ~/\.ftl/) {
        Files.walk(path.toPath())
                .filter { filepathMatches.matcher(it.toString()).find() }
                .each { Path it -> replaceTemplate(it.toFile(), parameters) }
    }

    static Map templateToMap(String filePath, Map parameters) {
        def hydratedString = new TemplatingEngine().template(new File(filePath), parameters)

        if (hydratedString.trim().isEmpty()) {
            // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
            return [:]
        }
        return new YamlSlurper().parseText(hydratedString) as Map
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

    String template(String template, Map parameters) {
        StringWriter writer = new StringWriter()
        Template templateObj = new Template("template", new StringReader(template), engine)
        templateObj.process(parameters, writer)
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