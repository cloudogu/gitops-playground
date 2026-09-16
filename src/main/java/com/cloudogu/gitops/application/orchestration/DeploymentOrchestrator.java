package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.tools.common.AbstractTool;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class DeploymentOrchestrator {
	@Getter
	private final List<AbstractTool> tools;

	public void deployTools(DeploymentContext context, RepositoryWorkspace workspace) {
		log.debug("Starting tool orchestration. ");

		for (AbstractTool tool : tools) {
			if (!tool.isEnabled(context)) {
				log.debug("Skipping disabled tool {}", tool.getClass().getSimpleName());
				continue;
			}

			log.debug("Deploying tool {}", tool.getClass().getSimpleName());
			tool.execute(context, workspace);
		}

		log.debug("Tool orchestration finished.");
	}
}
