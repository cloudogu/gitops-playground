package com.cloudogu.gitops.features.deployment

import org.junit.jupiter.api.Test

import com.cloudogu.gitops.config.Config

import java.nio.file.Path

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class DeployerTest {
    private ArgoCdApplicationStrategy argoCdStrat = mock(ArgoCdApplicationStrategy.class)
    private HelmStrategy helmStrat = mock(HelmStrategy.class)

    @Test
    void 'When argocd disabled, deploys imperatively via helm'() {
        def deployer = createDeployer(false)

        deployer.deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"))

        verify(argoCdStrat, never()).deployFeature(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(Path))
        verify(helmStrat).deployFeature("repoURL", "repoName", "chart", "version", "namespace", 
                "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM)
    }


    @Test
    void 'When Argo CD enabled, deploys natively via Argo CD'() {
        def deployer = createDeployer(true)

        deployer.deployFeature("repoURL", "repoName", "chart", "version", "namespace", "releaseName", Path.of("values.yaml"))

        verify(argoCdStrat).deployFeature("repoURL", "repoName", "chart", "version", "namespace",
                "releaseName", Path.of("values.yaml"), DeploymentStrategy.RepoType.HELM)
        verify(helmStrat, never()).deployFeature(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(Path))
    }

    private Deployer createDeployer(boolean argoCDActive) {
        Config config = new Config(features: new Config.FeaturesSchema(argocd: new Config.ArgoCDSchema(active:argoCDActive)))

        return new Deployer(config, argoCdStrat, helmStrat)
    }
}