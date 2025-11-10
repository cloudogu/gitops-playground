package com.cloudogu.gitops.utils

import freemarker.template.Configuration
import org.junit.jupiter.api.Test

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
    void 'templating only works for whitelisted statics'() {
        def templateText = '''
     <#assign DockerImageParser=statics['com.cloudogu.gitops.utils.DockerImageParser']>
    <#assign imageObject = DockerImageParser.parse('test:latest')>

  <#assign staticsTests=statics['System']>
  <#assign imageObject = staticsTests.exit()>
    '''.stripIndent()

        def model = [
                statics: new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, ['com.cloudogu.gitops.utils.DockerImageParser'] as Set).getStaticModels()
        ] as Map<String, Object>
        // create a temporary file to simulate an actual file input
        def tempInputFile = File.createTempFile("test", ".ftl.yaml")
        tempInputFile.text = templateText

        def exception = assertThrows(freemarker.core.InvalidReferenceException) {
            new TemplatingEngine().replaceTemplates(tempInputFile, model)
        }

        assert exception.message.contains("System") : "Exception message should mention 'System'"
    }

    @Test
    void 'templating in ftl files works correctly with whitelisted static models'() {
        def templateText = '''
<#assign DockerImageParser=statics['com.cloudogu.gitops.utils.DockerImageParser']>
<#assign imageObject = DockerImageParser.parse('test:latest')>

<#assign staticsTests=statics['java.lang.Math']>
<#assign number = staticsTests.round(3.14)>
    '''.stripIndent()

        def model = [
                statics: new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, ['java.lang.Math', 'com.cloudogu.gitops.utils.DockerImageParser'] as Set).getStaticModels()
        ] as Map<String, Object>
        // create a temporary file to simulate an actual file input
        def tempInputFile = File.createTempFile("test", ".ftl.yaml")
        tempInputFile.text = templateText

        new TemplatingEngine().replaceTemplates(tempInputFile, model)

    }
}