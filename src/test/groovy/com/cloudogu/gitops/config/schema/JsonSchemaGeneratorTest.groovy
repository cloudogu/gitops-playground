package com.cloudogu.gitops.config.schema


import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class JsonSchemaGeneratorTest {
    @Test
    void 'test configuration schema is not ouf of date'() {
        // slup and output to ensure consistent formatting
        def slurper = new JsonSlurper()
        def output = new JsonOutput()

        def expect = output.toJson(slurper.parseText(new JsonSchemaGenerator().createSchema().toString()))
        def actual = output.toJson(slurper.parse(new File(System.getProperty("user.dir"), "docs/configuration.schema.json")))

        assertThat(actual)
                .as("Schema in docs/configuration.schema.json must be updated. Run GenerateJsonSchema class.")
                .isEqualTo(expect)
    }
}
