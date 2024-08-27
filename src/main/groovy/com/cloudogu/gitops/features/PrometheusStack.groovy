package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.scmm.ScmmRepoProvider
import com.cloudogu.gitops.utils.*
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

import java.nio.file.Path

import static com.cloudogu.gitops.features.deployment.DeploymentStrategy.RepoType

@Slf4j
@Singleton
@Order(300)
class PrometheusStack extends Feature {

    static final String HELM_VALUES_PATH = "applications/cluster-resources/monitoring/prometheus-stack-helm-values.ftl.yaml"
    static final String RBAC_NAMESPACE_ISOLATION_TEMPLATE = 'applications/cluster-resources/monitoring/rbac/namespace-isolation-rbac.ftl.yaml'
    
    private Map config
    private FileSystemUtils fileSystemUtils
    private DeploymentStrategy deployer
    private K8sClient k8sClient
    private AirGappedUtils airGappedUtils
    ScmmRepoProvider scmmRepoProvider

    PrometheusStack(
            Configuration config,
            FileSystemUtils fileSystemUtils,
            DeploymentStrategy deployer,
            K8sClient k8sClient,
            AirGappedUtils airGappedUtils,
            ScmmRepoProvider scmmRepoProvider
    ) {
        this.deployer = deployer
        this.config = config.getConfig()
        this.fileSystemUtils = fileSystemUtils
        this.k8sClient = k8sClient
        this.airGappedUtils = airGappedUtils
        this.scmmRepoProvider = scmmRepoProvider
    }

    @Override
    boolean isEnabled() {
        return config.features['monitoring']['active']
    }

    @Override
    void enable() {
        def namePrefix = config.application['namePrefix']

        def template = new TemplatingEngine().template(new File(HELM_VALUES_PATH), [
                namePrefix        : namePrefix,
                podResources      : config.application['podResources'],
                monitoring        : [
                        grafanaEmailFrom: config.features['monitoring']['grafanaEmailFrom'] as String,
                        grafanaEmailTo  : config.features['monitoring']['grafanaEmailTo'] as String,
                        grafana         : [
                                // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                                host: config.features['monitoring']['grafanaUrl'] ? new URL(config.features['monitoring']['grafanaUrl'] as String).host : ""
                        ]
                ],
                remote: config.application["remote"],
                skipCrds          : config.application['skipCrds'],
                namespaceIsolation: config.application['namespaceIsolation'],
                namespaces        : namespaceList,
                mail              : [
                        active      : config.features['mail']['active'],
                        smtpAddress : config.features['mail']['smtpAddress'],
                        smtpPort    : config.features['mail']['smtpPort'],
                        smtpUser    : config.features['mail']['smtpUser'],
                        smtpPassword: config.features['mail']['smtpPassword']
                ],
                scmm              : getScmmConfiguration(),
                jenkins           : getJenkinsConfiguration(),
                config: config,
                // Allow for using static classes inside the templates
                statics: new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ])
        def helmValuesYaml = new YamlSlurper().parseText(
                template) as Map

        // Create secret imperatively here instead of values.yaml, because we don't want it to show in git repo
        k8sClient.createSecret(
                'generic',
                'prometheus-metrics-creds-scmm',
                'monitoring',
                new Tuple2('password', config.application["password"])
        )

        k8sClient.createSecret(
                'generic',
                'prometheus-metrics-creds-jenkins',
                'monitoring',
                new Tuple2('password', config.jenkins['metricsPassword']),
        )

        if (config.features['mail']['smtpUser'] || config.features['mail']['smtpPassword']) {
            k8sClient.createSecret(
                    'generic',
                    'grafana-email-secret',
                    'monitoring',
                    new Tuple2('user', config.features['mail']['smtpUser']),
                    new Tuple2('password', config.features['mail']['smtpPassword'])
            )
        }

        if (config.application['namespaceIsolation']) {
            ScmmRepo clusterResourcesRepo = scmmRepoProvider.getRepo('argocd/cluster-resources')
            clusterResourcesRepo.cloneRepo()

            for (String namespace : namespaceList) {
                def rbacYaml = new TemplatingEngine().template(new File(RBAC_NAMESPACE_ISOLATION_TEMPLATE),
                        [namespace: namespace, 
                         namePrefix: namePrefix])
                clusterResourcesRepo.writeFile("misc/monitoring/rbac/${namespace}.yaml", rbacYaml)
            }

            clusterResourcesRepo.commitAndPush("Add namespace-isolated RBAC for PrometheusStack")
        }

        def tmpHelmValues = fileSystemUtils.createTempFile()
        fileSystemUtils.writeYaml(helmValuesYaml, tmpHelmValues.toFile())

        def helmConfig = config['features']['monitoring']['helm']
        if (config.application['mirrorRepos']) {
            log.debug("Mirroring repos: Deploying prometheus from local git repo")

            def repoNamespaceAndName = airGappedUtils.mirrorHelmRepoToGit(config['features']['monitoring']['helm'] as Map)

            String prometheusVersion =
                    new YamlSlurper().parse(Path.of("${config.application['localHelmChartFolder']}/${helmConfig['chart']}",
                    'Chart.yaml'))['version']
            
            deployer.deployFeature(
                    "${scmmUri}/repo/${repoNamespaceAndName}",
                    'prometheusstack',
                    '.',
                    prometheusVersion,
                    'monitoring',
                    'kube-prometheus-stack',
                    tmpHelmValues, RepoType.GIT)
        } else {

            deployer.deployFeature(
                    helmConfig['repoURL'] as String,
                    'prometheusstack',
                    helmConfig['chart'] as String,
                    helmConfig['version'] as String,
                    'monitoring',
                    'kube-prometheus-stack',
                    tmpHelmValues)
        }
    }

    protected List getNamespaceList() {
        def namespaces = []
        def namePrefix = config.application['namePrefix']
        if (config.features['argocd']['active']) {
            namespaces.addAll("${namePrefix}argocd", "${namePrefix}example-apps-staging", "${namePrefix}example-apps-production")
        }
        if (config.features['monitoring']['active']) { // Ignore mailhog here, because it does not expose metrics
            namespaces.addAll("${namePrefix}monitoring")
        }
        if (config.features['secrets']['active']) {
            namespaces.addAll("${namePrefix}secrets")
        }
        if (config.features['ingressNginx']['active']) {
            namespaces.addAll("${namePrefix}ingress-nginx")
        }
        if (config.registry['internal'] || config.scmm['internal'] || config.jenkins['internal']) {
            namespaces.addAll("${namePrefix}default")
        }
        return namespaces
    }

    private Map getScmmConfiguration() {
        // Note that URI.resolve() seems to throw away the existing path. So we create a new URI object.
        URI uri = new URI("${scmmUri}/api/v2/metrics/prometheus")

        return [
                protocol: uri.scheme,
                host    : uri.authority,
                path    : uri.path
        ]
    }
    
    private URI getScmmUri() {
        if (config.scmm['internal']) {
            new URI('http://scmm-scm-manager.default.svc.cluster.local/scm')
        } else {
            new URI("${config.scmm['url']}/scm")
        }
    }

    private Map getJenkinsConfiguration() {
        String path = 'prometheus'
        URI uri
        if (config.jenkins['internal']) {
            uri = new URI("http://jenkins.default.svc.cluster.local/${path}")
        } else {
            uri = new URI("${config.jenkins['url']}/${path}")
        }

        return [
                metricsUsername: config.jenkins['metricsUsername'],
                protocol       : uri.scheme,
                host           : uri.authority,
                path           : uri.path
        ]
    }
}
