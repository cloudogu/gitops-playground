package com.cloudogu.gitops.utils

import static org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ClusterResourcesCopyFilterTest {

	@TempDir
	File tempDir

	@Test
	void 'forSubDir includes selected subdir and traversal parents only'() {
		File root = createClusterResourcesRoot()

		FileFilter filter = ClusterResourcesCopyFilter.forSubDir(root.path,
			'apps/monitoring')

		assertThat(filter.accept(new File(root, 'apps'))).isTrue()
		assertThat(filter.accept(new File(root, 'apps/monitoring'))).isTrue()
		assertThat(filter.accept(new File(root, 'apps/monitoring/misc/dashboard/prometheus-dashboard.ftl.yaml'))).isTrue()
		assertThat(filter.accept(new File(root, 'apps/ingress/values.yaml'))).isFalse()
	}

	@Test
	void 'forSubDirs excludes tool template directories except ArgoCD helm templates'() {
		File root = createClusterResourcesRoot()

		FileFilter filter = ClusterResourcesCopyFilter.forSubDirs(root.path,
			['apps/monitoring', 'apps/argocd', 'apps/jenkins'])

		assertThat(filter.accept(new File(root, 'apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml'))).isFalse()
		assertThat(filter.accept(new File(root, 'apps/argocd/templates/project.ftl.yaml'))).isFalse()
		assertThat(filter.accept(new File(root, 'apps/argocd/argocd/templates/allow-namespaces.ftl.yaml'))).isTrue()
		assertThat(filter.accept(new File(root, 'apps/jenkins/templates/values.ftl.yaml'))).isTrue()
	}

	@Test
	void 'forSubDirs allows everything when no subdirs are provided'() {
		File root = createClusterResourcesRoot()

		FileFilter filter = ClusterResourcesCopyFilter.forSubDirs(root.path,
			[])

		assertThat(filter.accept(new File(root, 'apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml'))).isTrue()
		assertThat(filter.accept(new File(root, 'apps/ingress/values.yaml'))).isTrue()
	}

	private File createClusterResourcesRoot() {
		File root = new File(tempDir, 'cluster-resources')

		[
			'apps/monitoring/misc/dashboard/prometheus-dashboard.ftl.yaml',
			'apps/monitoring/templates/prometheus-stack-helm-values.ftl.yaml',
			'apps/argocd/templates/project.ftl.yaml',
			'apps/argocd/argocd/templates/allow-namespaces.ftl.yaml',
			'apps/jenkins/templates/values.ftl.yaml',
			'apps/ingress/values.yaml',
		].each { String path ->
			File file = new File(root, path)
			file.parentFile.mkdirs()
			file.text = 'test'
		}

		return root
	}
}
