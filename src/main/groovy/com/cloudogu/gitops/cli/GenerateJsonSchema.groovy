package com.cloudogu.gitops.cli

import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.context.ApplicationContext 
/**
 * Generates the JSON Schema for the configuration file and prints it to docs/configuration.schema.json.
 * Passing '-' as parameter prints the schema to stdout
 * JsonSchemaGeneratorTest ensures that this is updated.
 *
 * @see com.cloudogu.gitops.config.schema.Schema
 */
class GenerateJsonSchema {
    static void main(String[] args) {
        ObjectNode jsonSchema = ApplicationContext.run().getBean(JsonSchemaGenerator).createSchema()
        def prettyJson = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchema)

        if (args.length > 0 && args[0] == "-") {
            println(prettyJson)
        } else {
            def schemaFile = 'docs/configuration.schema.json'
            new File(schemaFile).setText(prettyJson)
            println "Wrote schema to file ${schemaFile}"
        }
    }
}
