package com.cloudogu.gitops.core.modules.metrics.argocd

import com.cloudogu.gitops.core.utils.FileSystemUtils
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import static org.assertj.core.api.Assertions.assertThat 

class MailhogTest {
    public @TempDir
    Path tempDir
    File temporaryYamlFile

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false
            ],
            modules    : [metrics: true]
    ]

    @BeforeEach
    void setup() {
        File originalStackYamlFile = new File("${System.properties['user.dir']}/argocd/control-app/${Mailhog.MAILHOG_YAML_PATH}")
        temporaryYamlFile = new File("${tempDir.toString()}/${Mailhog.MAILHOG_YAML_PATH}")
        temporaryYamlFile.mkdirs()
        Files.copy(originalStackYamlFile.toPath(), temporaryYamlFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    void 'service type LoadBalancer when run remotely'() {
        config['application']['remote'] = true
        createMailhog().configure()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('LoadBalancer')
    }

    @Test
    void 'service type NodePort when not run remotely'() {
        config['application']['remote'] = false
        createMailhog().configure()

        assertThat(parseActualYaml()['service']['type']).isEqualTo('NodePort')
    }

    @Test
    void 'Password and username can be changed'() {
        String expectedUsername = 'user42'
        String expectedPassword = '12345'
        config['application']['username'] = expectedUsername
        config['application']['password'] = expectedPassword
        createMailhog().configure()
        
        String fileContents = parseActualYaml()['auth']['fileContents']
        String actualPasswordBcrypted = ((fileContents =~ /^[^:]*:(.*)$/)[0] as List)[1]
        new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)
        assertThat(new BCryptPasswordEncoder().matches(expectedPassword, actualPasswordBcrypted)).isTrue()
                .withFailMessage("Expected password does not match actual hash")
    }
    
    private Mailhog createMailhog() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        new Mailhog(config, tempDir.toString(), new FileSystemUtils())
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parseText(ys.parse(temporaryYamlFile)['spec']['source']['helm']['values'].toString())
    }
}