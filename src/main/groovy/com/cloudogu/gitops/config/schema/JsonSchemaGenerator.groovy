package com.cloudogu.gitops.config.schema

import com.cloudogu.gitops.config.Config
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.*
import com.github.victools.jsonschema.module.jackson.JacksonModule 

class JsonSchemaGenerator {
    static ObjectNode createSchema() {
        SchemaGeneratorConfigBuilder configBuilder = 
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        // Make the schema strict: Only allow our fields, warn when additional fields are passed
                        .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                        // Exception to the above: For Maps allow additional fields. 
                        // We use this to allow inline helm values without having to validate them
                        .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
                        // All fields can be set to null to use the default
                        .with(Option.NULLABLE_FIELDS_BY_DEFAULT)
                        .with(new JacksonModule( /* no options for now */))
        // Apply the rule to include only fields with @JsonProperty annotation
        configBuilder.forFields()
                .withIgnoreCheck((FieldScope field) -> {
                    // Only include fields that are annotated with @JsonProperty
                    return field.getAnnotation(JsonPropertyDescription) == null
                })

        SchemaGenerator generator = new SchemaGenerator(configBuilder.build())

        return generator.generateSchema(Config)
    }
}
