package com.cloudogu.gitops.config

import com.cloudogu.gitops.config.schema.Schema
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import jakarta.inject.Singleton
/**
 * Converts a config in form of a Map to a yaml config file
 */
@Singleton
class ConfigToConfigFileConverter {
    String convert(Map config) {
        def mapper = new ObjectMapper()
        mapper.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        def schema = mapper.convertValue(config, Schema)

        return new YAMLMapper().writeValueAsString(schema)
    }
}
