package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.ScmmRepo
import com.cloudogu.gitops.utils.DockerImageParser
import freemarker.template.DefaultObjectWrapperBuilder

class RepoInitializationAction {
    private ScmmRepo repo
    private String copyFromDirectory
    private Config config

    RepoInitializationAction(Config config, ScmmRepo repo, String copyFromDirectory) {
        this.config = config
        this.repo = repo
        this.copyFromDirectory = copyFromDirectory
    }

    /**
     * Clone repo from SCM and initialize it with default basic files. Afterwards we can edit these files.
     */
    void initLocalRepo() {
        repo.cloneRepo()
        repo.copyDirectoryContents(copyFromDirectory)
        replaceTemplates()
    }

    void replaceTemplates() {
        repo.replaceTemplates([
//                namePrefix          : config.application.namePrefix,
//                namePrefixForEnvVars: config.application.namePrefixForEnvVars,
                tenantName          : config.application.namePrefix.replaceAll(/-$/, ""),
//                podResources        : config.application.podResources,
                images              : config.images,
                nginxImage          : config.images.nginx ? DockerImageParser.parse(config.images.nginx) : null,
                isRemote            : config.application.remote,
                isInsecure          : config.application.insecure,
                isOpenshift         : config.application.openshift,
                urlSeparatorHyphen  : config.application.urlSeparatorHyphen,
                mirrorRepos         : config.application.mirrorRepos,
                skipCrds            : config.application.skipCrds,
                netpols             : config.application.netpols,
                argocd              : [
                        // Note that passing the URL object here leads to problems in Graal Native image, see Git history
                        host                     : config.features.argocd.url ? new URL(config.features.argocd.url).host : "",
                        env                      : config.features.argocd.env,
                        isOperator               : config.features.argocd.operator,
                        emailFrom                : config.features.argocd.emailFrom,
                        emailToUser              : config.features.argocd.emailToUser,
                        emailToAdmin             : config.features.argocd.emailToAdmin,
                        resourceInclusionsCluster: config.features.argocd.resourceInclusionsCluster
                ],
                registry            : [
                        twoRegistries: config.registry.twoRegistries
                ],
                monitoring          : [
                        grafana: [
                                url: config.features.monitoring.grafanaUrl ? new URL(config.features.monitoring.grafanaUrl) : null,
                        ],
                        active : config.features.monitoring.active
                ],
                mail                : [
                        active      : config.features.mail.active,
                        smtpAddress : config.features.mail.smtpAddress,
                        smtpPort    : config.features.mail.smtpPort,
                        smtpUser    : config.features.mail.smtpUser,
                        smtpPassword: config.features.mail.smtpPassword
                ],
                secrets             : [
                        active: config.features.secrets.active,
                        vault : [
                                url: config.features.secrets.vault.url ? new URL(config.features.secrets.vault.url) : null,
                        ],
                ],
                scmm                : [
                        baseUrl       : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local/scm" : ScmmRepo.createScmmUrl(config),
                        host          : config.scmm.internal ? "http://scmm.${config.application.namePrefix}scm-manager.svc.cluster.local" : config.scmm.host,
                        protocol      : config.scmm.internal ? 'http' : config.scmm.protocol,
                        repoUrl       : ScmmRepo.createSCMBaseUrl(config),
                        provider      : config.scmm.provider,
                        centralScmmUrl: !config.multiTenant.internal? config.multiTenant.centralScmUrl : "http://scmm.scm-manager.svc.cluster.local/scm"
                ],
                jenkins             : [
                        mavenCentralMirror: config.jenkins.mavenCentralMirror,
                ],
                exampleApps         : [
                        petclinic: [
                                baseDomain: config.features.exampleApps.petclinic.baseDomain
                        ],
                        nginx    : [
                                baseDomain: config.features.exampleApps.nginx.baseDomain
                        ],
                ],
                config              : config,
                // Allow for using static classes inside the templates
                statics             : new DefaultObjectWrapperBuilder(freemarker.template.Configuration.VERSION_2_3_32).build().getStaticModels()
        ])
    }

    ScmmRepo getRepo() {
        return repo
    }
}