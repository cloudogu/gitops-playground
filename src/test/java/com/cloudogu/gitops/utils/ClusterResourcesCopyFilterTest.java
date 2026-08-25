package com.cloudogu.gitops.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterResourcesCopyFilterTest {

	@TempDir
	File tempDir;

	@Test
	void forSubDirIncludesSelectedSubdirAndTraversalParentsOnly() throws IOException {
		File root = createClusterResourcesRoot();

		FileFilter filter = ClusterResourcesCopyFilter.forSubDir(root.getPath(), "apps/monitoring");

		assertThat(filter.accept(new File(root, "apps"))).isTrue();
		assertThat(filter.accept(new File(root, "apps/monitoring"))).isTrue();
		assertThat(filter.accept(new File(root, "apps/monitoring/misc/dashboard/prometheus-dashboard.ftl.yaml"))).isTrue();
		assertThat(filter.accept(new File(root, "apps/ingress/values.yaml"))).isFalse();
	}

	@Test
	void forSubDirsExcludesToolTemplateDirectoriesExceptArgoCDHelmTemplates() throws IOException {
		File root = createClusterResourcesRoot();

		FileFilter filter = ClusterResourcesCopyFilter.forSubDirs(
			root.getPath(),
			List.of("apps/monitoring", "apps/argocd")
		);

		assertThat(filter.accept(new File(root, "apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml"))).isFalse();
		assertThat(filter.accept(new File(root, "apps/argocd/templates/project.ftl.yaml"))).isFalse();
		assertThat(filter.accept(new File(root, "apps/argocd/argocd/templates/allow-namespaces.ftl.yaml"))).isTrue();
	}

	@Test
	void forSubDirsAllowsEverythingWhenNoSubdirsAreProvided() throws IOException {
		File root = createClusterResourcesRoot();

		FileFilter filter = ClusterResourcesCopyFilter.forSubDirs(root.getPath(), List.of());

		assertThat(filter.accept(new File(root, "apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml"))).isTrue();
		assertThat(filter.accept(new File(root, "apps/ingress/values.yaml"))).isTrue();
	}

	private File createClusterResourcesRoot() throws IOException {
		File root = new File(tempDir, "cluster-resources");

		List<String> paths = List.of(
			"apps/monitoring/misc/dashboard/prometheus-dashboard.ftl.yaml",
			"apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml",
			"apps/argocd/templates/project.ftl.yaml",
			"apps/argocd/argocd/templates/allow-namespaces.ftl.yaml",
			"apps/jenkins/templates/values.ftl.yaml",
			"apps/ingress/values.yaml"
		);

		for (String path : paths) {
			File file = new File(root, path);
			Files.createDirectories(file.toPath().getParent());
			Files.writeString(file.toPath(), "test");
		}

		return root;
	}
}
