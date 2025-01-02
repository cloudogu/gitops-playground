package com.cloudogu.gitops.features

import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.DeploymentStrategy
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.jenkins.GlobalPropertyManager
import com.cloudogu.gitops.jenkins.JobManager
import com.cloudogu.gitops.jenkins.PrometheusConfigurator
import com.cloudogu.gitops.jenkins.UserManager
import com.cloudogu.gitops.utils.CommandExecutor
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.K8sClient
import com.cloudogu.gitops.utils.MapUtils
import freemarker.template.Configuration
import freemarker.template.DefaultObjectWrapperBuilder
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Slf4j
@Singleton
@Order(70)
class Jenkins extends Feature {

    static final String HELM_VALUES_PATH = "jenkins/values.ftl.yaml"

    String namespace = 'default'

    private Config config
    private CommandExecutor commandExecutor
    private FileSystemUtils fileSystemUtils
    private GlobalPropertyManager globalPropertyManager
    private JobManager jobManger
    private UserManager userManager
    private PrometheusConfigurator prometheusConfigurator
    private DeploymentStrategy deployer
    private K8sClient k8sClient

    Jenkins(
            Config config,
            CommandExecutor commandExecutor,
            FileSystemUtils fileSystemUtils,
            GlobalPropertyManager globalPropertyManager,
            JobManager jobManger,
            UserManager userManager,
            PrometheusConfigurator prometheusConfigurator,
            HelmStrategy deployer,
            K8sClient k8sClient
    ) {
        this.config = config
        this.commandExecutor = commandExecutor
        this.fileSystemUtils = fileSystemUtils
        this.globalPropertyManager = globalPropertyManager
        this.jobManger = jobManger
        this.userManager = userManager
        this.prometheusConfigurator = prometheusConfigurator
        this.deployer = deployer
        this.k8sClient = k8sClient
    }

    @Override
    boolean isEnabled() {
        return true // For now, we either deploy an internal or configure an external instance
    }

    @Override
    void enable() {

        if (config.jenkins.internal) {
            // Mark the first node for Jenkins and agents. See jenkins/values.yaml "agent.workingDir" for details.
            // Remove first (in case new nodes were added)
            k8sClient.labelRemove('node', '--all', '', 'node')
            def nodeName = k8sClient.waitForNode().replace('node/', '')
            k8sClient.label('node', nodeName, new Tuple2('node', 'jenkins'))


            k8sClient.createSecret('generic', 'jenkins-credentials', namespace,
                    new Tuple2('jenkins-admin-user', config.jenkins.username),
                    new Tuple2('jenkins-admin-password', config.jenkins.password))

            def helmConfig = config.jenkins.helm
            def templatedMap = templateToMap(HELM_VALUES_PATH,
                    [
                            dockerGid: findDockerGid(),
                            config   : config,
                            // Allow for using static classes inside the templates
                            statics  : new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_32).build()
                                    .getStaticModels(),
                    ])

            def mergedMap = MapUtils.deepMerge(helmConfig.values, templatedMap)
            def tempValuesPath = fileSystemUtils.writeTempFile(mergedMap)

            deployer.deployFeature(
                    helmConfig.repoURL,
                    'jenkins',
                    helmConfig.chart,
                    helmConfig.version,
                    namespace,
                    'jenkins',
                    tempValuesPath
            )
        }

        commandExecutor.execute("${fileSystemUtils.rootDir}/scripts/jenkins/init-jenkins.sh", [
                TRACE                     : config.application.trace,
                INTERNAL_JENKINS          : config.jenkins.internal,
                JENKINS_HELM_CHART_VERSION: config.jenkins.helm.version,
                JENKINS_URL               : config.jenkins.url,
                JENKINS_USERNAME          : config.jenkins.username,
                JENKINS_PASSWORD          : config.jenkins.password,
                // Used indirectly in utils.sh 😬
                REMOTE_CLUSTER            : config.application.remote,
                SCMM_URL                  : config.scmm.urlForJenkins,
                SCMM_PASSWORD             : config.scmm.password,
                INSTALL_ARGOCD            : config.features.argocd.active,
                NAME_PREFIX               : config.application.namePrefix,
                INSECURE                  : config.application.insecure,
        ])

        globalPropertyManager.setGlobalProperty('SCMM_URL', config.scmm.url)

        if (config.jenkins.additionalEnvs) {
            for (entry in (config.jenkins.additionalEnvs as Map).entrySet()) {
                globalPropertyManager.setGlobalProperty(entry.key.toString(), entry.value.toString())
            }
        }

        if (config.registry.url) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_URL", config.registry.url)
        }

        if (config.registry.path) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PATH", config.registry.path)
        }

        if (config.registry.twoRegistries) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}REGISTRY_PROXY_URL", config.registry.proxyUrl)
        }

        if (config.jenkins.mavenCentralMirror) {
            globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}MAVEN_CENTRAL_MIRROR", config.jenkins.mavenCentralMirror)
        }

        globalPropertyManager.setGlobalProperty("${config.application.namePrefixForEnvVars}K8S_VERSION", Config.K8S_VERSION)

        if (userManager.isUsingCasSecurityRealm()) {
            log.trace("Using CAS Security Realm. Must not create user.")
        } else {
            userManager.createUser(config.jenkins.metricsUsername, config.jenkins.metricsPassword)
        }

        userManager.grantPermission(config.jenkins.metricsUsername, UserManager.Permissions.METRICS_VIEW)

        prometheusConfigurator.enableAuthentication()

        if (config.features.argocd.active) {

            String jobName = "${config.application.namePrefix}example-apps"
            def credentialId = "scmm-user"

            jobManger.createJob(jobName,
                    config.scmm.urlForJenkins,
                    "${config.application.namePrefix}argocd",
                    credentialId)

            jobManger.createCredential(
                    jobName,
                    credentialId,
                    "${config.application.namePrefix}gitops",
                    "${config.scmm.password}",
                    'credentials for accessing scm-manager')

            jobManger.createCredential(
                    jobName,
                    "registry-user",
                    "${config.registry.username}",
                    "${config.registry.password}",
                    'credentials for accessing the docker-registry for writing images built on jenkins')

            if (config.registry.twoRegistries) {
                jobManger.createCredential(
                        "${config.application.namePrefix}example-apps",
                        "registry-proxy-user",
                        "${config.registry.proxyUsername}",
                        "${config.registry.proxyPassword}",
                        'credentials for accessing the docker-registry that contains 3rd party or base images')

            }
            // Once everything is set up, start the jobs.
            jobManger.startJob(jobName)
        }
    }

    protected String findDockerGid() {
        String gid = ''
        def etcGroup = k8sClient.run("tmp-docker-gid-grepper-${new Random().nextInt(10000)}",
                'irrelevant' /* Redundant, but mandatory param */, namespace, createGidGrepperOverrides(),
                '--restart=Never', '-ti', '--rm', '--quiet')
        // --quiet is necessary to avoid 'pod deleted' output

        def lines = etcGroup.split('\n')
        for (String it : lines) {
            def parts = it.split(":")
            if (parts[0] == 'docker') {
                gid = parts[2]
                break
            }
        }

        if (!gid) {
            log.warn 'Unable to determine Docker Group ID (GID). Jenkins Agent pods will run as root user (UID 0)!\n' +
                    "Group docker not found in /etc/group:\n${etcGroup}"
            return ''
        } else {
            log.debug("Using Docker Group ID (GID) ${gid} for Jenkins Agent pods")
            return gid
        }
    }

    Map createGidGrepperOverrides() {
        [
                'spec': [
                        'containers'  : [
                                [
                                        'name'        : 'tmp-docker-gid-grepper',
                                        // We use the same image for several tasks for performance and maintenance reasons 
                                        'image'       : "${config.jenkins.internalBashImage}",
                                        'args'        : ['cat', '/etc/group'],
                                        'volumeMounts': [
                                                [
                                                        'name'     : 'group',
                                                        'mountPath': '/etc/group',
                                                        'readOnly' : true
                                                ]
                                        ]
                                ]
                        ],
                        'nodeSelector': [
                                'node': 'jenkins'
                        ],
                        'volumes'     : [
                                [
                                        'name'    : 'group',
                                        'hostPath': [
                                                'path': '/etc/group'
                                        ]
                                ]
                        ]
                ]
        ]
    }
}