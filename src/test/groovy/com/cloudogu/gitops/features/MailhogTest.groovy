package com.cloudogu.gitops.features

import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat 

class MailhogTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false
            ]]
    HelmClient helmClient = Mockito.mock(HelmClient.class)
    Path temporaryYamlFile

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
    
    private Mailhog createMailhog() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected
        
        new Mailhog(config, new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                temporaryYamlFile = super.copyToTempDir(filePath)
                return temporaryYamlFile
            } 
        }, helmClient)
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }
}