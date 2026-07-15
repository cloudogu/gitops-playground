package com.cloudogu.gitops.tools.common

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.application.repository.RepositoryWorkspace
import com.cloudogu.gitops.config.Config

import groovy.util.logging.Slf4j

/**
 * A single tool to be deployed by GOP.
 *
 * The DeploymentOrchestrator controls the order of tools.
 * Each tool implements the corresponding lifecycle phases.*/
@Slf4j
abstract class Tool {

	protected DeploymentContext context
	protected RepositoryWorkspace repositoryWorkspace

	/**
	 * Activation check for the current deployment run.
	 *
	 * This method must be side-effect free.	*/
	abstract boolean isEnabled(DeploymentContext context)

	/**
	 * Executes this tool along its internal lifecycle.	*/
	boolean execute(DeploymentContext context,
		RepositoryWorkspace workspace) {
		prepareExecution(context, workspace)

		log.info("Installing Tool ${getClass().simpleName}")

		validate()
		preDeploy()
		deploy()
		postDeploy()
		publishChanges()

		log.info("Tool installed: ${getClass().simpleName}")
		return true
	}

	/**
	 * Technical initialization of runtime state.
	 *
	 * This is not a lifecycle phase.	*/
	protected void prepareExecution(DeploymentContext context,
		RepositoryWorkspace workspace) {
		this.context = context
		this.repositoryWorkspace = workspace
	}

	/**
	 * Lifecycle phase: validate tool-specific configuration and prerequisites.
	 *
	 * Throw a RuntimeException to stop the deployment immediately.	*/
	void validate() {}

	/**
	 * Lifecycle phase: prepare deployment inputs and prerequisites.
	 *
	 * Typical responsibilities:
	 * - determine or mutate tool namespace
	 * - create namespaces
	 * - create secrets
	 * - prepare RBAC
	 * - prepare repository resources
	 * - add Helm values template data	*/
	protected void preDeploy() {}

	/**
	 * Lifecycle phase: deploy the tool.
	 *
	 * Typical responsibilities:
	 * - deploy Helm chart
	 * - create ArgoCD Application
	 * - run deployment strategy
	 * - wait for availability if this is part of the deployment step	*/
	protected void deploy() {}

	/**
	 * Lifecycle phase: run follow-up steps after deployment.
	 *
	 * Typical responsibilities:
	 * - bootstrap tool
	 * - install plugins
	 * - configure runtime state
	 * - update managed namespaces	*/
	protected void postDeploy() {}

	/**
	 * Lifecycle phase: publish GitOps repository changes.
	 *
	 * Tools that write GitOps resources should publish their changes explicitly here.
	 * Tools that do not modify the shared cluster-resources repository can keep the default no-op.	*/
	protected void publishChanges() {}

	protected void publishClusterResourcesChanges(String toolName) {
		repositoryWorkspace.commitAndPushClusterResourcesChanges("Update ${toolName} GitOps resources")
	}

	/**
	 * Returns the namespace managed by an enabled tool.
	 *
	 * Tools without a GOP-managed namespace return null.	*/
	final String getActiveNamespace(DeploymentContext context) {
		if (!isEnabled(context)) {
			return null
		}

		return resolveNamespace(context)
	}

	/**
	 * Resolves the namespace managed by this tool.
	 *
	 * Tools without a dedicated namespace do not override this method.
	 * Tools using an external deployment may return null.	*/
	protected String resolveNamespace(DeploymentContext context) {
		return null
	}

	Config getConfig() {
		return context.config
	}

	DeploymentContext getContext() {
		return context
	}

	void preConfigInit(Config configToSet) {}

	void postConfigInit(Config configToSet) {}
}