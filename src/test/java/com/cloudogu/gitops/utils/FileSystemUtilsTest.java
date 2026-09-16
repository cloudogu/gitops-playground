package com.cloudogu.gitops.utils;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemUtilsTest {

	private final FileSystemUtils fileSystemUtils = new FileSystemUtils();

	@Test
	void copiesToTempDir() throws IOException {
		String expectedText = "someText";

		File someFile = File.createTempFile(getClass().getSimpleName(), "");
		Files.writeString(someFile.toPath(), expectedText + System.lineSeparator());
		Path tmpFile = fileSystemUtils.copyToTempDir(someFile.getAbsolutePath());

		assertThat(tmpFile.toAbsolutePath().toString()).isNotEqualTo(someFile.getAbsoluteFile());
		assertThat(Files.readString(tmpFile).trim()).isEqualTo(expectedText);
	}

	@Test
	void makesReadOnlyFoldersWritableRecursively() throws IOException {
		Path parentDir = Files.createTempDirectory(getClass().getSimpleName());

		File regularFile = new File(parentDir.toFile(), "regularFile.txt");
		regularFile.createNewFile();

		File nestedDir = new File(parentDir.toFile(), "nestedDir");
		nestedDir.mkdir();

		File readOnlyFile = new File(nestedDir, "readOnlyFile.txt");
		readOnlyFile.createNewFile();
		readOnlyFile.setWritable(false);

		File anotherReadOnlyFile = new File(parentDir.toFile(), "anotherReadOnlyFile.txt");
		anotherReadOnlyFile.createNewFile();
		anotherReadOnlyFile.setWritable(false);

		assertThat(readOnlyFile.canWrite()).isFalse();
		assertThat(anotherReadOnlyFile.canWrite()).isFalse();

		FileSystemUtils.makeWritable(parentDir.toFile());

		assertThat(regularFile.canWrite()).isTrue();
		assertThat(readOnlyFile.canWrite()).isTrue();
		assertThat(anotherReadOnlyFile.canWrite()).isTrue();

		org.apache.commons.io.FileUtils.deleteDirectory(parentDir.toFile());
	}

	@Test
	void readsAndWritesYaml() {
		Path tmpFile = fileSystemUtils.createTempFile();
		Map<String, Object> yaml = Map.of(
			"foo", "bar",
			"nested", Map.of("a", 1, "b", 2)
		);

		fileSystemUtils.writeYaml(yaml, tmpFile.toFile());
		Map<String, Object> result = fileSystemUtils.readYaml(tmpFile);

		assertThat(result).isEqualTo(yaml);
	}

	@Test
	void readYamlFallsBackToClasspath() {
		Map<String, Object> result = fileSystemUtils.readYaml(Path.of("testMainConfig.yaml"));

		assertThat(nestedValue(result, "registry", "internalPort")).isEqualTo(30000);
	}

	@Test
	void readYamlFallsBackToClasspathAndRemovesSrcMainResources() {
		Map<String, Object> result = fileSystemUtils.readYaml(Path.of("src/main/resources/application-minimal.yaml"));

		assertThat(nestedValue(result, "application", "yes")).isEqualTo(true);
	}

	@Test
	void readYamlReturnsEmptyMapIfNotFound() {
		Map<String, Object> result = fileSystemUtils.readYaml(Path.of("non-existent.yaml"));

		assertThat(result).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private Object nestedValue(Map<String, Object> source, String parentKey, String childKey) {
		return ((Map<String, Object>) source.get(parentKey)).get(childKey);
	}
}
