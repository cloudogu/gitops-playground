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
import com.cloudogu.gitops.git.local.ScmRepoProvider
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmmApiClient
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
            def k8sClient = new K8sClient(executor, fileSystemUtils, new Provider<Config>() {
                @Override
                Config get() {
                    return config
                }
            })
            def helmClient = new HelmClient(executor)

            def httpClientFactory = new HttpClientFactory()

            def scmmRepoProvider = new ScmRepoProvider(config, fileSystemUtils)

            def insecureSslContextProvider = new Provider<HttpClientFactory.InsecureSslContext>() {
                @Override
                HttpClientFactory.InsecureSslContext get() {
                    return httpClientFactory.insecureSslContext()
                }
            }
            def httpClientScmm = httpClientFactory.okHttpClientScmm(httpClientFactory.createLoggingInterceptor(), config, insecureSslContextProvider)
            def scmmApiClient = new ScmmApiClient(config, httpClientScmm)

            def jenkinsApiClient = new JenkinsApiClient(config,
                    httpClientFactory.okHttpClientJenkins(httpClientFactory.createLoggingInterceptor(), config, insecureSslContextProvider))

            context.registerSingleton(k8sClient)

            if (config.application.destroy) {
                context.registerSingleton(new Destroyer([
                        new ArgoCDDestructionHandler(config, k8sClient, scmmRepoProvider, helmClient, fileSystemUtils),
                        new ScmmDestructionHandler(config, scmmApiClient),
                        new JenkinsDestructionHandler(new JobManager(jenkinsApiClient), config, new GlobalPropertyManager(jenkinsApiClient))
                ]))
            } else {
                def helmStrategy = new HelmStrategy(config, helmClient)

                def gitHandler = new GitHandler(config, scmmApiClient, helmStrategy, fileSystemUtils)

                def deployer = new Deployer(config, new ArgoCdApplicationStrategy(config, fileSystemUtils, scmmRepoProvider), helmStrategy)

                def airGappedUtils = new AirGappedUtils(config, scmmRepoProvider, scmmApiClient, fileSystemUtils, helmClient, gitHandler)
                def networkingUtils = new NetworkingUtils()

                def jenkins = new Jenkins(config, executor, fileSystemUtils, new GlobalPropertyManager(jenkinsApiClient),
                        new JobManager(jenkinsApiClient), new UserManager(jenkinsApiClient),
                        new PrometheusConfigurator(jenkinsApiClient), helmStrategy, k8sClient, networkingUtils)

                context.registerSingleton(new Application(config, [
                        new Registry(config, fileSystemUtils, k8sClient, helmStrategy),
                        gitHandler,
                        jenkins,
                        new ScmmManager(config, executor, fileSystemUtils, helmStrategy, k8sClient, networkingUtils),
                        new ArgoCD(config, k8sClient, helmClient, fileSystemUtils, scmmRepoProvider, gitHandler),
                        new IngressNginx(config, fileSystemUtils, deployer, k8sClient, airGappedUtils,gitHandler),
                        new CertManager(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
                        new Mailhog(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, gitHandler),
                        new PrometheusStack(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, scmmRepoProvider, gitHandler),
                        new ExternalSecretsOperator(config, fileSystemUtils, deployer, k8sClient, airGappedUtils,gitHandler),
                        new Vault(config, fileSystemUtils, k8sClient, deployer, airGappedUtils,gitHandler),
                        new ContentLoader(config, k8sClient, scmmRepoProvider, scmmApiClient, jenkins, gitHandler),
                ]))
            }
        }
    }
}