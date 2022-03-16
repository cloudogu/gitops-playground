package com.cloudogu.gitops.utils

class TestConfig {
    static Map get() {
        return [
                registry   : [
                        url         : null,
                        path        : null,
                        username    : null,
                        password    : null,
                        internalPort: 0
                ],
                jenkins    : [
                        url     : null,
                        username: null,
                        password: null
                ],
                scmm       : [
                        url     : null,
                        username: null,
                        password: null
                ],
                application: [
                        remote        : false,
                        insecure      : false,
                        skipHelmUpdate: false,
                        debug         : false,
                        trace         : false,
                        username      : null,
                        password      : null,
                        pipeYes       : false,
                ],
                images     : [
                        kubectl    : null,
                        helm       : null,
                        kubeval    : null,
                        helmKubeval: null,
                        yamllint   : null
                ],
                modules    : [
                        fluxv1 : false,
                        fluxv2 : false,
                        argocd : [
                                active    : false,
                                configOnly: false,
                                url       : null
                        ],
                        metrics: false
                ],
                mailhog: [
                        username: null,
                        password: null
                ]
        ]
    }
}
