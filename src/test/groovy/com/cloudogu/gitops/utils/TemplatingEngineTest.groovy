package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class TemplatingEngineTest {

    @Test
    void 'replaces two templates in different folders'() {
        def tmpDir = File.createTempDir('gitops-playground-tests-templatingengine')
        tmpDir.deleteOnExit()
        def fooTemplate = new File(tmpDir.absolutePath, "foo.tpl.txt")
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
        def barTemplate = new File(tmpDir.absolutePath, "bar.tpl.txt")
        barTemplate.text = "Hello \${name}"

        def engine = new TemplatingEngine()
        engine.replaceTemplate(barTemplate, [
                name: "Playground",
        ])

        assertThat(new File(tmpDir.absolutePath, "bar.txt").text).isEqualTo("Hello Playground")
    }
}
