package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersionDetector
import jakarta.inject.Singleton

@Singleton
class SchemaValidator {
    private final JsonSchemaGenerator schemaGenerator

    SchemaValidator(JsonSchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator
    }

    void validate(JsonNode json) {
        def schemaNode = schemaGenerator.createSchema()
        def schema = JsonSchemaFactory.getInstance(SpecVersionDetector.detect(schemaNode)).getSchema(schemaNode)

        def validationMessages = schema.validate(json)

        if (!validationMessages.isEmpty()) {
            throw new RuntimeException("Configuration file invalid: " + validationMessages.join("\n"))
        }
    }
}
