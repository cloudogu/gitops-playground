package com.cloudogu.gitops.utils


import static org.assertj.core.api.Assertions.assertThat 

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TemplatingEngineTest {

    File tmpDir

    @BeforeEach
    void before() {
        tmpDir = File.createTempDir('gitops-playground-tests-templatingengine')
        tmpDir.deleteOnExit()
    }

    @Test
    void 'replaces two templates in different folders'() {
        def fooTemplate = new File(tmpDir.absolutePath, "foo.ftl.txt")
        fooTemplate.text = """
            this is the template
            I can embed \${string}
            <#if display>
            and use ifs
            <#else>
            and use elses
            </#if>
        """

        def tmpDir2 = File.createTempDir('gitops-playground-tests-templatingengine')
        tmpDir2.deleteOnExit()
        def barTemplate = new File(tmpDir2.absolutePath, "bar.ftl.txt")
        barTemplate.text = "Hello \${name}"

        def engine = new TemplatingEngine()
        engine.replaceTemplate(barTemplate, [
                name: "Playground",
        ])

        assertThat(new File(tmpDir2.absolutePath, "bar.txt").text).isEqualTo("Hello Playground")
        assertThat(barTemplate).doesNotExist()
    }

    @Test
    void 'keeps template file'() {
        def barTemplate = new File(tmpDir.absolutePath, "bar.ftl.txt")
        def barTarget = new File(tmpDir.absolutePath, "bar.txt")
        barTemplate.text = "Hello \${name}"
        
        def engine = new TemplatingEngine()
        engine.template(barTemplate, barTarget, [
                name: "Playground",
        ])

        assertThat(barTarget.text).isEqualTo("Hello Playground")
        assertThat(barTemplate).exists()
    }

    @Test
    void 'Templates from file to string'() {
        def fooTemplate = new File(tmpDir.absolutePath, "foo.ftl.txt")
        fooTemplate.text = "Hello \${name}"

        def engine = new TemplatingEngine()
        String result = engine.template(fooTemplate, [
                name: "Playground",
        ])

        assertThat(result).isEqualTo("Hello Playground")
    }
    
    @Test
    void 'Templates from string to string'() {
        def fooTemplate = "Hello \${name}"

        def engine = new TemplatingEngine()
        String result = engine.template(fooTemplate, [
                name: "Playground",
        ])

        assertThat(result).isEqualTo("Hello Playground")
    }
    
    @Test
    void 'Ignores templates without variables'() {
        def fooTemplate = "Hello name"

        def engine = new TemplatingEngine()
        String result = engine.template(fooTemplate, [:])

        assertThat(result).isEqualTo("Hello name")
    }

    @Test
    void "replaces yaml templates"() {
        def barTemplate = new File(tmpDir.absolutePath + File.separator + "subdirectory", "result.ftl.yaml")
        barTemplate.getParentFile().mkdirs()
        barTemplate.text = 'foo: ${prefix}suffix'
        def barTarget = new File(tmpDir.absolutePath, "subdirectory/keep-this-way.yaml")
        barTarget.text = 'thiswont: ${prefix}-be-replaced'
        
        def engine = new TemplatingEngine()
        engine.replaceTemplates(tmpDir, [prefix: "myteam-"])

        assertThat(new File("$tmpDir/subdirectory/result.yaml").text).isEqualTo("foo: myteam-suffix")
        assertThat(new File("$tmpDir/subdirectory/keep-this-way.yaml").text).isEqualTo('thiswont: ${prefix}-be-replaced')
        assertThat(new File("$tmpDir/subdirectory/result.ftl.yaml").exists()).isFalse()
    }
}
