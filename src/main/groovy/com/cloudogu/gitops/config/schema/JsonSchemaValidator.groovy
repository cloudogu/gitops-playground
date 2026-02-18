package com.cloudogu.gitops.config.schema

import groovy.util.logging.Slf4j

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersionDetector

@Slf4j
class JsonSchemaValidator {

    private static ObjectMapper objectMapper = new ObjectMapper()

    static void validate(Map yaml) {
            def json = objectMapper.convertValue(yaml, JsonNode)
            def schemaNode = JsonSchemaGenerator.createSchema()
            def schema = JsonSchemaFactory.getInstance(SpecVersionDetector.detect(schemaNode)).getSchema(schemaNode)

            log.debug("yaml configuration converted to json for validate {}", json)

            def validationMessages = schema.validate(json)

            if (!validationMessages.isEmpty()) {
                throw new RuntimeException("Config file invalid: " + validationMessages.join("\n"))
            }
    }
}
