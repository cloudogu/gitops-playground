package com.cloudogu.gitops.utils;

import freemarker.core.InvalidReferenceException;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import freemarker.template.TemplateModelException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllowlistFreemarkerObjectWrapperTest {

	@Test
	void shouldAllowAccessToWhitelistedStaticModels() throws TemplateModelException {
		var wrapper = new AllowListFreemarkerObjectWrapper(
			Configuration.VERSION_2_3_32,
			Set.of("com.cloudogu.gitops.utils.DockerImageParser")
		);
		var staticModels = wrapper.getStaticModels();

		assertNotNull(staticModels.get("com.cloudogu.gitops.utils.DockerImageParser"));
		assertNull(staticModels.get("java.lang.Integer"));
		assertNull(staticModels.get("java.lang.String"));
	}

	@Test
	void shouldDenyAccessToNonWhitelistedStaticModels() throws TemplateModelException {
		var wrapper = new AllowListFreemarkerObjectWrapper(
			Configuration.VERSION_2_3_32,
			Set.of("java.lang.String")
		);
		var staticModels = wrapper.getStaticModels();

		assertNull(staticModels.get("java.lang.Integer"));
		assertNotNull(staticModels.get("java.lang.String"));
		assertNull(staticModels.get("com.cloudogu.gitops.utils.DockerImageParser"));
	}

	@Test
	void shouldReturnTrueForIsEmptyWhenAllowlistIsEmpty() throws TemplateModelException {
		var wrapper = new AllowListFreemarkerObjectWrapper(Configuration.VERSION_2_3_32, Set.of());
		var staticModels = wrapper.getStaticModels();

		assertTrue(staticModels.isEmpty());
	}

	@Test
	void templatingOnlyWorksForWhitelistedStatics() throws IOException {
		String templateText = """
			 <#assign DockerImageParser=statics['com.cloudogu.gitops.utils.DockerImageParser']>
			<#assign imageObject = DockerImageParser.parse('test:latest')>
			<#assign staticsTests=statics['System']>
			<#assign imageObject = staticsTests.exit()>
			""";

		Map<String, Object> model = Map.of(
			"statics",
			new AllowListFreemarkerObjectWrapper(
				Configuration.VERSION_2_3_32,
				Set.of("com.cloudogu.gitops.utils.DockerImageParser")
			).getStaticModels()
		);
		File tempInputFile = File.createTempFile("test", ".ftl.yaml");
		Files.writeString(tempInputFile.toPath(), templateText);

		InvalidReferenceException exception = assertThrows(
			InvalidReferenceException.class,
			() -> new TemplatingEngine().replaceTemplates(tempInputFile, model)
		);

		assertTrue(exception.getMessage().contains("System"), "Exception message should mention 'System'");
	}

	@Test
	void templatingInFtlFilesWorksCorrectlyWithWhitelistedStaticModels() throws IOException, TemplateException {
		String templateText = """
			<#assign DockerImageParser=statics['com.cloudogu.gitops.utils.DockerImageParser']>
			<#assign imageObject = DockerImageParser.parse('test:latest')>
			<#assign staticsTests=statics['java.lang.Math']>
			<#assign number = staticsTests.round(3.14)>
			""";

		Map<String, Object> model = Map.of(
			"statics",
			new AllowListFreemarkerObjectWrapper(
				Configuration.VERSION_2_3_32,
				Set.of("java.lang.Math", "com.cloudogu.gitops.utils.DockerImageParser")
			).getStaticModels()
		);
		File tempInputFile = File.createTempFile("test", ".ftl.yaml");
		Files.writeString(tempInputFile.toPath(), templateText);

		new TemplatingEngine().replaceTemplates(tempInputFile, model);
	}
}
