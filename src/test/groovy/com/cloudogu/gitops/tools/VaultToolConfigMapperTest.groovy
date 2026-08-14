package com.cloudogu.gitops.tools

import com.cloudogu.gitops.application.context.DeploymentContext
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.tools.common.HelmChartConfig
import com.cloudogu.gitops.tools.common.ImagePullSecretConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

import static org.assertj.core.api.Assertions.assertThat

class VaultToolConfigMapperTest {

    @Test
    void 'maps all relevant values from deployment context and config'() {
        Config config = config()
        config.features.secrets.vault.mode = Config.VaultMode.PROD

        VaultToolConfig actual = new VaultToolConfigMapper(config).map(context(config))

        assertThat(actual).isEqualTo(VaultToolConfig.builder()
                .active(true)
                .namespace('test-secrets')
                .namePrefix('test-')
                .url('https://vault.example.org')
                .developmentMode(false)
                .helm(HelmChartConfig.builder()
                        .repoURL('https://vault-chart.example.org')
                        .chart('vault-chart')
                        .version('5.6.7')
                        .values([ha: true])
                        .localHelmChartFolder('/charts')
                        .build())
                .imagePullSecret(imagePullSecret())
                .templateConfig([
                        application: [
                                namePrefix        : 'test-',
                                namespaceIsolation: true,
                                openshift         : true,
                                password          : 'application-password',
                                podResources      : true,
                                username          : 'application-user'
                        ],
                        features   : [
                                argocd     : [
                                        active: true
                                ],
                                certManager: [
                                        active: true,
                                        issuer: 'production-issuer'
                                ],
                                secrets    : [
                                        vault: [
                                                oidc: [
                                                        providerName  : 'Keycloak',
                                                        issuerUrl     : '',
                                                        clientId      : 'vault-client',
                                                        clientSecret  : '',
                                                        scopes        : ['openid', 'profile', 'email'],
                                                        adminGroupName: '',
                                                        enabled       : false
                                                ],
                                                helm: [
                                                        image: 'vault-image'
                                                ]
                                        ]
                                ]
                        ],
                        registry   : [
                                createImagePullSecrets: true
                        ]
                ])
                .build())
    }

    @ParameterizedTest
    @CsvSource([
            'DEV, true',
            'PROD, false'
    ])
    void 'maps vault mode to development mode'(Config.VaultMode mode, boolean expectedDevelopmentMode) {
        Config config = config()
        config.features.secrets.vault.mode = mode

        VaultToolConfig actual = new VaultToolConfigMapper(config).map(context(config))

        assertThat(actual.developmentMode()).isEqualTo(expectedDevelopmentMode)
    }

    private static Config config() {
        Config config = new Config()

        config.application.namePrefix = 'test-'
        config.application.localHelmChartFolder = '/charts'
        config.application.namespaceIsolation = true
        // Intentionally differs from the DeploymentContext to verify derived values come from the context.
        config.application.openshift = false
        config.application.password = 'application-password'
        config.application.podResources = true
        config.application.username = 'application-user'

        config.registry.createImagePullSecrets = true
        config.registry.proxyUrl = 'proxy.example.org'
        config.registry.url = 'registry.example.org'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.readOnlyUsername = 'read-only-user'
        config.registry.username = 'registry-user'
        config.registry.proxyPassword = 'proxy-password'
        config.registry.readOnlyPassword = 'read-only-password'
        config.registry.password = 'registry-password'

        config.features.argocd.active = true

        config.features.certManager.active = true
        config.features.certManager.issuer = 'production-issuer'

        config.features.secrets.active = true
        config.features.secrets.namespace = 'secrets'
        config.features.secrets.vault.url = 'https://vault.example.org'
        config.features.secrets.vault.oidc.clientId = 'vault-client'

        config.features.secrets.vault.helm.repoURL = 'https://vault-chart.example.org'
        config.features.secrets.vault.helm.chart = 'vault-chart'
        config.features.secrets.vault.helm.version = '5.6.7'
        config.features.secrets.vault.helm.values = [ha: true]
        config.features.secrets.vault.helm.image = 'vault-image'

        return config
    }

    private static DeploymentContext context(Config config) {
        return new DeploymentContext(
                config,
                DeploymentContext.TenantMode.SINGLE_TENANT,
                DeploymentContext.ScmManagerDeploymentMode.EXTERNAL,
                false,
                DeploymentContext.ClusterDistribution.OPENSHIFT)
    }

    private static ImagePullSecretConfig imagePullSecret() {
        return ImagePullSecretConfig.builder()
                .create(true)
                .proxyUrl('proxy.example.org')
                .url('registry.example.org')
                .proxyUsername('proxy-user')
                .readOnlyUsername('read-only-user')
                .username('registry-user')
                .proxyPassword('proxy-password')
                .readOnlyPassword('read-only-password')
                .password('registry-password')
                .build()
    }
}