package com.cloudogu.gitops.cli

import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micronaut.context.ApplicationContext

/**
 * Generates the JSON Schema for the configuration file and prints it to stdout.
 * We save the schema to docs/configuration.schema.json. JsonSchemaGeneratorTest ensures that this is updated.
 *
 * @see com.cloudogu.gitops.config.schema.Schema
 */
class GenerateJsonSchema {
    static void main(String[] args) {
        ObjectNode jsonSchema = ApplicationContext.run().getBean(JsonSchemaGenerator).createSchema()

        println(jsonSchema.toString());
    }
}
