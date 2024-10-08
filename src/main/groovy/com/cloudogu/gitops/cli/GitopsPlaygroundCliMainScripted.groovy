package com.cloudogu.gitops.cli

import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.cloudogu.gitops.config.schema.JsonSchemaValidator
import com.cloudogu.gitops.config.schema.Schema
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import com.cloudogu.gitops.dependencyinjection.JenkinsFactory
import com.cloudogu.gitops.dependencyinjection.RetrofitFactory
import com.cloudogu.gitops.destroy.ArgoCDDestructionHandler
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.destroy.JenkinsDestructionHandler
import com.cloudogu.gitops.destroy.ScmmDestructionHandler
import com.cloudogu.gitops.features.*
import com.cloudogu.gitops.features.argocd.ArgoCD
import com.cloudogu.gitops.features.deployment.ArgoCdApplicationStrategy
import com.cloudogu.gitops.features.deployment.Deployer
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.jenkins.*
import com.cloudogu.gitops.scmm.ScmmRepoProvider
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

        @Override
        protected ApplicationContext createApplicationContext() {
            ApplicationContext context = super.createApplicationContext()

            // Create ApplicationConfigurator to get started
            context.registerSingleton(
                    new ApplicationConfigurator(
                            new NetworkingUtils(new K8sClient(new CommandExecutor(), new FileSystemUtils(), null), new CommandExecutor()),
                            new FileSystemUtils(),
                            new JsonSchemaValidator(new JsonSchemaGenerator())))
            return context
        }

        @Override
        protected Configuration register(ApplicationContext context, Schema config) {
            def configuration = super.register(context, config)

            // After config is set, create all other beans

            def fileSystemUtils = new FileSystemUtils()
            def executor = new CommandExecutor()
            def k8sClient = new K8sClient(executor, fileSystemUtils, new Provider<Configuration>() {
                @Override
                Configuration get() {
                    return configuration
                }
            })
            def helmClient = new HelmClient(executor)

            def httpClientFactory = new HttpClientFactory()
            
            def scmmRepoProvider = new ScmmRepoProvider(configuration, fileSystemUtils)
            def retrofitFactory = new RetrofitFactory()

            def insecureSslContextProvider = new Provider<HttpClientFactory.InsecureSslContext>() {
                @Override
                HttpClientFactory.InsecureSslContext get() {
                    return httpClientFactory.insecureSslContext()
                }
            }
            def httpClientScmm = retrofitFactory.okHttpClient(httpClientFactory.createLoggingInterceptor(), configuration, insecureSslContextProvider)
            def retrofit = retrofitFactory.retrofit(configuration, httpClientScmm)
            def repoApi = retrofitFactory.repositoryApi(retrofit)

            def jenkinsConfiguration = new JenkinsConfigurationAdapter(configuration)
            JenkinsFactory jenkinsFactory = new JenkinsFactory(jenkinsConfiguration)
            def jenkinsApiClient = jenkinsFactory.jenkinsApiClient(
                    httpClientFactory.okHttpClient(httpClientFactory.createLoggingInterceptor(), jenkinsConfiguration, insecureSslContextProvider))

            context.registerSingleton(k8sClient)
            
            if (configuration.config['application']['destroy']) {
                context.registerSingleton(new Destroyer([
                        new ArgoCDDestructionHandler(configuration, k8sClient, scmmRepoProvider, helmClient, fileSystemUtils),
                        new ScmmDestructionHandler(configuration, retrofitFactory.usersApi(retrofit), retrofitFactory.repositoryApi(retrofit)),
                        new JenkinsDestructionHandler(new JobManager(jenkinsApiClient), configuration, new GlobalPropertyManager(jenkinsApiClient))
                ]))
            } else {
                def helmStrategy = new HelmStrategy(configuration, helmClient)

                def deployer = new Deployer(configuration, new ArgoCdApplicationStrategy(configuration, fileSystemUtils, scmmRepoProvider), helmStrategy)

                def airGappedUtils = new AirGappedUtils(configuration, scmmRepoProvider, repoApi, fileSystemUtils, helmClient)

                context.registerSingleton(new Application([
                        new Registry(config, fileSystemUtils, k8sClient, helmStrategy),
                        new ScmManager(configuration, executor, fileSystemUtils, helmStrategy),
                        new Jenkins(configuration, executor, fileSystemUtils, new GlobalPropertyManager(jenkinsApiClient),
                                new JobManager(jenkinsApiClient), new UserManager(jenkinsApiClient),
                                new PrometheusConfigurator(jenkinsApiClient)),
                        new Content(configuration, k8sClient),
                        new ArgoCD(configuration, k8sClient, helmClient, fileSystemUtils, scmmRepoProvider),
                        new IngressNginx(configuration, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new CertManager(configuration, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new Mailhog(configuration, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new PrometheusStack(configuration, fileSystemUtils, deployer, k8sClient, airGappedUtils, scmmRepoProvider),
                        new ExternalSecretsOperator(configuration, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new Vault(configuration, fileSystemUtils, k8sClient, deployer, airGappedUtils)
                ]))
            }
            return configuration
        }
    }
}
