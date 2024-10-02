package com.cloudogu.gitops.config

import com.cloudogu.gitops.config.schema.Schema
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import jakarta.inject.Singleton

/**
 * Converts a config in form of a Map to a yaml config file
 */
@Singleton
class ConfigToConfigFileConverter {
    String convert(Map config) {

        new YAMLMapper()
                .registerModule(new SimpleModule().setSerializerModifier(new BeanSerializerModifier() {
                    @Override
                    List<BeanPropertyWriter> changeProperties(SerializationConfig serializationConfig, BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
                        beanProperties.findAll { writer -> writer.getAnnotation(JsonPropertyDescription) != null }
                    }
                }))
                .writeValueAsString(
                        Schema.fromMap(config)
                )
    }
}
