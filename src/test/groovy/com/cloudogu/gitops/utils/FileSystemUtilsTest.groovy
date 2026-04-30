package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import static org.assertj.core.api.Assertions.assertThat

class FileSystemUtilsTest {

    FileSystemUtils fileSystemUtils = new FileSystemUtils()
    
    @Test
    void copiesToTempDir() {
        def expectedText = 'someText'
        
        File someFile = File.createTempFile(getClass().getSimpleName(), '')
        someFile.withWriter { {
            it.println expectedText
        }}
        Path tmpFile = fileSystemUtils.copyToTempDir(someFile.absolutePath)
        
        assertThat(tmpFile.toAbsolutePath().toString()).isNotEqualTo(someFile.getAbsoluteFile())
        assertThat(tmpFile.toFile().getText().trim()).isEqualTo(expectedText)
    }
    
    @Test
    void 'makes read-only folders writable recursively'() {
        // Create temporary directory with nested structure
        Path parentDir = Files.createTempDirectory(this.class.getSimpleName())

        // Create some regular files
        File regularFile = new File(parentDir.toFile(), "regularFile.txt")
        regularFile.createNewFile()

        // Create nested directory
        File nestedDir = new File(parentDir.toFile(), "nestedDir")
        nestedDir.mkdir()

        // Create read-only file in nested directory
        File readOnlyFile = new File(nestedDir, "readOnlyFile.txt")
        readOnlyFile.createNewFile()
        readOnlyFile.setWritable(false)

        // Create another read-only file in parent directory
        File anotherReadOnlyFile = new File(parentDir.toFile(), "anotherReadOnlyFile.txt")
        anotherReadOnlyFile.createNewFile()
        anotherReadOnlyFile.setWritable(false)

        // Verify files are indeed read-only
        assertThat(readOnlyFile.canWrite()).isFalse()
        assertThat(anotherReadOnlyFile.canWrite()).isFalse()

        FileSystemUtils.makeWritable(parentDir.toFile())

        // Verify all files are now writable
        assertThat(regularFile.canWrite()).isTrue()
        assertThat(readOnlyFile.canWrite()).isTrue()
        assertThat(anotherReadOnlyFile.canWrite()).isTrue()

        // Clean up
        parentDir.toFile().deleteDir()
    }
    
    @Test
    void 'reads and writes yaml'() {
        Path tmpFile = fileSystemUtils.createTempFile()
        Map yaml = [foo: 'bar', nested: [a: 1, b: 2]]

        fileSystemUtils.writeYaml(yaml, tmpFile.toFile())
        Map result = fileSystemUtils.readYaml(tmpFile)

        assertThat(result).isEqualTo(yaml)
    }

    @Test
    void 'readYaml falls back to classpath'() {
        // testMainConfig.yaml exists in src/test/resources, so it is on the classpath
        Map result = fileSystemUtils.readYaml(Path.of('testMainConfig.yaml'))

        assertThat(result)
                .extracting('registry.internalPort')
                .isEqualTo(30000)
    }

    @Test
    void 'readYaml falls back to classpath and removes src main resources'() {
        // application-minimal.yaml exists in src/main/resources
        // We simulate a path that might be in a config file pointing to the source tree
        Map result = fileSystemUtils.readYaml(Path.of('src/main/resources/application-minimal.yaml'))

        assertThat(result)
                .extracting('application.yes')
                .isEqualTo(true)
    }

    @Test
    void 'readYaml returns empty map if not found'() {
        Map result = fileSystemUtils.readYaml(Path.of('non-existent.yaml'))
        assertThat(result).isEmpty()
    }
}
