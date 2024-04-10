package com.cloudogu.gitops.config


import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ConfigToConfigFileConverterTest {
    @Test
    void 'converts config map to yaml'() {
        def converter = new ConfigToConfigFileConverter()

        def config = converter.convert([
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
    url: ""
    emailFrom: ""
    emailToUser: ""
    emailToAdmin: ""
  mail: null
  monitoring:
    active: true
    grafanaUrl: ""
    helm: null
    grafanaEmailFrom: ""
    grafanaEmailTo: ""
  secrets:
    vault:
      mode: ""
      url: ""
      helm: null
    externalSecrets: null
  exampleApps:
    petclinic:
      baseDomain: "base-domain"
    nginx:
      baseDomain: "base-domain"
  ingressNginx:
    active: true
""")
    }
}
