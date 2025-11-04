package com.cloudogu.gitops.utils

import freemarker.template.Configuration
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class AllowlistFreemarkerObjectWrapperTest {

    @Test
    void 'should allow access to whitelisted static models'() {
        def wrapper = new AllowlistFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, ["java.lang.String"] as Set)
        def staticModels = wrapper.getStaticModels()

        assertNotNull(staticModels.get("java.lang.String"))
    }

    @Test
    void 'should deny access to non-whitelisted static models'() {
        def wrapper = new AllowlistFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, ["java.lang.String"] as Set)
        def staticModels = wrapper.getStaticModels()

        assertNull(staticModels.get("java.lang.Integer"))
    }

    @Test
    void 'should return true for isEmpty when allowlist is empty'() {
        def wrapper = new AllowlistFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, [] as Set)
        def staticModels = wrapper.getStaticModels()

        assertTrue(staticModels.isEmpty())
    }
}