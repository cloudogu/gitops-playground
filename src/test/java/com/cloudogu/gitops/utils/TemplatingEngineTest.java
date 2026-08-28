package com.cloudogu.gitops.utils;

import freemarker.template.TemplateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplatingEngineTest {

	private File tmpDir;

	@BeforeEach
	void before() throws IOException {
		tmpDir = Files.createTempDirectory("gitops-playground-tests-templatingengine").toFile();
		tmpDir.deleteOnExit();
	}

	@Test
	void replacesTwoTemplatesInDifferentFolders() throws IOException, TemplateException {
		File fooTemplate = new File(tmpDir.getAbsolutePath(), "foo.ftl.txt");
		Files.writeString(
			fooTemplate.toPath(), """
				this is the template
				I can embed ${string}
				<#if display>
				and use ifs
				<#else>
				and use elses
				</#if>
				"""
		);

		File tmpDir2 = Files.createTempDirectory("gitops-playground-tests-templatingengine").toFile();
		tmpDir2.deleteOnExit();
		File barTemplate = new File(tmpDir2.getAbsolutePath(), "bar.ftl.txt");
		Files.writeString(barTemplate.toPath(), "Hello ${name}");

		TemplatingEngine engine = new TemplatingEngine();
		engine.replaceTemplate(barTemplate, Map.of("name", "Playground"));

		assertThat(Files.readString(new File(tmpDir2.getAbsolutePath(), "bar.txt").toPath())).isEqualTo(
			"Hello Playground");
		assertThat(barTemplate).doesNotExist();
	}

	@Test
	void keepsTemplateFile() throws IOException, TemplateException {
		File barTemplate = new File(tmpDir.getAbsolutePath(), "bar.ftl.txt");
		File barTarget = new File(tmpDir.getAbsolutePath(), "bar.txt");
		Files.writeString(barTemplate.toPath(), "Hello ${name}");

		TemplatingEngine engine = new TemplatingEngine();
		engine.template(barTemplate, barTarget, Map.of("name", "Playground"));

		assertThat(Files.readString(barTarget.toPath())).isEqualTo("Hello Playground");
		assertThat(barTemplate).exists();
	}

	@Test
	void templatesFromFileToString() throws IOException, TemplateException {
		File fooTemplate = new File(tmpDir.getAbsolutePath(), "foo.ftl.txt");
		Files.writeString(fooTemplate.toPath(), "Hello ${name}");

		TemplatingEngine engine = new TemplatingEngine();
		String result = engine.template(fooTemplate, Map.of("name", "Playground"));

		assertThat(result).isEqualTo("Hello Playground");
	}

	@Test
	void templatesFromStringToString() throws IOException, TemplateException {
		String fooTemplate = "Hello ${name}";

		TemplatingEngine engine = new TemplatingEngine();
		String result = engine.template(fooTemplate, Map.of("name", "Playground"));

		assertThat(result).isEqualTo("Hello Playground");
	}

	@Test
	void ignoresTemplatesWithoutVariables() throws IOException, TemplateException {
		String fooTemplate = "Hello name";

		TemplatingEngine engine = new TemplatingEngine();
		String result = engine.template(fooTemplate, Map.of());

		assertThat(result).isEqualTo("Hello name");
	}

	@Test
	void replacesYamlTemplates() throws IOException, TemplateException {
		File barTemplate = new File(tmpDir.getAbsolutePath() + File.separator + "subdirectory", "result.ftl.yaml");
		Files.createDirectories(barTemplate.getParentFile().toPath());
		Files.writeString(barTemplate.toPath(), "foo: ${prefix}suffix");
		File barTarget = new File(tmpDir.getAbsolutePath(), "subdirectory/keep-this-way.yaml");
		Files.writeString(barTarget.toPath(), "thiswont: ${prefix}-be-replaced");

		TemplatingEngine engine = new TemplatingEngine();
		engine.replaceTemplates(tmpDir, Map.of("prefix", "myteam-"));

		assertThat(Files.readString(new File(tmpDir, "subdirectory/result.yaml").toPath())).isEqualTo(
			"foo: myteam-suffix");
		assertThat(Files.readString(new File(tmpDir, "subdirectory/keep-this-way.yaml").toPath()))
			.isEqualTo("thiswont: ${prefix}-be-replaced");
		assertThat(new File(tmpDir, "subdirectory/result.ftl.yaml")).doesNotExist();
	}
}
