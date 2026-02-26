package com.cloudogu.gitops.cli

import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import com.cloudogu.gitops.destroy.ArgoCDDestructionHandler
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.destroy.JenkinsDestructionHandler
import com.cloudogu.gitops.destroy.ScmmDestructionHandler
import com.cloudogu.gitops.features.*
import com.cloudogu.gitops.features.argocd.ArgoCD
import com.cloudogu.gitops.features.deployment.ArgoCdApplicationStrategy
import com.cloudogu.gitops.features.deployment.Deployer
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.features.git.GitHandler
import com.cloudogu.gitops.git.GitRepoFactory
import com.cloudogu.gitops.jenkins.*
import com.cloudogu.gitops.kubernetes.api.HelmClient
import com.cloudogu.gitops.kubernetes.api.K8sClient
import com.cloudogu.gitops.utils.AirGappedUtils
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.NetworkingUtils

import io.micronaut.context.ApplicationContext

import jakarta.inject.Provider
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Micronaut's dependency injection relies on statically compiled class files with seems incompatible with groovy 
 * scripting/interpretation (without prior compilation).
 * The purpose of our -dev image is exactly that: allow groovy scripting inside the image, to shorten the dev cycle on 
 * air-gapped customer envs.
 *
 * To make this work the dev image gets it's own main() method that explicitly creates instances of the groovy classes.
 * Yes, redundant and not beautiful, but not using dependency injection is worse.*/
@Slf4j
@CompileStatic
class GitopsPlaygroundCliMainScripted {

	static void main(String[] args) throws Exception {
		new GitopsPlaygroundCliMain().exec(args, GitopsPlaygroundCliScripted)
	}

	static class GitopsPlaygroundCliScripted extends GitopsPlaygroundCli {

		protected void register(Config config, ApplicationContext context) {
			super.register(config, context)

			FileSystemUtils fileSystemUtils = new FileSystemUtils()
			CommandExecutor executor = new CommandExecutor()
			NetworkingUtils networkingUtils = new NetworkingUtils()

			K8sClient k8sClient = new K8sClient(executor, fileSystemUtils, new Provider<Config>() {
				@Override
				Config get() {
					return config
				}
			})

			HelmClient helmClient = new HelmClient(executor)
			HttpClientFactory httpClientFactory = new HttpClientFactory()
			GitRepoFactory gitRepoFactory = new GitRepoFactory(config, fileSystemUtils)
			HelmStrategy helmStrategy = new HelmStrategy(config, helmClient)
			GitHandler gitHandler = new GitHandler(config, helmStrategy, fileSystemUtils, k8sClient, networkingUtils)

			JenkinsApiClient jenkinsApiClient = new JenkinsApiClient(config,
				httpClientFactory.okHttpClientJenkins(config))

			context.registerSingleton(k8sClient)

			if (config.application.destroy) {
				context.registerSingleton(new Destroyer([new ArgoCDDestructionHandler(config, k8sClient, gitRepoFactory, helmClient, fileSystemUtils, gitHandler),
				                                         new ScmmDestructionHandler(config),
				                                         new JenkinsDestructionHandler(new JobManager(jenkinsApiClient), config, new GlobalPropertyManager(jenkinsApiClient)),]))
			} else {
				Deployer deployer = new Deployer(config, new ArgoCdApplicationStrategy(config, fileSystemUtils, gitRepoFactory, gitHandler), helmStrategy)
				AirGappedUtils airGappedUtils = new AirGappedUtils(config, gitRepoFactory, fileSystemUtils, helmClient, gitHandler)
				Jenkins jenkins = new Jenkins(config, executor, fileSystemUtils, new GlobalPropertyManager(jenkinsApiClient),
					new JobManager(jenkinsApiClient), new UserManager(jenkinsApiClient),
					new PrometheusConfigurator(jenkinsApiClient), helmStrategy, k8sClient, networkingUtils, gitHandler)

				// make sure the order of features is in same order as the @Order values
				context.registerSingleton(new Application(config, [new Registry(config, fileSystemUtils, k8sClient, helmStrategy),
				                                                   gitHandler,
				                                                   jenkins,
				                                                   new ArgoCD(config, k8sClient, helmClient, fileSystemUtils, gitRepoFactory, gitHandler),
				                                                   new Ingress(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
				                                                   new CertManager(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
				                                                   new Mail(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
				                                                   new Monitoring(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitRepoFactory, gitHandler),
				                                                   new ExternalSecretsOperator(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
				                                                   new Vault(config, fileSystemUtils, k8sClient, deployer, airGappedUtils, gitHandler),
				                                                   new ContentLoader(config, k8sClient, gitRepoFactory, jenkins, gitHandler),]))
			}
		}
	}
}