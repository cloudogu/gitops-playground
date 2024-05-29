package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.*
import jakarta.inject.Singleton

@Singleton
class JsonSchemaGenerator {
    ObjectNode createSchema() {
        SchemaGeneratorConfigBuilder configBuilder = 
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        // Make the schema strict: Only allow our fields, warn when additional fields are passed
                        .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                        // Exception to the above: For Maps allow additional fields. 
                        // We use this to allow inline helm values without having to validate them
                        .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)

        SchemaGenerator generator = new SchemaGenerator(configBuilder.build())

        return generator.generateSchema(Schema)
    }
}
