package com.cloudogu.gitops.config




interface ScmmSchema {
    public String username = Config.DEFAULT_ADMIN_USER
    public String password = Config.DEFAULT_ADMIN_PW
    Config.HelmConfigWithValues helm = new Config.HelmConfigWithValues(
            chart: 'scm-manager',
            repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
            version: '3.8.0',
            values: [:]
    )

    String rootPath = 'repo'
    String gitOpsUsername = ''
    String urlForJenkins = ''
    String ingress = ''
    Boolean skipRestart = false
    Boolean skipPlugins = false
    String namespace ='scm-manager'
}