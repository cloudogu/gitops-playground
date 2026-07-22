package com.cloudogu.gitops.config.schema;

import com.cloudogu.gitops.config.Config;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import jakarta.inject.Singleton;
import tools.jackson.databind.node.ObjectNode;

@Singleton
public class JsonSchemaGenerator {

  public ObjectNode createSchema() {
    SchemaGeneratorConfigBuilder configBuilder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            // Make the schema strict: Only allow our fields, warn when additional fields are passed
            .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
            // Exception to the above: For Maps allow additional fields.
            // We use this to allow inline helm values without having to validate them
            .with(Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
            // All fields can be set to null to use the default
            .with(Option.NULLABLE_FIELDS_BY_DEFAULT)
            .with(new JacksonModule(/* no options for now */ ));

    // Apply the rule to include only fields with @JsonProperty annotation (or here,
    // @JsonPropertyDescription)
    configBuilder
        .forFields()
        .withIgnoreCheck(
            (FieldScope field) -> field.getAnnotation(JsonPropertyDescription.class) == null);

    SchemaGenerator generator = new SchemaGenerator(configBuilder.build());

    return generator.generateSchema(Config.class);
  }
}
