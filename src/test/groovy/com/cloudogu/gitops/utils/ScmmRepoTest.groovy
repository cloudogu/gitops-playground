package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat 

class ScmmRepoTest {

    Map config = [
            scmm: [
                internal: false,
                    username: "dont-care-username",
                    password: "dont-care-password",
                    protocol: "https",
                    host: "localhost"
            ],
            application: [
                    namePrefix : ''
            ],
    ]

    @Test
    void "writes file"() {
        def repo = createRepo()
        repo.writeFile("test.txt", "the file's content")

        def expectedFile = new File("$repo.absoluteLocalRepoTmpDir/test.txt")
        assertThat(expectedFile.text).is("the file's content")
    }

    @Test
    void "overwrites file"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir

        def existingFile = new File("$tempDir/already-exists.txt")
        existingFile.createNewFile()
        existingFile.text = "already existing content"

        repo.writeFile("already-exists.txt", "overwritten content")

        def expectedFile = new File("$tempDir/already-exists.txt")
        assertThat(expectedFile.text).is("overwritten content")
    }

    @Test
    void "writes file and creates subdirectory"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir
        repo.writeFile("subdirectory/test.txt", "the file's content")

        def expectedFile = new File("$tempDir/subdirectory/test.txt")
        assertThat(expectedFile.text).is("the file's content")
    }

    @Test
    void "throws error when directory conflicts with existing file"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir
        new File("$tempDir/test.txt").mkdir()

        shouldFail(FileNotFoundException) {
            repo.writeFile("test.txt", "the file's content")
        }
    }

    @Test
    void "replaces yaml templates"() {
        def repo = createRepo()
        def tempDir = repo.absoluteLocalRepoTmpDir
        repo.writeFile("subdirectory/result.tpl.yaml", 'foo: ${prefix}suffix')
        repo.writeFile("subdirectory/keep-this-way.yaml", 'thiswont: ${prefix}-be-replaced')

        repo.replaceYamlTemplates([prefix: "myteam-"])

        assertThat(new File("$tempDir/subdirectory/result.yaml").text).isEqualTo("foo: myteam-suffix")
        assertThat(new File("$tempDir/subdirectory/keep-this-way.yaml").text).isEqualTo('thiswont: ${prefix}-be-replaced')
        assertThat(new File("$tempDir/subdirectory/result.tpl.yaml").exists()).isFalse()
    }

    @Test
    void 'Creates repo with empty name-prefix'(){
        def repo = createRepo('expectedRepoTarget')
        assertThat(repo.scmmRepoTarget).isEqualTo('expectedRepoTarget')
    }

    @Test
    void 'Creates repo with name-prefix'(){
        config.application['namePrefix'] = 'abc'
        def repo = createRepo('expectedRepoTarget')
        assertThat(repo.scmmRepoTarget).isEqualTo('abc-expectedRepoTarget')
    }

    private ScmmRepo createRepo(String repoTarget = "dont-care-repo-target") {
        new ScmmRepo(config, repoTarget, new CommandExecutorForTest())
    }
}
