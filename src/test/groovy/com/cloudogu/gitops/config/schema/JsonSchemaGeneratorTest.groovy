package com.cloudogu.gitops.config.schema

import org.junit.jupiter.api.Test

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static org.assertj.core.api.Assertions.assertThat

class JsonSchemaGeneratorTest {
    @Test
    void 'test configuration schema is not ouf of date'() {
        // slurp and output to ensure consistent formatting
        def slurper = new JsonSlurper()
        def output = new JsonOutput()

        def expect = output.toJson(slurper.parseText(new JsonSchemaGenerator().createSchema().toString()))
        def actual = output.toJson(slurper.parse(new File(System.getProperty("user.dir"), "docs/configuration.schema.json")))

        assertThat(actual)
                .as("Config in docs/configuration.schema.json must be updated. Run GenerateJsonSchema class.")
                .isEqualTo(expect)
    }
}