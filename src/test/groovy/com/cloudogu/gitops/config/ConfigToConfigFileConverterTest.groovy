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
                        pullUrl         : 'pullUrl',
                        pullUsername    : 'pullUsername',
                        pullPassword    : 'pullPassword',
                        pushUrl         : 'pushUrl',
                        pushPath        : 'pushPath',
                        pushUsername    : 'pushUsername',
                        pushPassword    : 'pushPassword',
                ],
                images     : [
                        kubectl    : 'kubectl-value',
                        helm       : 'helm-value',
                        kubeval    : 'kubeval-value',
                        helmKubeval: 'helmKubeval-value',
                        yamllint   : 'yamllint-value',
                        petclinic : 'petclinic-value'
                ],
                features   : [
                        argocd    : [
                                active: true,
                        ],
                        monitoring: [
                                active: true
                        ],
                        secrets   : [
                                active: true,
                                vault: [
                                        url: ''
                                ]
                        ],
                        exampleApps: [
                                petclinic: [
                                        baseDomain: 'base-domain'
                                ],
                                nginx: [
                                        baseDomain: 'base-domain'
                                ]
                        ],
                        ingressNginx: [
                                active: true
                        ]
                ]
        ])

        assertThat(config).isEqualTo("""---
registry:
  internalPort: 123
  url: "url"
  path: "path"
  username: "username"
  password: "password"
  pullUrl: "pullUrl"
  pullUsername: "pullUsername"
  pullPassword: "pullPassword"
  pushUrl: "pushUrl"
  pushPath: "pushPath"
  pushUsername: "pushUsername"
  pushPassword: "pushPassword"
  helm:
    chart: ""
    repoURL: ""
    version: ""
images:
  kubectl: "kubectl-value"
  helm: "helm-value"
  kubeval: "kubeval-value"
  helmKubeval: "helmKubeval-value"
  yamllint: "yamllint-value"
  nginx: ""
  petclinic: "petclinic-value"
features:
  argocd:
    active: true
    url: ""
    emailFrom: ""
    emailToUser: ""
    emailToAdmin: ""
  mail: null
  monitoring:
    active: true
    grafanaUrl: ""
    grafanaEmailFrom: ""
    grafanaEmailTo: ""
    helm:
      chart: ""
      repoURL: ""
      version: ""
      grafanaImage: ""
      grafanaSidecarImage: ""
      prometheusImage: ""
      prometheusOperatorImage: ""
      prometheusConfigReloaderImage: ""
  secrets:
    externalSecrets: null
    vault:
      mode: ""
      url: ""
      helm:
        chart: ""
        repoURL: ""
        version: ""
        image: ""
  ingressNginx:
    active: true
    helm:
      chart: ""
      repoURL: ""
      version: ""
      values: null
  exampleApps:
    petclinic:
      baseDomain: "base-domain"
    nginx:
      baseDomain: "base-domain"
""")
    }
}
