package com.cloudogu.gitops.config.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

import java.util.stream.Stream

import static groovy.test.GroovyAssert.shouldFail

class SchemaValidatorTest {
    static Stream<Arguments> validSchemas() {
        Stream.Builder<Arguments> ret = Stream.builder()

        ret.add(Arguments.of("empty registry", [
                registry: [:]
        ]))

        ret.add(Arguments.of("defined registry", [
                registry: [
                        url: "localhost",
                        path: "gop",
                        username: "admin",
                        password: "hunter2",
                        internalPort: 30000
                ]
        ]))

        ret.add(Arguments.of("multiple values", [
                registry: [
                        url: "localhost",
                        path: "gop",
                ],
                jenkins: [
                        url: "http://localhost/jenkins"
                ],
                scmm: [
                        url: "http://localhost/scmm"
                ],
                images: [
                        kubectl: "localhost:30000/lachlanevenson/k8s-kubectl:v1.25.4"
                ],
                application: [
                        remote: true,
                        namePrefix: "prefix"
                ],
                features: [
                        argocd: [
                                active: true,
                                url: "http://localhost/argocd"
                        ],
                        exampleApps: [
                                petclinic: [
                                        baseDomain: "petclinic.localhost"
                                ]
                        ]
                ]
        ]))

        return ret.build()
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("validSchemas")
    void 'test valid schemas'(String description, Map schema) {
        def validator = new SchemaValidator(new JsonSchemaGenerator())

        validator.validate(new ObjectMapper().convertValue(schema, JsonNode))
    }

    static Stream<Arguments> invalidSchemas() {
        Stream.Builder<Arguments> ret = Stream.builder()

        ret.add(Arguments.of("wrong type for registry.internalPort", [
                registry: [
                        internalPort: "this should be a number"
                ]
        ]))

        ret.add(Arguments.of("invalid additional key within registry", [
                registry: [
                        url: "",
                        unexpectedKey: "this should error"
                ]
        ]))

        ret.add(Arguments.of("invalid additional key on root level", [
                registry: [
                        url: "",
                ],
                unexpectedKey: "this should not exist"
        ]))

        ret.add(Arguments.of("specifying dynamic value", [
                application: [
                        namePrefix: "prefix",
                        namePrefixForEnvVars: "prefix"
                ],
        ]))

        return ret.build()
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSchemas")
    void 'test invalid schemas'(String description, Map schema) {
        def validator = new SchemaValidator(new JsonSchemaGenerator())

        shouldFail(RuntimeException) {
            validator.validate(new ObjectMapper().convertValue(schema, JsonNode))
        }
    }
}
