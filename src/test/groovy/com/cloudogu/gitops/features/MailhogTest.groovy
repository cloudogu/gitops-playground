package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class MailhogTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
            ],
            scmm       : [
                    internal: true,
                    protocol: 'https',
                    host: 'abc',
                    username: '',
                    password: ''
            ],
            features    : [
                    argocd    : [
                            active: false,
                    ],
                    fluxv2    : false,
                    mail      : [
                            active: true,
                            helm  : [
                                    chart  : 'mailhog',
                                    repoURL: 'https://codecentric.github.io/helm-charts',
                                    version: '5.0.1'
                            ]
                    ]
            ],
    ]
    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(commandExecutor)
    Path temporaryYamlFile

    @Test
    void "is disabled via active flag"() {
        config['features']['mail']['active'] = false
        createMailhog().install()
        assertThat(temporaryYamlFile).isNull()
    }

    @Test
    void 'service type LoadBalancer when run remotely'() {
        config['application']['remote'] = true
        createMailhog().install()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('LoadBalancer')
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createMailhog().install()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void 'Password and username can be changed'() {
        String expectedUsername = 'user42'
        String expectedPassword = '12345'
        config['application']['username'] = expectedUsername
        config['application']['password'] = expectedPassword
        createMailhog().install()

        String fileContents = parseActualYaml()['auth']['fileContents']
        String actualPasswordBcrypted = ((fileContents =~ /^[^:]*:(.*)$/)[0] as List)[1]
        new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)
        assertThat(new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)).isTrue()
                .withFailMessage("Expected password does not match actual hash")
    }

    @Test
    void 'When flux and argocd enabled, mailhog is deployed imperatively via helm'() {
        config.features['argocd']['active'] = true
        config.features['fluxv2'] = true
        
        createMailhog().install()

        assertMailhogInstalledImperativelyViaHelm()
    }
    
    @Test
    void 'When flux enabled, mailhog is deployed imperatively via helm'() {
        config.features['argocd']['active'] = false
        config.features['fluxv2'] = true
        
        createMailhog().install()

        assertMailhogInstalledImperativelyViaHelm()
    }

    @Test
    void 'When only argoCD enabled, mailhog is deployed natively via argoCD'() {
        config.features['argocd']['active'] = true
        config.features['fluxv2'] = false
        
        createMailhog().install()
    }

    protected void assertMailhogInstalledImperativelyViaHelm() {
        assertThat(commandExecutor.actualCommands[0].trim()).isEqualTo(
                'helm repo add mailhog https://codecentric.github.io/helm-charts')
        assertThat(commandExecutor.actualCommands[1].trim()).isEqualTo(
                'helm upgrade -i mailhog mailhog/mailhog --version 5.0.1' +
                        " --values ${temporaryYamlFile} --namespace foo-monitoring")
    }

    private Mailhog createMailhog() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        new Mailhog(new Configuration(config), new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                temporaryYamlFile = super.copyToTempDir(filePath)
                return temporaryYamlFile
            }
        }, new HelmStrategy(new Configuration(config), helmClient))
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}
