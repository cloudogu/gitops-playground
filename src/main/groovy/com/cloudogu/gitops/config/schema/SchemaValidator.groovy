package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import jakarta.inject.Singleton

@Singleton
class SchemaValidator {
    private final JsonSchemaGenerator schemaGenerator

    SchemaValidator(JsonSchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator
    }

    void validate(JsonNode json) {
        def schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909).getSchema(schemaGenerator.createSchema())

        def validationMessages = schema.validate(json)

        if (!validationMessages.isEmpty()) {
            throw new RuntimeException("Configuration file invalid: " + validationMessages.join("\n"))
        }
    }
}
