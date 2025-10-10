///////////////////// ARGOCD TESTS


        // Content examples
        assertThat(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')).exists()
        assertThat(Path.of(argocdRepo.getAbsoluteLocalRepoTmpDir(), 'applications/example-apps.yaml')).exists()      



        assertAllYamlFiles(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'argocd', 7) { Path it ->
            def yaml = parseActualYaml(it.toString())
            List yamlDocuments = yaml instanceof List ? yaml : [yaml]
            for (def document in yamlDocuments) {
                assertThat(document['spec']['source']['repoURL'] as String)
                        .as("$it repoURL have name prefix")
                        .startsWith("${scmmUrl}/repo/${expectedPrefix}argocd")
            }
        }


    private void assertJenkinsEnvironmentVariablesPrefixes(String prefix) {
        List defaultRegistryEnvVars = ["env.${prefix}REGISTRY_URL", "env.${prefix}REGISTRY_PATH"]
        List twoRegistriesEnvVars = ["env.${prefix}REGISTRY_PROXY_URL"]

        assertThat(new File(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains("env.${prefix}K8S_VERSION")

        for (def petclinicRepo : petClinicRepos) {
            defaultRegistryEnvVars.each { expectedEnvVar ->
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(expectedEnvVar)
            }

            if (config.registry['twoRegistries']) {
                twoRegistriesEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains(expectedEnvVar)
                }
            } else {
                twoRegistriesEnvVars.each { expectedEnvVar ->
                    assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).doesNotContain(expectedEnvVar)
                }
            }
        }
    }


    @Test
    void 'When vault enabled: Pushes external secret, and mounts into example app'() {
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir(), 'k8s/values-shared.yaml')

        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(2)
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(2)

        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/staging/external-secret.yaml")).exists()
        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/production/external-secret.yaml")).exists()
        assertThat(new File(clusterResourcesRepo.getAbsoluteLocalRepoTmpDir() + "/misc/secrets")).exists()
    }



    @Test
    void 'When emailaddress is NOT set: Use default email addresses in configurations'() {
        config.features.mail.active = true

        createArgoCD().install()

        def exampleAppsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')

        assertThat(exampleAppsYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('app-team@example.org')
    }e.org')



    @Test
    void 'use custom maven image'() {
        config.images.maven = 'maven:latest'

        createArgoCD().install()

        for (def petclinicRepo : petClinicRepos) {
            if (petclinicRepo.scmmRepoTarget.contains('argocd/petclinic-plain')) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains('mvn = cesBuildLib.MavenInDocker.new(this, \'maven:latest\')')
            }
        }
    }



    @Test
    void 'use maven with proxy registry and credentials'() {
        config.images.maven = 'latest'
        config.registry.twoRegistries = true

        createArgoCD().install()

        for (def petclinicRepo : petClinicRepos) {
            if (petclinicRepo.scmmRepoTarget.contains('argocd/petclinic-plain')) {
                assertThat(new File(petclinicRepo.absoluteLocalRepoTmpDir, 'Jenkinsfile').text).contains('mvn = cesBuildLib.MavenInDocker.new(this, \'latest\', dockerRegistryProxyCredentials)')
            }
        }

    }



    @Test
    void 'When vault disabled: Does not push ExternalSecret and not mount into example app'() {
        config.features.secrets.active = false
        createArgoCD().install()
        def valuesYaml = new YamlSlurper().parse(Path.of nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir(), 'k8s/values-shared.yaml')
        assertThat((valuesYaml['extraVolumeMounts'] as List)).hasSize(1)
        assertThat((valuesYaml['extraVolumes'] as List)).hasSize(1)
        assertThat((valuesYaml['extraVolumeMounts'] as List)[0]['name']).isEqualTo('index')
        assertThat((valuesYaml['extraVolumes'] as List)[0]['name']).isEqualTo('index')

        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/staging/external-secret.yaml")).doesNotExist()
        assertThat(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir() + "/k8s/production/external-secret.yaml")).doesNotExist()
    }




    @Test
    void 'generate example-apps bootstrapping application via ArgoApplication when true'() {
        setup()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/bootstrap.yaml")).exists()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/argocd-application-example-apps-testPrefix-argocd.yaml")).exists()
    }

    @Test
    void 'not generating example-apps bootstrapping application via ArgoApplication when false'() {
        config.content.examples = false
        setup()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/bootstrap.yaml")).exists()
        assertThat(new File(tenantBootstrap.getAbsoluteLocalRepoTmpDir() + "/applications/argocd-application-example-apps-testPrefix-argocd.yaml")).doesNotExist()
    }



    @Test
    void 'For internal SCMM: Use service address in gitops repos'() {
        def argocd = createArgoCD()
        argocd.install()

        filesWithInternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
        assertThat(filesWithInternalSCMM).isNotEmpty()
    }




    @Test
    void 'Pushes example repos for remote'() {
        config.application.remote = true
        config.features.exampleApps.petclinic.baseDomain = 'petclinic.local'
        config.features.exampleApps.nginx.baseDomain = 'nginx.local'

        createArgoCD().install()

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml').toString())
                .doesNotContain('ClusterIP')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production.nginx-helm.nginx.local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging.nginx-helm.nginx.local')

        assertThat(parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml').toString())
                .doesNotContain('ClusterIP')

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production.nginx-helm-umbrella.nginx.local')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[2]['spec']['rules'] as List)[0]['host'])
                .isEqualTo('broken-application.nginx.local')
        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[1]['spec']['type']))
                .isEqualTo('LoadBalancer')

        assertPetClinicRepos('LoadBalancer', 'ClusterIP', 'petclinic.local')
    }



    @Test
    void 'Pushes example repos for local'() {
        config.application.remote = false
        def argocd = createArgoCD()

        def setUriMock = mock(CloneCommand.class, RETURNS_DEEP_STUBS)
        def checkoutMock = mock(CheckoutCommand.class, RETURNS_DEEP_STUBS)
        when(gitCloneMock.setURI(anyString())).thenReturn(setUriMock)
        when(setUriMock.setDirectory(any(File.class)).call().checkout()).thenReturn(checkoutMock)

        argocd.install()
        def valuesYaml = parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml')
        assertThat(valuesYaml['service']['type']).isEqualTo('ClusterIP')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')).doesNotContainKey('ingress')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')).doesNotContainKey('ingress')
        assertThat(valuesYaml).doesNotContainKey('resources')

        valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['service']['type']).isEqualTo('ClusterIP')
        assertThat(valuesYaml['nginx'] as Map).doesNotContainKey('ingress')
        assertThat(valuesYaml['nginx'] as Map).doesNotContainKey('resources')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[1]['spec']['type']))
                .isEqualTo('ClusterIP')
        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[0]['spec']['template']['spec']['containers'] as List)[0]['resources'])
                .isNull()

        assertThat(new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text).doesNotContain('resources:')

        // Assert Petclinic repo cloned
        verify(gitCloneMock).setURI('https://github.com/cloudogu/spring-petclinic.git')
        verify(setUriMock).setDirectory(argocd.remotePetClinicRepoTmpDir)
        verify(checkoutMock).setName('32c8653')

        assertPetClinicRepos('ClusterIP', 'LoadBalancer', '')
    }






    @Test
    void 'configures custom nginx image'() {
        config.images.nginx = 'localhost:5000/nginx/nginx:latest'
        createArgoCD().install()

        def image = parseActualYaml(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir + '/k8s/values-shared.yaml')['image']
        assertThat(image['registry']).isEqualTo('localhost:5000')
        assertThat(image['repository']).isEqualTo('nginx/nginx')
        assertThat(image['tag']).isEqualTo('latest')

        image = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')['nginx']['image']
        assertThat(image['registry']).isEqualTo('localhost:5000')
        assertThat(image['repository']).isEqualTo('nginx/nginx')
        assertThat(image['tag']).isEqualTo('latest')

        def deployment = parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[0]
        assertThat(deployment['kind']).as("Did not correctly fetch deployment from broken-application.yaml").isEqualTo("Deploymentz")
        assertThat((deployment['spec']['template']['spec']['containers'] as List)[0]['image']).isEqualTo('localhost:5000/nginx/nginx:latest')

        def yamlString = new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text
        assertThat(yamlString).startsWith("""image:
registry: localhost:5000
repository: nginx/nginx
tag: latest
""")
}





    @Test
    void 'For external SCMM: Use external address in gitops repos'() {
        config.scmm.internal = false
        def argocd = createArgoCD()
        argocd.install()

        filesWithInternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), argocd.scmmUrlInternal)
        assertThat(filesWithInternalSCMM).isEmpty()


        filesWithExternalSCMM = findFilesContaining(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), "https://abc")
        assertThat(filesWithExternalSCMM).isNotEmpty()
    }




    @Test
    void 'Sets pod resource limits and requests'() {
        config.application.podResources = true

        createArgoCD().install()

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-shared.yaml')['resources'] as Map)
                .containsKeys('limits', 'requests')

        assertThat(parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')['nginx']['resources'] as Map)
                .containsKeys('limits', 'requests')

        assertThat(new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text).contains('limits:', 'resources:')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[0]['spec']['template']['spec']['containers'] as List)[0]['resources'] as Map)
                .containsKeys('limits', 'requests')

        assertPetClinicRepos('ClusterIP', 'LoadBalancer', '')
    }



    @Test
    void 'Sets image pull secrets for nginx'() {
        config.registry.createImagePullSecrets = true
        config.registry.twoRegistries = true
        config.registry.proxyUrl = 'proxy-url'
        config.registry.proxyUsername = 'proxy-user'
        config.registry.proxyPassword = 'proxy-pw'

        createArgoCD().install()

        assertThat(parseActualYaml(nginxHelmJenkinsRepo.absoluteLocalRepoTmpDir + '/k8s/values-shared.yaml')['global']['imagePullSecrets'])
                .isEqualTo(['proxy-registry'])

        assertThat(parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')['nginx']['global']['imagePullSecrets'])
                .isEqualTo(['proxy-registry'])

        def deployment = parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[0]
        assertThat(deployment['spec']['imagePullSecrets']).isEqualTo([[name: 'proxy-registry']])

        assertThat(new File(nginxValidationRepo.absoluteLocalRepoTmpDir, '/k8s/values-shared.yaml').text)
                .contains("""global:
imagePullSecrets:
- proxy-registry
""")
}




    @Test
    void 'When emailaddress is set: Include given email addresses into configurations'() {
        config.features.mail.active = true
        config.features.argocd.emailFrom = 'argocd@example.com'
        config.features.argocd.emailToUser = 'app-team@example.com'
        config.features.argocd.emailToAdmin = 'argocd@example.com'
        createArgoCD().install()

        def exampleAppsYaml = new YamlSlurper().parse(Path.of argocdRepo.getAbsoluteLocalRepoTmpDir(), 'projects/example-apps.yaml')

        assertThat(exampleAppsYaml['metadata']['annotations']['notifications.argoproj.io/subscribe.email']).isEqualTo('app-team@example.com')
    }


    @Test
    void 'If urlSeparatorHyphen is NOT set, ensure that hostnames are build correctly '() {
        config.application.remote = true
        config.features.exampleApps.petclinic.baseDomain = 'petclinic.local'
        config.features.exampleApps.nginx.baseDomain = 'nginx.local'
        config.application.urlSeparatorHyphen = false

        createArgoCD().install()

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production.nginx-helm-umbrella.nginx.local')

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production.nginx-helm.nginx.local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging.nginx-helm.nginx.local')
        assertPetClinicRepos('LoadBalancer', 'ClusterIP', 'petclinic.local')
    }



    @Test
    void 'If urlSeparatorHyphen is set, ensure that hostnames are build correctly '() {
        config.application.remote = true
        config.features.exampleApps.petclinic.baseDomain = 'petclinic-local'
        config.features.exampleApps.nginx.baseDomain = 'nginx-local'
        config.application.urlSeparatorHyphen = true

        createArgoCD().install()

        def valuesYaml = parseActualYaml(new File(exampleAppsRepo.getAbsoluteLocalRepoTmpDir()), 'apps/nginx-helm-umbrella/values.yaml')
        assertThat(valuesYaml['nginx']['ingress']['hostname'] as String).isEqualTo('production-nginx-helm-umbrella-nginx-local')

        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-production.yaml')['ingress']['hostname']).isEqualTo('production-nginx-helm-nginx-local')
        assertThat(parseActualYaml(new File(nginxHelmJenkinsRepo.getAbsoluteLocalRepoTmpDir()), 'k8s/values-staging.yaml')['ingress']['hostname']).isEqualTo('staging-nginx-helm-nginx-local')

        assertThat((parseActualYaml(brokenApplicationRepo.absoluteLocalRepoTmpDir + '/broken-application.yaml')[2]['spec']['rules'] as List)[0]['host'])
                .isEqualTo('broken-application-nginx-local')

        assertPetClinicRepos('LoadBalancer', 'ClusterIP', 'petclinic-local')
    }


////////////// ApplicationConfigurator TESTS


    @Test
    void "Certain properties are read from env"() {
        withEnvironmentVariable('SPRING_BOOT_HELM_CHART_REPO', 'value1').execute {
            def actualConfig = new ApplicationConfigurator(fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.springBootHelmChart.url).isEqualTo('value1')
        }
        withEnvironmentVariable('SPRING_PETCLINIC_REPO', 'value2').execute {
            def actualConfig = new ApplicationConfigurator(fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.springPetclinic.url).isEqualTo('value2')
        }
        withEnvironmentVariable('GITOPS_BUILD_LIB_REPO', 'value3').execute {
            def actualConfig = new ApplicationConfigurator(fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.gitopsBuildLib.url).isEqualTo('value3')
        }
        withEnvironmentVariable('CES_BUILD_LIB_REPO', 'value4').execute {
            def actualConfig = new ApplicationConfigurator(fileSystemUtils).initConfig(new Config())
            assertThat(actualConfig.repositories.cesBuildLib.url).isEqualTo('value4')
        }
    }


    //// no hyphens
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic.localhost")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx.localhost")

    //// with hyphens
        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic-localhost")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx-localhost")


    @Test
    void "base url: individual url params take precedence"() {
        testConfig.application.baseUrl = 'http://localhost'

        testConfig.features.argocd.active = true
        testConfig.features.mail.active = true
        testConfig.features.monitoring.active = true
        testConfig.features.secrets.active = true

        testConfig.features.exampleApps.petclinic.baseDomain = 'petclinic'
        testConfig.features.exampleApps.nginx.baseDomain = 'nginx'

        def actualConfig = applicationConfigurator.initConfig(testConfig)

        assertThat(actualConfig.features.exampleApps.petclinic.baseDomain).isEqualTo("petclinic")
        assertThat(actualConfig.features.exampleApps.nginx.baseDomain).isEqualTo("nginx")
    }



/////////////// ApplicationConfigurator

        if (!newConfig.features.exampleApps.petclinic.baseDomain) {
            // This param only requires the host / domain
            newConfig.features.exampleApps.petclinic.baseDomain =
                    new URL(injectSubdomain('petclinic', baseUrl, urlSeparatorHyphen)).host
            log.debug("Setting Petclinic URL ${newConfig.features.exampleApps.petclinic.baseDomain}")
        }
        if (!newConfig.features.exampleApps.nginx.baseDomain) {
            // This param only requires the host / domain
            newConfig.features.exampleApps.nginx.baseDomain =
                    new URL(injectSubdomain('nginx', baseUrl, urlSeparatorHyphen)).host
            log.debug("Setting Nginx URL ${newConfig.features.exampleApps.nginx.baseDomain}")
        }



//////////////////// GITLAB

        String dependencysGroupName = '3rd-party-dependencies'
        Optional<Group> dependencysGroup = getGroup("${mainGroupName}/${dependencysGroupName}")
        if (dependencysGroup.isEmpty()) {
            def tempGroup = new Group()
                    .withName(dependencysGroupName)
                    .withPath(dependencysGroupName.toLowerCase())
                    .withParentId(mainSCMGroup.id)

            addGroup(tempGroup)
        }

        String exercisesGroupName = 'exercises'
        Optional<Group> exercisesGroup = getGroup("${mainGroupName}/${exercisesGroupName}")
        if (exercisesGroup.isEmpty()) {
            def tempGroup = new Group()
                    .withName(exercisesGroupName)
                    .withPath(exercisesGroupName.toLowerCase())
                    .withParentId(mainSCMGroup.id)

            exercisesGroup = addGroup(tempGroup)
        }

        exercisesGroup.ifPresent(this.&createExercisesRepos)