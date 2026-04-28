package com.cloudogu.gitops.config.schema

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.Schema
import groovy.util.logging.Slf4j

@Slf4j
class JsonSchemaValidator {

    private static ObjectMapper objectMapper = new ObjectMapper()
    private static SchemaRegistry schemaRegistry = SchemaRegistry.builder().build()
    
    static void validate(Map yaml) {
            def json = objectMapper.convertValue(yaml, JsonNode)
            def schemaNode = JsonSchemaGenerator.createSchema()
            Schema schema = schemaRegistry.getSchema(schemaNode)

            log.debug("yaml configuration converted to json for validate {}", json)

            def validationMessages = schema.validate(json)

            if (!validationMessages.isEmpty()) {
                throw new RuntimeException("Config file invalid: " + validationMessages.join("\n"))
            }
    }
}
