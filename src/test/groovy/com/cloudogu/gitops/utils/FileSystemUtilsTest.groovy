package com.cloudogu.gitops.utils

import org.junit.jupiter.api.Test

import java.nio.file.Path

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
}
