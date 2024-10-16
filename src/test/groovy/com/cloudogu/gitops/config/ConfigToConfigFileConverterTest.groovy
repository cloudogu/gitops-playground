package com.cloudogu.gitops.config


import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ConfigToConfigFileConverterTest {
    @Test
    void 'converts config map to yaml'() {
        def converter = new ConfigToConfigFileConverter()

        def config = converter.convert([
                registry   : [
                        internalPort: 123,
                        url         : 'url',
                        path        : 'path',
                        username    : 'username',
                        password    : 'password',
                        proxyUrl         : 'proxyUrl',
                        proxyUsername    : 'proxyUsername',
                        proxyPassword    : 'proxyPassword',
                        helm  : [
                                chart  : 'docker-registry',
                                repoURL: 'https://charts.helm.sh/stable',
                                version: '1.9.4'
                        ]
                ],
                jenkins    : [
                        url     : 'jenkins-url',
                        username: 'jenkins-user',
                        password: 'jenkins-pw',
                        metricsUsername: 'metricsUsername',
                        metricsPassword: 'metricsUsername',
                        mavenCentralMirror: 'mirror',
                        helm  : [
                                version: '5.0.17'
                        ],
                        jenkinsAdditionalEnvs: [
                                a: 'b'
                        ],
                ],
                scmm       : [
                        url     : 'scmm-url',
                        username: 'scmm-user',
                        password: 'scmm-pw',
                        helm  : [
                                chart  : 'scm-manager',
                                repoURL: 'https://packages.scm-manager.org/repository/helm-v2-releases/',
                                version: '3.2.1'
                        ]
                ],
                application: [
                        remote        : true,
                        insecure      : true,
                        localHelmChartFolder : "folder",
                        username: 'app-user',
                        password: 'app-pw',
                        yes           : true,
                        namePrefix    : 'prefix',
                        destroy       : true,
                        podResources : true,
                        baseUrl: 'base-url',
                        gitName: 'gitName',
                        gitEmail: 'git-email',
                        urlSeparatorHyphen: true,
                        mirrorRepos     : true,
                        skipCrds : true,
                        namespaceIsolation : false,
                        netpols : false
                ],
                images     : [
                        kubectl    : 'kubectl-value',
                        helm       : 'helm-value',
                        kubeval    : 'kubeval-value',
                        helmKubeval: 'helmKubeval-value',
                        yamllint   : 'yamllint-value',
                        nginx: 'nginx-value',
                        petclinic  : 'petclinic-value',
                        maven      : 'maven-value'
                ],
                repositories : [
                        springBootHelmChart: [
                                url: 'springboot-url',
                                ref: 'springboot-ref',
                        ],
                        springPetclinic: [
                                url: 'springpet-url',
                                ref: 'springpet-ref'
                        ],
                        gitopsBuildLib: [
                                url: 'gitopsbuildlib-ref'
                        ],
                        cesBuildLib: [
                                url: 'cesbuildlib-ref'
                        ]
                ],
                features   : [
                        argocd    : [
                                active: true,
                                url       : 'url-value',
                                emailFrom : 'argocd@example.org',
                                emailToUser : 'app-team@example.org',
                                emailToAdmin : 'infra@example.org'
                        ],
                        mail   : [
                                mailhog : false,
                                mailhogUrl : 'mailhog-url',
                                smtpAddress: 'smtpA',
                                smtpPort : '1234',
                                smtpUser : 'smptU',
                                smtpPassword : 'smtpPassword',
                                helm  : [
                                        chart  : 'mailhog',
                                        repoURL: 'https://codecentric.github.io/helm-charts',
                                        version: '5.0.1',
                                        image: 'ghcr.io/cloudogu/mailhog:v1.0.1'
                                ]
                        ],
                        monitoring: [
                                active: true,
                                grafanaUrl: 'g-url',
                                grafanaEmailFrom : 'grafana@example.org',
                                grafanaEmailTo : 'infra@example.org',
                                helm  : [
                                        chart  : 'kube-prometheus-stack',
                                        repoURL: 'https://prometheus-community.github.io/helm-charts',
                                        version: '58.2.1',
                                        grafanaImage: 'grafanaImage',
                                        grafanaSidecarImage: 'grafanaSidecarImage',
                                        prometheusImage: 'prometheusImage',
                                        prometheusOperatorImage: 'prometheusOperatorImage',
                                        prometheusConfigReloaderImage: 'prometheusConfigReloaderImage',
                                ]
                        ],
                        secrets   : [
                                externalSecrets: [
                                        helm: [
                                                chart  : 'external-secrets',
                                                repoURL: 'https://charts.external-secrets.io',
                                                version: '0.9.16',
                                                image  : 'eso-image',
                                                certControllerImage: 'eso-cc-image',
                                                webhookImage: 'eso-wh-image'
                                        ]
                                ],
                                vault          : [
                                        mode: 'dev',
                                        url: 'vault-url',
                                        helm: [
                                                chart  : 'vault',
                                                repoURL: 'https://helm.releases.hashicorp.com',
                                                version: '0.25.0',
                                                image: 'vault-image',
                                        ]
                                ]
                        ],
                        ingressNginx: [
                                active: true,
                                helm  : [
                                        chart: 'ingress-nginx',
                                        repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                        version: '4.9.1',
                                        values: [
                                                a: 'b'
                                        ]
                                ],
                        ],
                        exampleApps: [
                                petclinic: [
                                        baseDomain: 'base-domain'
                                ],
                                nginx: [
                                        baseDomain: 'base-domain'
                                ]
                        ],
                ]
        ])

        assertThat(config).isEqualTo("""---
registry:
  internalPort: 123
  url: "url"
  path: "path"
  username: "username"
  password: "password"
  proxyUrl: "proxyUrl"
  proxyUsername: "proxyUsername"
  proxyPassword: "proxyPassword"
  helm:
    chart: "docker-registry"
    repoURL: "https://charts.helm.sh/stable"
    version: "1.9.4"
jenkins:
  url: "jenkins-url"
  username: "jenkins-user"
  password: "jenkins-pw"
  metricsUsername: "metricsUsername"
  metricsPassword: "metricsUsername"
  mavenCentralMirror: "mirror"
  jenkinsAdditionalEnvs:
    a: "b"
  helm:
    version: "5.0.17"
scmm:
  url: "scmm-url"
  username: "scmm-user"
  password: "scmm-pw"
  helm:
    chart: "scm-manager"
    repoURL: "https://packages.scm-manager.org/repository/helm-v2-releases/"
    version: "3.2.1"
application:
  remote: true
  insecure: true
  localHelmChartFolder: "folder"
  openshift: false
  username: "app-user"
  password: "app-pw"
  "yes": true
  namePrefix: "prefix"
  destroy: true
  podResources: true
  gitName: "gitName"
  gitEmail: "git-email"
  baseUrl: "base-url"
  urlSeparatorHyphen: true
  mirrorRepos: true
  skipCrds: true
  namespaceIsolation: false
  netpols: false
images:
  kubectl: "kubectl-value"
  helm: "helm-value"
  kubeval: "kubeval-value"
  helmKubeval: "helmKubeval-value"
  yamllint: "yamllint-value"
  nginx: "nginx-value"
  petclinic: "petclinic-value"
  maven: "maven-value"
repositories:
  springBootHelmChart:
    url: "springboot-url"
    ref: "springboot-ref"
  springPetclinic:
    url: "springpet-url"
    ref: "springpet-ref"
  gitopsBuildLib:
    url: "gitopsbuildlib-ref"
  cesBuildLib:
    url: "cesbuildlib-ref"
features:
  argocd:
    active: true
    url: "url-value"
    emailFrom: "argocd@example.org"
    emailToUser: "app-team@example.org"
    emailToAdmin: "infra@example.org"
  mail:
    mailhog: false
    mailhogUrl: "mailhog-url"
    smtpAddress: "smtpA"
    smtpPort: 1234
    smtpUser: "smptU"
    smtpPassword: "smtpPassword"
    helm:
      chart: "mailhog"
      repoURL: "https://codecentric.github.io/helm-charts"
      version: "5.0.1"
      image: "ghcr.io/cloudogu/mailhog:v1.0.1"
  monitoring:
    active: true
    grafanaUrl: "g-url"
    grafanaEmailFrom: "grafana@example.org"
    grafanaEmailTo: "infra@example.org"
    helm:
      chart: "kube-prometheus-stack"
      repoURL: "https://prometheus-community.github.io/helm-charts"
      version: "58.2.1"
      values: null
      grafanaImage: "grafanaImage"
      grafanaSidecarImage: "grafanaSidecarImage"
      prometheusImage: "prometheusImage"
      prometheusOperatorImage: "prometheusOperatorImage"
      prometheusConfigReloaderImage: "prometheusConfigReloaderImage"
  secrets:
    externalSecrets:
      helm:
        chart: "external-secrets"
        repoURL: "https://charts.external-secrets.io"
        version: "0.9.16"
        image: "eso-image"
        certControllerImage: "eso-cc-image"
        webhookImage: "eso-wh-image"
    vault:
      mode: "dev"
      url: "vault-url"
      helm:
        chart: "vault"
        repoURL: "https://helm.releases.hashicorp.com"
        version: "0.25.0"
        image: "vault-image"
  ingressNginx:
    active: true
    helm:
      chart: "ingress-nginx"
      repoURL: "https://kubernetes.github.io/ingress-nginx"
      version: "4.9.1"
      values:
        a: "b"
  exampleApps:
    petclinic:
      baseDomain: "base-domain"
    nginx:
      baseDomain: "base-domain"
""")
    }
}
