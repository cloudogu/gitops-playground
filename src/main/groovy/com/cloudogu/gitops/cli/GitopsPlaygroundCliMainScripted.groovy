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
import com.cloudogu.gitops.utils.*
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext
import jakarta.inject.Provider

/**
 * Micronaut's dependency injection relies on statically compiled class files with seems incompatible with groovy 
 * scripting/interpretation (without prior compilation).
 * The purpose of our -dev image is exactly that: allow groovy scripting inside the image, to shorten the dev cycle on 
 * air-gapped customer envs.
 *
 * To make this work the dev image gets it's own main() method that explicitly creates instances of the groovy classes.
 * Yes, redundant and not beautiful, but not using dependency injection is worse.
 */
@Slf4j
class GitopsPlaygroundCliMainScripted {

    static void main(String[] args) throws Exception {
        new GitopsPlaygroundCliMain().exec(args, GitopsPlaygroundCliScripted.class)
    }

    static class GitopsPlaygroundCliScripted extends GitopsPlaygroundCli {

        protected void register(Config config, ApplicationContext context) {
            super.register(config, context)

            def fileSystemUtils = new FileSystemUtils()
            def executor = new CommandExecutor()
            def networkingUtils = new NetworkingUtils()
            def k8sClient = new K8sClient(executor, fileSystemUtils, new Provider<Config>() {
                @Override
                Config get() {
                    return config
                }
            })
            def helmClient = new HelmClient(executor)

            def httpClientFactory = new HttpClientFactory()

            def gitRepoFactory = new GitRepoFactory(config, fileSystemUtils)

            def helmStrategy = new HelmStrategy(config, helmClient)

            def gitHandler = new GitHandler(config, helmStrategy, fileSystemUtils, k8sClient, networkingUtils)

            def jenkinsApiClient = new JenkinsApiClient(config,
                    httpClientFactory.okHttpClientJenkins(config))

            context.registerSingleton(k8sClient)

            if (config.application.destroy) {
                context.registerSingleton(new Destroyer([
                        new ArgoCDDestructionHandler(config, k8sClient, gitRepoFactory, helmClient, fileSystemUtils, gitHandler),
                        new ScmmDestructionHandler(config),
                        new JenkinsDestructionHandler(new JobManager(jenkinsApiClient), config, new GlobalPropertyManager(jenkinsApiClient))
                ]))
            } else {
                def deployer = new Deployer(config, new ArgoCdApplicationStrategy(config, fileSystemUtils, gitRepoFactory, gitHandler), helmStrategy)

                def airGappedUtils = new AirGappedUtils(config, gitRepoFactory, fileSystemUtils, helmClient, gitHandler)

                def jenkins = new Jenkins(config, executor, fileSystemUtils, new GlobalPropertyManager(jenkinsApiClient),
                        new JobManager(jenkinsApiClient), new UserManager(jenkinsApiClient),
                        new PrometheusConfigurator(jenkinsApiClient), helmStrategy, k8sClient, networkingUtils,gitHandler)

                // make sure the order of features is in same order as the @Order values
                context.registerSingleton(new Application(config, [
                        new Registry(config, fileSystemUtils, k8sClient, helmStrategy),
                        new ScmManagerSetup(config, executor, fileSystemUtils, helmStrategy, k8sClient, networkingUtils),
                        gitHandler,
                        jenkins,
                        new ArgoCD(config, k8sClient, helmClient, fileSystemUtils, gitRepoFactory, gitHandler),
                        new IngressNginx(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
                        new CertManager(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
                        new Mailhog(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
                        new PrometheusStack(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitRepoFactory, gitHandler),
                        new ExternalSecretsOperator(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
                        new Vault(config, fileSystemUtils, k8sClient, deployer, airGappedUtils, gitHandler),
                        new ContentLoader(config, k8sClient, gitRepoFactory, jenkins, gitHandler),
                ]))
            }
        }
    }
}