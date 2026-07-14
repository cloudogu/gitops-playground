package com.cloudogu.gitops.utils;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.Version;
import groovy.yaml.YamlSlurper;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

@SuppressWarnings({"rawtypes", "unchecked"})
public class TemplatingEngine {
    private final Configuration engine;

    public TemplatingEngine() {
        this(null);
    }

    public TemplatingEngine(Configuration engine) {
        if (engine == null) {
            engine = new Configuration(new Version("2.3.32"));
        }
        this.engine = engine;
        try {
            this.engine.setSharedVariable("nullToEmpty", "");
        } catch (Exception e) {
            throw new RuntimeException("Failed to set shared variable in freemarker configuration", e);
        }
    }

    /**
     * Executes template with parameters and replaces the .ftl in the file name.
     */
    public File replaceTemplate(File templateFile, Map parameters) {
        File targetFile = new File(templateFile.toString().replace(".ftl", ""));
        String rendered = template(templateFile, parameters);

        // Only write file if template has non-empty output.
        // This avoids creating empty files when the entire template is skipped via <#if>.
        if (rendered != null && !rendered.trim().isEmpty()) {
            try {
                Files.writeString(targetFile.toPath(), rendered);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write to file: " + targetFile, e);
            }
        } else {
            targetFile.delete();
        }

        templateFile.delete();
        return targetFile;
    }

    /**
     * Recursively templates all .ftl files in <code>path</code>.
     * <p>
     * That is, apply {@link #replaceTemplate(java.io.File, java.util.Map)} to all files matching <code>filepathMatches</code>.
     */
    public void replaceTemplates(File path, Map parameters) {
        replaceTemplates(path, parameters, Pattern.compile("\\.ftl"));
    }

    public void replaceTemplates(File path, Map parameters, Pattern filepathMatches) {
        try (var stream = Files.walk(path.toPath())) {
            stream.filter(p -> filepathMatches.matcher(p.toString()).find())
                  .forEach(p -> replaceTemplate(p.toFile(), parameters));
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk path: " + path, e);
        }
    }

    public static Map templateToMap(String filePath, Map parameters) {
        String hydratedString = new TemplatingEngine().template(new File(filePath), parameters);

        if (hydratedString == null || hydratedString.trim().isEmpty()) {
            // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
            return Collections.emptyMap();
        }
        return (Map) new YamlSlurper().parseText(hydratedString);
    }

    /**
     * Executes template and writes to targetFile, keeping the template file.
     */
    public File template(File templateFile, File targetFile, Map parameters) {
        try {
            Template template = prepareTemplate(templateFile);
            try (var writer = Files.newBufferedWriter(targetFile.toPath())) {
                template.process(parameters, writer);
            }
            return targetFile;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process template " + templateFile + " to " + targetFile, e);
        }
    }

    public String template(File templateFile, Map parameters) {
        try {
            Template template = prepareTemplate(templateFile);
            StringWriter writer = new StringWriter();
            template.process(parameters, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to process template " + templateFile, e);
        }
    }

    public String template(String template, Map parameters) {
        try {
            StringWriter writer = new StringWriter();
            Template templateObj = new Template("template", new StringReader(template), engine);
            templateObj.process(parameters, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to process template string", e);
        }
    }

    protected Template prepareTemplate(File templateFile) {
        if (!templateFile.getName().contains(".ftl")) {
            throw new RuntimeException("File must contain .ftl to be a template");
        }

        try {
            engine.setDirectoryForTemplateLoading(templateFile.getParentFile());
            return engine.getTemplate(templateFile.getName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare template " + templateFile, e);
        }
    }
}
