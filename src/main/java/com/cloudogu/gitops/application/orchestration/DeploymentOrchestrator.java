package com.cloudogu.gitops.application.orchestration;

import com.cloudogu.gitops.application.content.ContentLoader;
import com.cloudogu.gitops.application.context.DeploymentContext;
import com.cloudogu.gitops.application.repository.RepositoryWorkspace;
import com.cloudogu.gitops.tools.*;
import com.cloudogu.gitops.tools.common.Tool;
import com.cloudogu.gitops.tools.core.Jenkins;
import com.cloudogu.gitops.tools.core.argocd.ArgoCD;
import com.cloudogu.gitops.tools.core.scmmanager.ScmManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Singleton
public class DeploymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DeploymentOrchestrator.class);

    private final List<Tool> tools;

    @Inject
    public DeploymentOrchestrator(ScmManager scmManager,
                                  Jenkins jenkins,
                                  Registry registry,
                                  ArgoCD argoCD,
                                  Ingress ingress,
                                  CertManager certManager,
                                  Monitoring monitoring,
                                  ExternalSecretsOperator externalSecretsOperator,
                                  Vault vault,
                                  ContentLoader contentLoader) {
        this(List.of(scmManager,
                argoCD,
                jenkins,
                registry,
                ingress,
                certManager,
                monitoring,
                externalSecretsOperator,
                vault,
                contentLoader));
    }

    public DeploymentOrchestrator(List<Tool> tools) {
        this.tools = tools;
    }

    public void deployTools(DeploymentContext context, RepositoryWorkspace workspace) {
        log.debug("Starting tool orchestration.");

        for (Tool tool : tools) {
            if (!tool.isEnabled(context)) {
                log.debug("Skipping disabled tool {}", tool.getClass().getSimpleName());
                continue;
            }

            log.debug("Deploying tool {}", tool.getClass().getSimpleName());
            tool.execute(context, workspace);
        }

        log.debug("Tool orchestration finished.");
    }

    public List<Tool> getTools() {
        return tools;
    }
}
