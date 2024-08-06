package com.cloudogu.gitops.cli

import com.cloudogu.gitops.Application
import com.cloudogu.gitops.config.ApplicationConfigurator
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.config.schema.JsonSchemaGenerator
import com.cloudogu.gitops.config.schema.JsonSchemaValidator
import com.cloudogu.gitops.dependencyinjection.HttpClientFactory
import com.cloudogu.gitops.dependencyinjection.JenkinsFactory
import com.cloudogu.gitops.dependencyinjection.RetrofitFactory
import com.cloudogu.gitops.features.ExternalSecretsOperator
import com.cloudogu.gitops.features.IngressNginx
import com.cloudogu.gitops.features.Jenkins
import com.cloudogu.gitops.features.Mailhog
import com.cloudogu.gitops.features.PrometheusStack
import com.cloudogu.gitops.features.Registry
import com.cloudogu.gitops.features.ScmManager
import com.cloudogu.gitops.features.Vault
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
import okhttp3.OkHttpClient

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
            // TODO create ConfigToConfigFileConverter for outputConfigFile
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
            def helmStrategy = new HelmStrategy(config, helmClient)

            def jenkinsConfiguration = new JenkinsConfigurationAdapter(config)
            JenkinsFactory jenkinsFactory = new JenkinsFactory(jenkinsConfiguration)

            def retrofitFactory = new RetrofitFactory()
            def httpClientFactory = new HttpClientFactory()

            def insecureSslContextProvider = new Provider<HttpClientFactory.InsecureSslContext>() {
                @Override
                HttpClientFactory.InsecureSslContext get() {
                    return httpClientFactory.insecureSslContext()
                }
            }
            def scmmRepoProvider = new ScmmRepoProvider(config, fileSystemUtils)
            def deployer = new Deployer(config, new ArgoCdApplicationStrategy(config, fileSystemUtils, scmmRepoProvider), helmStrategy)

            def jenkinsApiClient = jenkinsFactory.jenkinsApiClient(httpClientFactory.okHttpClient(httpClientFactory.createLoggingInterceptor(), jenkinsConfiguration, insecureSslContextProvider))

            OkHttpClient httpClientScmm = retrofitFactory.okHttpClient(httpClientFactory.createLoggingInterceptor(), config, insecureSslContextProvider)
            def repoApi = retrofitFactory.repositoryApi(retrofitFactory.retrofit(config, httpClientScmm))
            def airGappedUtils = new AirGappedUtils(config, scmmRepoProvider, repoApi, fileSystemUtils, helmClient)
            
            context.registerSingleton(k8sClient)
            context.registerSingleton(new Application([
                    new Registry(config, fileSystemUtils, k8sClient, helmStrategy),
                    new ScmManager(config, executor, fileSystemUtils, helmStrategy),
                    new Jenkins(config, executor, fileSystemUtils, new GlobalPropertyManager(jenkinsApiClient),
                            new JobManager(jenkinsApiClient), new UserManager(jenkinsApiClient),
                            new PrometheusConfigurator(jenkinsApiClient)),
                    new ArgoCD(config, k8sClient, helmClient, fileSystemUtils, scmmRepoProvider),
                    new IngressNginx(config, fileSystemUtils,deployer, k8sClient, airGappedUtils),
                    new Mailhog(config, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                    new PrometheusStack(config,fileSystemUtils, deployer, k8sClient, airGappedUtils),
                    new ExternalSecretsOperator(config, fileSystemUtils, deployer, k8sClient, airGappedUtils),
                    new Vault(config, fileSystemUtils, k8sClient, deployer, airGappedUtils)
            ]))


            // TODO dont forget to create destroyer
        }
    }
}
