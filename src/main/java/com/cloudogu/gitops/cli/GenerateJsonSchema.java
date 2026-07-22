package com.cloudogu.gitops.cli;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.schema.JsonSchemaGenerator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.micronaut.context.ApplicationContext;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
public class GenerateJsonSchema {

  public static final String SCHEMA_FILE = "docs/configuration.schema.json";
  public static final String DOCS_FILE = "docs/Configuration.md";

  private static final Pattern UPPERCASE_LETTER =
      Pattern.compile("\\p{Lu}", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern WHITESPACE_RUN =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern WHITESPACE_AROUND_NEWLINE =
      Pattern.compile("\\s*\\n\\s*", Pattern.UNICODE_CHARACTER_CLASS);

  public static void main(String[] args) {
    try {
      ObjectNode jsonSchema =
          ApplicationContext.run().getBean(JsonSchemaGenerator.class).createSchema();
      String prettyJson =
          new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchema);

      if (args.length > 0 && "-".equals(args[0])) {
        System.out.println(prettyJson);
      } else {
        Files.writeString(new File(SCHEMA_FILE).toPath(), prettyJson);
        log.info("Wrote schema to {}", SCHEMA_FILE);

        Files.writeString(new File(DOCS_FILE).toPath(), generateDocs());
        log.info("Wrote documentation to {}", DOCS_FILE);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to generate schema/documentation files", e);
    }
  }

  public static String generateDocs() {
    Config config = new Config();
    StringBuilder md = new StringBuilder();

    md.append("# Overview of all CLI and config options\n\n");
    md.append("All options can be set via a [config file](./configuration.schema.json). ");
    md.append("Most options are also available as CLI parameters.\n\n");

    List<Field> topFields =
        schemaFields(Config.class).stream()
            .filter(field -> !Set.of("features", "stages").contains(field.getName()))
            .toList();

    // Table of contents and top-level sections are built from the same fields in one pass.
    StringBuilder toc = new StringBuilder();
    StringBuilder sections = new StringBuilder();
    for (Field field : topFields) {
      toc.append("- [")
          .append(sectionTitle(field.getName()))
          .append("](#")
          .append(anchor(field.getName()))
          .append(")\n");

      field.setAccessible(true);
      sections.append("## ").append(sectionTitle(field.getName())).append("\n\n");
      try {
        sections.append(buildTable(field.get(config), field.getType(), field.getName()));
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    md.append("## Table of Contents\n\n");
    md.append(toc);
    md.append("- [Tools](#tools)\n");
    for (Field f : schemaFields(Config.FeaturesSchema.class)) {
      md.append("  - [")
          .append(sectionTitle(f.getName()))
          .append("](#tools-")
          .append(anchor(f.getName()))
          .append(")\n");
    }
    md.append("\n");

    md.append(sections);

    // Tools sub-sections
    md.append("## Tools\n\n");
    md.append("Configuration of optional tools supported by gitops-playground.\n\n");
    for (Field field : schemaFields(Config.FeaturesSchema.class)) {
      field.setAccessible(true);
      md.append("### Tool: ").append(sectionTitle(field.getName())).append("\n\n");
      try {
        md.append(
            buildTable(
                field.get(config.getFeatures()), field.getType(), "features." + field.getName()));
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    return md.toString();
  }

  public static String buildTable(Object instance, Class<?> clazz, String prefix) {
    List<Map<String, String>> rows = collectRows(instance, clazz, prefix);
    if (rows.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("| CLI | Config key | Type | Default | Description |\n");
    sb.append("| :--- | :--- | :--- | :--- | :--- |\n");
    for (Map<String, String> r : rows) {
      sb.append("| ")
          .append(r.get("cli"))
          .append(" | `")
          .append(r.get("key"))
          .append("` | ")
          .append(r.get("type"))
          .append(" | `")
          .append(r.get("default"))
          .append("` | ")
          .append(r.get("desc"))
          .append(" |\n");
    }
    sb.append("\n");
    return sb.toString();
  }

  public static List<Map<String, String>> collectRows(
      Object instance, Class<?> clazz, String prefix) {
    List<Map<String, String>> rows = new ArrayList<>();
    for (Field field : allFields(clazz)) {
      if (isInternalField(field)) {
        continue;
      }
      collectFieldRows(field, instance, prefix, rows);
    }
    return rows;
  }

  private static void collectFieldRows(
      Field field, Object instance, String prefix, List<Map<String, String>> rows) {
    field.setAccessible(true);
    String key = prefix + "." + field.getName();

    if (isSchemaType(field.getType())) {
      rows.addAll(collectRows(safeGet(field, instance), field.getType(), key));
      return;
    }

    JsonPropertyDescription jsonDesc = field.getAnnotation(JsonPropertyDescription.class);
    Option cliOpt = field.getAnnotation(Option.class);
    if (jsonDesc == null && cliOpt == null) {
      return;
    }

    Map<String, String> r = new HashMap<>();
    if (cliOpt != null) {
      r.put(
          "cli",
          Arrays.stream(cliOpt.names())
              .map(opt -> "`" + opt + "`")
              .collect(Collectors.joining(", ")));
    } else {
      r.put("cli", "-");
    }
    r.put("key", key);
    r.put("type", typeName(field));
    r.put("default", formatDefault(safeGet(field, instance)));
    r.put(
        "desc",
        WHITESPACE_AROUND_NEWLINE
            .matcher(jsonDesc != null ? jsonDesc.value() : "-")
            .replaceAll(" ")
            .trim());
    rows.add(r);
  }

  public static List<Field> allFields(Class<?> clazz) {
    List<Field> fields = new ArrayList<>();
    for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
      fields.addAll(Arrays.asList(c.getDeclaredFields()));
    }
    return fields;
  }

  public static List<Field> schemaFields(Class<?> clazz) {
    return Arrays.stream(clazz.getDeclaredFields())
        .filter(field -> !isInternalField(field) && isSchemaType(field.getType()))
        .toList();
  }

  public static boolean isInternalField(Field field) {
    if (field.isSynthetic()) {
      return true;
    }
    if (Modifier.isStatic(field.getModifiers())) {
      return true;
    }
    if (field.isAnnotationPresent(JsonIgnore.class)) {
      return true;
    }
    return Set.of("metaClass", "$staticClassInfo", "__$stMC").contains(field.getName());
  }

  public static boolean isSchemaType(Class<?> type) {
    return type.getName().startsWith("com.cloudogu.gitops");
  }

  public static Object safeGet(Field field, Object instance) {
    try {
      field.setAccessible(true);
      return field.get(instance);
    } catch (Exception e) {
      log.debug("Failed to read field {} for documentation generation", field.getName(), e);
      return null;
    }
  }

  public static String formatDefault(Object value) {
    if (value == null) {
      return "-";
    }
    if (value instanceof Map<?, ?> map) {
      return map.isEmpty() ? "[:]" : value.toString();
    }
    if (value instanceof Collection<?> collection) {
      return collection.isEmpty() ? "[]" : value.toString();
    }
    return value.toString();
  }

  public static String typeName(Field field) {
    Class<?> t = field.getType();
    if (t == Boolean.class || t == boolean.class) {
      return "Boolean";
    }
    if (t == Integer.class || t == int.class) {
      return "Integer";
    }
    if (t == String.class) {
      return "String";
    }
    if (Map.class.isAssignableFrom(t)) {
      return "Map";
    }
    if (t.isEnum()) {
      return t.getSimpleName();
    }
    if (field.getGenericType() instanceof ParameterizedType pt) {
      String args =
          Arrays.stream(pt.getActualTypeArguments())
              .map(it -> it instanceof Class ? ((Class<?>) it).getSimpleName() : it.toString())
              .collect(Collectors.joining(", "));
      return ((Class<?>) pt.getRawType()).getSimpleName() + "&lt;" + args + "&gt;";
    }
    return t.getSimpleName();
  }

  public static String sectionTitle(String name) {
    String title = UPPERCASE_LETTER.matcher(name).replaceAll(" $0").trim();
    return Character.toUpperCase(title.charAt(0)) + title.substring(1);
  }

  public static String anchor(String name) {
    return WHITESPACE_RUN.matcher(sectionTitle(name).toLowerCase(Locale.ROOT)).replaceAll("-");
  }
}
