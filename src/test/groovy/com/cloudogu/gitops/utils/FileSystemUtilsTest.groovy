package com.cloudogu.gitops.utils

import static org.assertj.core.api.Assertions.assertThat

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

import org.junit.jupiter.api.Test

class FileSystemUtilsTest {

	FileSystemUtils fileSystemUtils = new FileSystemUtils()

	@Test
	void copiesToTempDir() {
		def expectedText = 'someText'

		File someFile = File.createTempFile(getClass().getSimpleName(), '')
		someFile.withWriter {
			{
				it.println expectedText
			}
		}
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