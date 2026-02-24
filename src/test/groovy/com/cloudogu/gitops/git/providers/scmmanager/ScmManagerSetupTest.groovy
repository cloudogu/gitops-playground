package com.cloudogu.gitops.git.providers.scmmanager

import org.junit.jupiter.api.Test
import retrofit2.Call
import retrofit2.Response

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.git.providers.scmmanager.api.PluginApi
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApi
import com.cloudogu.gitops.git.providers.scmmanager.api.ScmManagerApiClient

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.eq
import static org.mockito.Mockito.*

class ScmManagerSetupTest {

    ScmManager scmManager = mock(ScmManager.class)

    HelmStrategy helmStrategy = mock(HelmStrategy.class)
    ScmManagerApiClient apiClient = mock(ScmManagerApiClient.class)

    PluginApi pluginApi = mock(PluginApi.class)
    ScmManagerApi generalApi = mock(ScmManagerApi.class)

    Config config = Config.fromMap([
            application: [
                    namePrefix: 'test',
            ],
            scm        : [
                    scmManager: [
                            internal      : true,
                            url           : "",
                            namespace     : "scm-manager",
                            username      : "admin",
                            password      : "admin",
                            helm          : [
                                    chart  : "scm-manager",
                                    repoURL: "https://packages.scm-manager.org/repository/helm-v2-releases/",
                                    version: "3.11.2",
                                    values : [:]
                            ],
                            rootPath      : "repo",
                            urlForJenkins : "http://scmm.scm-manager.svc.cluster.local/scm",
                            ingress       : "scmm.master.localhost",
                            skipRestart   : false,
                            skipPlugins   : false,
                            gitOpsUsername: ""
                    ]
            ]
    ])

    @Test
    void 'Helm chart is installed correctly'() {
        when(scmManager.getConfig()).thenReturn(config)
        when(scmManager.getHelmStrategy()).thenReturn(helmStrategy)
        when(scmManager.getScmmConfig()).thenReturn(config.scm.scmManager)
        ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager)
        scmManagerSetup.setupHelm()
        verify(helmStrategy).deployFeature(
                eq( "https://packages.scm-manager.org/repository/helm-v2-releases/"),
                eq("scm-manager"),
                any(),
                eq("3.11.2"),
                eq("scm-manager"),
                eq("scmm"),
                any()
        )
    }

    @Test
    void 'ScmManager Plugins are installed correctly'() {
        when(scmManager.getConfig()).thenReturn(config)
        when(scmManager.getHelmStrategy()).thenReturn(helmStrategy)
        when(scmManager.getScmmConfig()).thenReturn(config.scm.scmManager)
        when(scmManager.getApiClient()).thenReturn(apiClient)

        Call<Void> apiCall = mock(Call.class)

        when(pluginApi.install(any(),any())).thenReturn(apiCall)
        when(generalApi.checkScmmAvailable()).thenReturn(apiCall)
        when(apiClient.pluginApi()).thenReturn(pluginApi)
        when(apiClient.generalApi()).thenReturn(generalApi)
        when(apiCall.execute()).thenReturn(Response.success(null))
        ScmManagerSetup scmManagerSetup = new ScmManagerSetup(scmManager)
        scmManagerSetup.installScmmPlugins()
        verify(pluginApi,atLeast(10)).install(any(),any())
    }

}