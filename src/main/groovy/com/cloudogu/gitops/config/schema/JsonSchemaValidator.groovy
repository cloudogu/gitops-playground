package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersionDetector
import jakarta.inject.Singleton
import com.networknt.schema.JsonSchema


@Singleton
class JsonSchemaValidator {
    private final JsonSchemaGenerator schemaGenerator

    JsonSchemaValidator(JsonSchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator
    }

    void validate(JsonNode json) {
        def schemaNode = schemaGenerator.createSchema()
        def schema = JsonSchemaFactory.getInstance(SpecVersionDetector.detect(schemaNode)).getSchema(schemaNode)

        // Validate the JSON with custom validation rule to ignore "values" property
        def validationMessages = validateWithCustomRule(schema, json)

        if (!validationMessages.isEmpty()) {
            throw new RuntimeException("Configuration file invalid: " + validationMessages.join("\n"))
        }
    }

    private List<String> validateWithCustomRule(JsonSchema schema, JsonNode json) {
        List<String> validationMessages = []

        // Iterate over each property in the JSON
        json.fields().each { entry ->
            def key = entry.key
            def value = entry.value

            // Ignore validation for the "values" property
            if (key == "features" && value.has("ingressNginx") && value.get("ingressNginx").has("values")) {
                // Do nothing, skip validation for "values" property
            } else {
                // Validate other properties according to the schema
                def validationResult = schema.validate(value)
                validationResult.each { validationError ->
                    validationMessages.add(validationError.getMessage())
                }
            }
        }

        return validationMessages
    }

}
