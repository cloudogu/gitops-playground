package com.cloudogu.gitops.config.schema

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import groovy.transform.CompileDynamic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import picocli.CommandLine

import static org.assertj.core.api.Assertions.assertThat

@CompileDynamic
@TypeChecked(TypeCheckingMode.SKIP)
class CredentialsDelegationTest {

    // ── Credentials class ──────────────────────────────────────────────

    @Test
    void 'copy constructor copies all fields'() {
        def original = new Credentials('admin', 'secret', 'my-secret', 'prod-ns', 'user', 'pass')

        def copy = new Credentials(original)

        assertThat(copy.username).isEqualTo('admin')
        assertThat(copy.password).isEqualTo('secret')
        assertThat(copy.secretName).isEqualTo('my-secret')
        assertThat(copy.secretNamespace).isEqualTo('prod-ns')
        assertThat(copy.usernameKey).isEqualTo('user')
        assertThat(copy.passwordKey).isEqualTo('pass')
    }

    @Test
    void 'copy constructor with null is safe'() {
        def copy = new Credentials(null)

        assertThat(copy.username).isNull()
        assertThat(copy.password).isNull()
        assertThat(copy.secretName).isNull()
    }

    @Test
    void 'two-arg constructor sets defaults for keys'() {
        def creds = new Credentials('user', 'pw')

        assertThat(creds.username).isEqualTo('user')
        assertThat(creds.password).isEqualTo('pw')
        assertThat(creds.usernameKey).isEqualTo('username')
        assertThat(creds.passwordKey).isEqualTo('password')
        assertThat(creds.secretName).isEmpty()
        assertThat(creds.secretNamespace).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ['registry', 'jenkins', 'application'])
    void 'setting credentials stores the reference without changing plain values'(String section) {
        def schema = new Config()."$section"
        schema.username = 'configured-user'
        schema.password = 'configured-password'
        def reference = new Credentials('', '', 'secret', 'namespace')

        schema.credentials = reference

        assertThat(schema.credentials).isSameAs(reference)
        assertThat(schema.username).isEqualTo('configured-user')
        assertThat(schema.password).isEqualTo('configured-password')
    }

    @Test
    void 'parses plain credentials from CLI arguments'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--registry-username', 'registry-user', '--registry-password', 'registry-password',
                '--jenkins-username', 'jenkins-user', '--jenkins-password', 'jenkins-password',
                '--username', 'app-user', '--password', 'app-password',
                '--smtp-user', 'mail-user', '--smtp-password', 'mail-password'
        )

        assertThat(config.registry.username).isEqualTo('registry-user')
        assertThat(config.registry.password).isEqualTo('registry-password')
        assertThat(config.jenkins.username).isEqualTo('jenkins-user')
        assertThat(config.jenkins.password).isEqualTo('jenkins-password')
        assertThat(config.application.username).isEqualTo('app-user')
        assertThat(config.application.password).isEqualTo('app-password')
        assertThat(config.features.mail.smtpUser).isEqualTo('mail-user')
        assertThat(config.features.mail.smtpPassword).isEqualTo('mail-password')
    }

    @Test
    void 'ScmManagerTenantConfig uses default admin credentials'() {
        def schema = new ScmTenantSchema.ScmManagerTenantConfig()

        assertThat(schema.credentials.username).isEqualTo(Config.DEFAULT_ADMIN_USER)
        assertThat(schema.credentials.password).isEqualTo(Config.DEFAULT_ADMIN_PW)
    }
}
