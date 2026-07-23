package com.cloudogu.gitops.utils;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.Version;
import groovy.yaml.YamlSlurper;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class TemplatingEngine {
  private static final Pattern FTL_FILE_PATTERN = Pattern.compile("\\.ftl");

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

  /** Executes template with parameters and replaces the .ftl in the file name. */
  public File replaceTemplate(File templateFile, Map<String, Object> parameters)
      throws IOException, freemarker.template.TemplateException {
    File targetFile = new File(templateFile.toString().replace(".ftl", ""));
    String rendered = template(templateFile, parameters);

    // Only write file if template has non-empty output.
    // This avoids creating empty files when the entire template is skipped via <#if>.
    if (rendered != null && !rendered.trim().isEmpty()) {
      Files.writeString(targetFile.toPath(), rendered);
    } else {
      Files.deleteIfExists(targetFile.toPath());
    }

    Files.deleteIfExists(templateFile.toPath());
    return targetFile;
  }

  /**
   * Recursively templates all .ftl files in <code>path</code>.
   *
   * <p>That is, apply {@link #replaceTemplate(java.io.File, java.util.Map)} to all files matching
   * <code>filepathMatches</code>.
   */
  public void replaceTemplates(File path, Map<String, Object> parameters)
      throws IOException, freemarker.template.TemplateException {
    replaceTemplates(path, parameters, FTL_FILE_PATTERN);
  }

  public void replaceTemplates(File path, Map<String, Object> parameters, Pattern filepathMatches)
      throws IOException, freemarker.template.TemplateException {
    try (Stream<Path> stream = Files.walk(path.toPath())) {
      List<Path> files =
          stream
              .filter(candidatePath -> filepathMatches.matcher(candidatePath.toString()).find())
              .toList();
      for (Path file : files) {
        replaceTemplate(file.toFile(), parameters);
      }
    }
  }

  public static Map<String, Object> templateToMap(String filePath, Map<String, Object> parameters) {
    String hydratedString;
    try {
      hydratedString = new TemplatingEngine().template(new File(filePath), parameters);
    } catch (Exception e) {
      throw new RuntimeException("Failed to hydrate template to map: " + filePath, e);
    }

    if (hydratedString == null || hydratedString.trim().isEmpty()) {
      // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
      return Collections.emptyMap();
    }
    return MapUtils.asStringObjectMap(new YamlSlurper().parseText(hydratedString));
  }

  /** Executes template and writes to targetFile, keeping the template file. */
  public File template(File templateFile, File targetFile, Map<String, Object> parameters)
      throws IOException, freemarker.template.TemplateException {
    Template template = prepareTemplate(templateFile);
    try (BufferedWriter writer = Files.newBufferedWriter(targetFile.toPath())) {
      template.process(parameters, writer);
    }
    return targetFile;
  }

  public String template(File templateFile, Map<String, Object> parameters)
      throws IOException, freemarker.template.TemplateException {
    Template template = prepareTemplate(templateFile);
    StringWriter writer = new StringWriter();
    template.process(parameters, writer);
    return writer.toString();
  }

  public String template(String template, Map<String, Object> parameters)
      throws IOException, freemarker.template.TemplateException {
    StringWriter writer = new StringWriter();
    Template templateObj = new Template("template", new StringReader(template), engine);
    templateObj.process(parameters, writer);
    return writer.toString();
  }

  protected Template prepareTemplate(File templateFile) throws IOException {
    if (!templateFile.getName().contains(".ftl")) {
      throw new IllegalArgumentException("File must contain .ftl to be a template");
    }

    engine.setDirectoryForTemplateLoading(templateFile.getParentFile());
    return engine.getTemplate(templateFile.getName());
  }
}
