package com.cloudogu.gitops.infrastructure.kubernetes.rbac;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.git.GitRepo;
import com.cloudogu.gitops.utils.TemplatingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class RbacDefinition {

	private final Role.Variant variant;
	private String name;
	private String namespace;
	private List<ServiceAccountRef> serviceAccounts = new ArrayList<>();
	private String subfolder = "rbac";
	private GitRepo repo;
	private Config config;

	private final TemplatingEngine templater = new TemplatingEngine();

	public RbacDefinition withName(String name) {
		this.name = name;
		return this;
	}

	public RbacDefinition withNamespace(String namespace) {
		this.namespace = namespace;
		return this;
	}

	public RbacDefinition withServiceAccounts(List<ServiceAccountRef> accounts) {
		this.serviceAccounts = new ArrayList<>(accounts);
		return this;
	}

	public RbacDefinition withServiceAccountsFrom(String saNamespace, List<String> saNames) {
		return withServiceAccounts(ServiceAccountRef.fromNames(saNamespace, saNames));
	}

	public RbacDefinition withSubfolder(String subfolder) {
		this.subfolder = subfolder;
		return this;
	}

	public RbacDefinition withRepo(GitRepo repo) {
		this.repo = repo;
		return this;
	}

	public RbacDefinition withConfig(Config config) {
		this.config = config;
		return this;
	}

	public void generate() {
		if (repo == null) {
			throw new IllegalStateException("SCMM repo must be set using withRepo() before calling generate()");
		}

		log.trace("Generating RBAC for name='{}', namespace='{}', subfolder='{}'", name, namespace, subfolder);

		File outputDir = Path.of(repo.getAbsoluteLocalRepoTmpDir(), subfolder).toFile();
		outputDir.mkdirs();

		generateRole(outputDir);
		generateRoleBinding(outputDir);
	}

	private void generateRole(File outputDir) {
		if (variant == Role.Variant.CLUSTER_ADMIN) {
			log.trace("Skipping creation of ClusterRole cluster-admin");
			return;
		}

		Role role = new Role(name, namespace, variant, config);

		try {
			templater.template(role.getTemplateFile(), role.getOutputFile(outputDir), role.toTemplateParams());
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate role template", e);
		}
	}

	private void generateRoleBinding(File outputDir) {
		String roleName = name;
		if (variant == Role.Variant.CLUSTER_ADMIN) {
			roleName = "cluster-admin";
		}
		RoleBinding binding = new RoleBinding(name, namespace, roleName, serviceAccounts);

		try {
			templater.template(binding.getTemplateFile(), binding.getOutputFile(outputDir), binding.toTemplateParams());
		} catch (Exception e) {
			throw new RuntimeException("Failed to generate role binding template", e);
		}
	}
}
