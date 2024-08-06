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
    void 'deletes files except'() {
        Path parentDir = Files.createTempDirectory(this.class.getSimpleName())
        for (i in 0..<3) {
            def filePath = parentDir.resolve i.toString()
            Files.write(filePath, i.toString().getBytes())
        }
        for (i in 3..<7) {
            Path dirPath = parentDir.resolve(i.toString())
            Files.createDirectories(dirPath)
        }
        
        fileSystemUtils.deleteFilesExcept(parentDir.toFile(), '0', '3')

        List<Path> chartSubFolders = Files.list(parentDir).collect(Collectors.toList())
        assertThat(chartSubFolders).hasSize(2)
        assertThat(chartSubFolders).contains(parentDir.resolve('0'), parentDir.resolve('3'))
    }
}
