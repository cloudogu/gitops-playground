package com.cloudogu.gitops.cli

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.context.ApplicationContext
import picocli.CommandLine.Option as CliOption

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/**
 * Generates:
 *   - docs/configuration.schema.json  (JSON schema for config file validation)
 *   - docs/configuration.md           (human-readable reference of all CLI + config options)
 *
 * Passing '-' as argument prints the JSON schema to stdout (original behaviour, used in tests).
 * JsonSchemaGeneratorTest ensures that the schema is kept up to date.
 *
 * @see com.cloudogu.gitops.config.Config
 */
class GenerateJsonSchema {

    static final String SCHEMA_FILE = 'docs/configuration.schema.json'
    static final String DOCS_FILE   = 'docs/Configuration.md'

    static void main(String[] args) {
        ObjectNode jsonSchema = ApplicationContext.run().getBean(JsonSchemaGenerator).createSchema()
        String prettyJson = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchema)

        if (args.length > 0 && args[0] == '-') {
            println(prettyJson)
        } else {
            new File(SCHEMA_FILE).setText(prettyJson)
            println "Wrote schema to ${SCHEMA_FILE}"

            new File(DOCS_FILE).setText(generateDocs())
            println "Wrote documentation to ${DOCS_FILE}"
        }
    }

    static String generateDocs() {
        Config config = new Config()
        StringBuilder md = new StringBuilder()

        md << '# Overview of all CLI and config options\n\n'
        md << 'All options can be set via a [config file](./configuration.schema.json). '
        md << 'Most options are also available as CLI parameters.\n\n'

        List<Field> topFields = schemaFields(Config).findAll { Field field -> field.name !in ['features', 'stages'] }

        // Table of contents
        md << '## Table of Contents\n\n'
        topFields.each { f -> md << "- [${sectionTitle(f.name)}](#${anchor(f.name)})\n" }
        md << '- [Features](#features)\n'
        schemaFields(Config.FeaturesSchema).each { f ->
            md << "  - [${sectionTitle(f.name)}](#feature-${anchor(f.name)})\n"
        }
        md << '\n'

        // Top-level sections
        topFields.each { field ->
            field.accessible = true
            md << "## ${sectionTitle(field.name)}\n\n"
            md << buildTable(field.get(config), field.type, field.name)
        }

        // Features sub-sections
        md << '## Features\n\n'
        md << 'Configuration of optional features supported by gitops-playground.\n\n'
        schemaFields(Config.FeaturesSchema).each { field ->
            field.accessible = true
            md << "### Feature: ${sectionTitle(field.name)}\n\n"
            md << buildTable(field.get(config.features), field.type, "features.${field.name}")
        }

        return md.toString()
    }

    static String buildTable(Object instance, Class clazz, String prefix) {
        List<Map> rows = collectRows(instance, clazz, prefix)
        if (!rows) { return '' }

        StringBuilder sb = new StringBuilder()
        sb << '| CLI | Config key | Type | Default | Description |\n'
        sb << '| :--- | :--- | :--- | :--- | :--- |\n'
        rows.each { Map r ->
            sb << "| ${r.cli} | `${r.key}` | ${r.type} | `${r.default}` | ${r.desc} |\n"
        }
        sb << '\n'
        return sb.toString()
    }

    static List<Map> collectRows(Object instance, Class clazz, String prefix) {
        List<Map> rows = []
        allFields(clazz).each { Field field ->
            if (isInternalField(field)) { return }

            JsonPropertyDescription jsonDesc = field.getAnnotation(JsonPropertyDescription)
            CliOption cliOpt                = field.getAnnotation(CliOption)
            if (!jsonDesc && !cliOpt) { return }

            field.accessible = true
            String key = "${prefix}.${field.name}"

            if (isSchemaType(field.type)) {
                rows.addAll(collectRows(safeGet(field, instance), field.type, key))
            } else {
                rows << [
                    cli    : cliOpt ? cliOpt.names().collect { String opt -> "`${opt}`" }.join(', ') : '-',
                    key    : key,
                    type   : typeName(field),
                    default: formatDefault(safeGet(field, instance)),
                    desc   : (jsonDesc?.value() ?: '-').replaceAll(/\s*\n\s*/, ' ').trim(),
                ]
            }
        }
        return rows
    }

    static List<Field> allFields(Class clazz) {
        List<Field> fields = []
        for (Class c = clazz; c && c != Object; c = c.superclass) {
            fields.addAll(c.declaredFields)
        }
        return fields
    }

    static List<Field> schemaFields(Class clazz) {
        return clazz.declaredFields.findAll { Field field -> !isInternalField(field) && isSchemaType(field.type) }
    }

    static boolean isInternalField(Field field) {
        if (field.synthetic) { return true }
        if (Modifier.isStatic(field.modifiers)) { return true }
        if (field.getAnnotation(JsonIgnore)) { return true }
        return (field.name in ['metaClass', '$staticClassInfo', '__$stMC'])
    }

    static boolean isSchemaType(Class type) {
        return type.name.startsWith('com.cloudogu.gitops')
    }

    static Object safeGet(Field field, Object instance) {
        try { field.accessible = true; return field.get(instance) } catch (e) { return null }
    }

    static String formatDefault(Object value) {
        switch (value) {
            case null:       return '-'
            case Map:        return value ? '[:]' : value.toString()
            case Collection: return value ? '[]'  : value.toString()
            default:         return value.toString()
        }
    }

    static String typeName(Field field) {
        Class t = field.type
        if (t == Boolean || t == boolean) { return 'Boolean' }
        if (t == Integer || t == int)     { return 'Integer' }
        if (t == String)                  { return 'String' }
        if (Map.isAssignableFrom(t))      { return 'Map' }
        if (t.enum)                       { return t.simpleName }
        if (field.genericType instanceof ParameterizedType) {
            ParameterizedType pt = field.genericType as ParameterizedType
            String args = pt.actualTypeArguments.collect { it ->
                it instanceof Class ? (it as Class).simpleName : it.toString()
            }.join(', ')
            return "${(pt.rawType as Class).simpleName}&lt;${args}&gt;"
        }
        return t.simpleName
    }

    static String sectionTitle(String name) {
        return name.replaceAll(/([A-Z])/, ' $1').trim().with { String title -> title[0].toUpperCase() + title[1..-1] }
    }

    static String anchor(String name) {
        return sectionTitle(name).toLowerCase().replaceAll(/\s+/, '-')
    }
}

