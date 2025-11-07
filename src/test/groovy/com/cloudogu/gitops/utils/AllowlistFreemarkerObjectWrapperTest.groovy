package com.cloudogu.gitops.utils


import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder
import freemarker.template.Template
import org.junit.jupiter.api.Test
import freemarker.template.TemplateException

import static org.junit.jupiter.api.Assertions.*

class AllowlistFreemarkerObjectWrapperTest {


    @Test
    void 'should allow access to whitelisted static models'() {
        def wrapper = new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, ["com.cloudogu.gitops.utils.DockerImageParser"] as Set)
        def staticModels = wrapper.getStaticModels()

        assertNotNull(staticModels.get("com.cloudogu.gitops.utils.DockerImageParser"))
        assertNull(staticModels.get("java.lang.Integer"))
        assertNull(staticModels.get("java.lang.String"))
    }

    @Test
    void 'should deny access to non-whitelisted static models'() {
        def wrapper = new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, ["java.lang.String"] as Set)
        def staticModels = wrapper.getStaticModels()

        assertNull(staticModels.get("java.lang.Integer"))
        assertNotNull(staticModels.get("java.lang.String"))
        assertNull(staticModels.get("com.cloudogu.gitops.utils.DockerImageParser"))
    }

    @Test
    void 'should return true for isEmpty when allowlist is empty'() {
        def wrapper = new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, [] as Set)
        def staticModels = wrapper.getStaticModels()

        assertTrue(staticModels.isEmpty())
    }

    @Test
    void 'templating in ftl files works correctly with whitelist'(){

        def model = [
                config    : 'testconfig',
                // Allow for using static classes inside the templates
                statics   : new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, [] as Set).getStaticModels()
        ] as Map<String, Object>
        def filePath = getClass().getResource("/test.ftl.yaml")
        new TemplatingEngine().replaceTemplates(new File(filePath as String), model)

        assertNull(new File('test.yaml').text)
    }
}