package com.cloudogu.gitops.config.schema;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
public class JsonSchemaValidator {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final SchemaRegistry schemaRegistry = SchemaRegistry.builder().build();

  public static void validate(Map<?, ?> yaml) {
    JsonNode json = objectMapper.convertValue(yaml, JsonNode.class);
    tools.jackson.databind.node.ObjectNode schemaNode = new JsonSchemaGenerator().createSchema();
    Schema schema = schemaRegistry.getSchema(schemaNode);

    log.debug("yaml configuration converted to json for validate {}", json);

    List<?> validationMessages = schema.validate(json);

    if (!validationMessages.isEmpty()) {
      String errorMsg =
          validationMessages.stream().map(Object::toString).collect(Collectors.joining("\n"));
      throw new RuntimeException("Config file invalid: " + errorMsg);
    }
  }
}
