package com.cloudogu.gitops.cli

import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.ConfigToConfigFileConverter
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.cloudogu.gitops.config.schema.JsonSchemaValidator
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
            context.registerSingleton(new ConfigToConfigFileConverter())
            return context
        }

        @Override
        protected void register(ApplicationContext context, Configuration config) {
            super.register(context, config)

            // After config is set, create all other beans

            def fileSystemUtils = new FileSystemUtils()
            def executor = new CommandExecutor()
            def k8sClient = new K8sClient(executor, fileSystemUtils, new Provider<Configuration>() {
                @Override
                Configuration get() {
                    return config
                }
            })
            def helmClient = new HelmClient(executor)

            def httpClientFactory = new HttpClientFactory()
            
            def scmmRepoProvider = new ScmmRepoProvider(config, fileSystemUtils)
            def retrofitFactory = new RetrofitFactory()

            def insecureSslContextProvider = new Provider<HttpClientFactory.InsecureSslContext>() {
                @Override
                HttpClientFactory.InsecureSslContext get() {
                    return httpClientFactory.insecureSslContext()
                }
            }
            def httpClientScmm = retrofitFactory.okHttpClient(httpClientFactory.createLoggingInterceptor(), config, insecureSslContextProvider)
            def retrofit = retrofitFactory.retrofit(config, httpClientScmm)
            def repoApi = retrofitFactory.repositoryApi(retrofit)

            def jenkinsConfiguration = new JenkinsConfigurationAdapter(config)
            JenkinsFactory jenkinsFactory = new JenkinsFactory(jenkinsConfiguration)
            def jenkinsApiClient = jenkinsFactory.jenkinsApiClient(
                    httpClientFactory.okHttpClient(httpClientFactory.createLoggingInterceptor(), jenkinsConfiguration, insecureSslContextProvider))

            context.registerSingleton(k8sClient)
            
            if (config.config['application']['destroy']) {
                context.registerSingleton(new Destroyer([
                        new ArgoCDDestructionHandler(config, k8sClient, scmmRepoProvider, helmClient, fileSystemUtils),
                        new ScmmDestructionHandler(config, retrofitFactory.usersApi(retrofit), retrofitFactory.repositoryApi(retrofit)),
                        new JenkinsDestructionHandler(new JobManager(jenkinsApiClient), config, new GlobalPropertyManager(jenkinsApiClient))
                ]))
            } else {
                def helmStrategy = new HelmStrategy(config, helmClient)

                def deployer = new Deployer(config, new ArgoCdApplicationStrategy(config, fileSystemUtils, scmmRepoProvider), helmStrategy)

                def airGappedUtils = new AirGappedUtils(config, scmmRepoProvider, repoApi, fileSystemUtils, helmClient)

                context.registerSingleton(new Application([
                        new Registry(config, fileSystemUtils, k8sClient, helmStrategy),
                        new ScmManager(config, executor, fileSystemUtils, helmStrategy),
                        new Jenkins(config, executor, fileSystemUtils, new GlobalPropertyManager(jenkinsApiClient),
                                new JobManager(jenkinsApiClient), new UserManager(jenkinsApiClient),
                                new PrometheusConfigurator(jenkinsApiClient)),
                        new ArgoCD(config, k8sClient, helmClient, fileSystemUtils, scmmRepoProvider),
                        new IngressNginx(config, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new Mailhog(config, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new PrometheusStack(config, fileSystemUtils, deployer, k8sClient, airGappedUtils, scmmRepoProvider),
                        new ExternalSecretsOperator(config, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                        new Vault(config, fileSystemUtils, k8sClient, deployer, airGappedUtils)
                ]))
            }
        }
    }
}
