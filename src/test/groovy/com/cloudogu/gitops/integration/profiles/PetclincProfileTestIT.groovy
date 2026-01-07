package com.cloudogu.gitops.integration.profiles

import com.cloudogu.gitops.integration.features.KubenetesApiTestSetup
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

import static org.assertj.core.api.Assertions.assertThat

/**
 * This tests can only be successfull, if one of theses profiles used.
 */
@EnabledIfSystemProperty(named = "micronaut.environments", matches = "full|content-examples")
class PetclinicProfileTestIT extends KubenetesApiTestSetup {

    String namespace1 = 'example-apps-staging'
    String namespace2 = 'example-apps-production'

    @BeforeAll
    static void labelTest() {
        println "###### Testing Petclinic ######"
    }

    @Test
    void ensureNamespaceStagingExists() {
        def namespaces = api.listNamespace().execute()
        assertThat(namespaces).isNotNull()
        assertThat(namespaces.getItems().isEmpty()).isFalse()
        def namespace = namespaces.getItems().find { namespace1.equals(it.getMetadata().name) }
        assertThat(namespace).isNotNull()
    }
    @Test
    void ensureNamespaceProductionExists() {
        def namespaces = api.listNamespace().execute()
        assertThat(namespaces).isNotNull()
        assertThat(namespaces.getItems().isEmpty()).isFalse()
        def namespace = namespaces.getItems().find { namespace2.equals(it.getMetadata().name) }
        assertThat(namespace).isNotNull()
    }


    @Override
    boolean isReadyToStartTests() {

        return true
    }
}