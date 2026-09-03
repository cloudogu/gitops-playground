package com.cloudogu.gitops.config.schema

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.config.scm.ScmCentralSchema
import com.cloudogu.gitops.config.scm.ScmTenantSchema
import org.junit.jupiter.api.Test
import picocli.CommandLine

import static com.cloudogu.gitops.config.Config.*
import static org.assertj.core.api.Assertions.assertThat

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

    // ── Lazy-init getCredentials() ─────────────────────────────────────

    @Test
    void 'RegistrySchema creates credentials from username-password when null'() {
        def schema = new RegistrySchema(username: 'reg-user', password: 'reg-pw')

        def creds = schema.credentials

        assertThat(creds).isNotNull()
        assertThat(creds.username).isEqualTo('reg-user')
        assertThat(creds.password).isEqualTo('reg-pw')
    }

    @Test
    void 'JenkinsSchema creates credentials from username-password when null'() {
        def schema = new JenkinsSchema(username: 'jenkins-user', password: 'jenkins-pw')

        def creds = schema.credentials

        assertThat(creds).isNotNull()
        assertThat(creds.username).isEqualTo('jenkins-user')
        assertThat(creds.password).isEqualTo('jenkins-pw')
    }

    @Test
    void 'MailSchema creates credentials from smtpUser-smtpPassword when null'() {
        def schema = new MailSchema(smtpUser: 'smtp-user', smtpPassword: 'smtp-pw')

        def creds = schema.credentials

        assertThat(creds).isNotNull()
        assertThat(creds.username).isEqualTo('smtp-user')
        assertThat(creds.password).isEqualTo('smtp-pw')
    }

    @Test
    void 'RegistrySchema with only legacy fields creates valid credentials'() {
        def schema = new RegistrySchema(username: 'reg-user', password: 'reg-pw')

        assertThat(schema.credentials).isNotNull()
        assertThat(schema.credentials.username).isEqualTo('reg-user')
        assertThat(schema.credentials.password).isEqualTo('reg-pw')
        assertThat(schema.credentials.secretName).isEmpty()
    }

    @Test
    void 'does not overwrite non-empty credentials username'() {
        def schema = new RegistrySchema()
        schema.credentials = new Credentials('existing', 'existing', 'my-secret', 'ns')

        def creds = schema.credentials

        assertThat(creds.username).isEqualTo('existing')
        assertThat(creds.password).isEqualTo('existing')
    }

    @Test
    void 'ApplicationSchema does not back-fill when secretName is set'() {
        def schema = new ApplicationSchema()
        schema.credentials = new Credentials('', '', 'app-secret', 'app-ns')

        def creds = schema.credentials

        assertThat(creds.username).isEmpty()
        assertThat(creds.password).isEmpty()
        assertThat(creds.secretName).isEqualTo('app-secret')
    }

    @Test
    void 'ApplicationSchema with only legacy fields creates valid credentials'() {
        def schema = new ApplicationSchema(username: 'app-user', password: 'app-pw')

        def creds = schema.credentials

        assertThat(creds.username).isEqualTo('app-user')
        assertThat(creds.password).isEqualTo('app-pw')
    }

    // ── setCredentials() propagation ───────────────────────────────────

    @Test
    void 'setCredentials propagates username and password to legacy fields'() {
        def schema = new RegistrySchema()
        schema.credentials = new Credentials('new-user', 'new-pw', 'sec', 'ns')

        assertThat(schema.username).isEqualTo('new-user')
        assertThat(schema.password).isEqualTo('new-pw')
    }

    @Test
    void 'setCredentials with null credentials does not crash'() {
        def schema = new RegistrySchema(username: 'old', password: 'old')
        schema.credentials = null

        assertThat(schema.username).isEqualTo('old')
    }

    @Test
    void 'MailSchema setCredentials propagates to smtpUser and smtpPassword'() {
        def schema = new MailSchema()
        schema.credentials = new Credentials('mail-user', 'mail-pw')

        assertThat(schema.smtpUser).isEqualTo('mail-user')
        assertThat(schema.smtpPassword).isEqualTo('mail-pw')
    }

    // ── setUsername-setPassword propagation ─────────────────────────────

    @Test
    void 'setUsername propagates to credentials when credentials exists'() {
        def schema = new RegistrySchema()
        schema.credentials = new Credentials('old', 'old')
        schema.username = 'updated'

        assertThat(schema.credentials.username).isEqualTo('updated')
    }

    @Test
    void 'setPassword propagates to existing credentials'() {
        def schema = new RegistrySchema()
        schema.credentials = new Credentials('old', 'old')
        schema.password = 'new-pw'

        assertThat(schema.credentials.password).isEqualTo('new-pw')
    }

    // ── CLI: RegistrySchema secrets ────────────────────────────────────

    @Test
    void 'parses registry secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--registry-secret-name', 'reg-sec',
                '--registry-secret-namespace', 'reg-ns'
        )

        assertThat(config.registry.secretName).isEqualTo('reg-sec')
        assertThat(config.registry.secretNamespace).isEqualTo('reg-ns')
    }

    @Test
    void 'parses registry proxy secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--registry-proxy-secret-name', 'proxy-sec',
                '--registry-proxy-secret-namespace', 'proxy-ns'
        )

        assertThat(config.registry.proxySecretName).isEqualTo('proxy-sec')
        assertThat(config.registry.proxySecretNamespace).isEqualTo('proxy-ns')
    }

    @Test
    void 'parses registry read-only secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--registry-read-only-secret-name', 'ro-sec',
                '--registry-read-only-secret-namespace', 'ro-ns'
        )

        assertThat(config.registry.readOnlySecretName).isEqualTo('ro-sec')
        assertThat(config.registry.readOnlySecretNamespace).isEqualTo('ro-ns')
    }

    // ── CLI: JenkinsSchema secrets ─────────────────────────────────────

    @Test
    void 'parses jenkins secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--jenkins-secret-name', 'jenkins-sec',
                '--jenkins-secret-namespace', 'jenkins-ns'
        )

        assertThat(config.jenkins.secretName).isEqualTo('jenkins-sec')
        assertThat(config.jenkins.secretNamespace).isEqualTo('jenkins-ns')
    }

    @Test
    void 'parses jenkins metrics secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--jenkins-metrics-secret-name', 'metrics-sec',
                '--jenkins-metrics-secret-namespace', 'metrics-ns'
        )

        assertThat(config.jenkins.metricsSecretName).isEqualTo('metrics-sec')
        assertThat(config.jenkins.metricsSecretNamespace).isEqualTo('metrics-ns')
    }

    // ── CLI: ApplicationSchema secrets ─────────────────────────────────

    @Test
    void 'parses application secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--secret-name', 'app-sec',
                '--secret-namespace', 'app-ns'
        )

        assertThat(config.application.secretName).isEqualTo('app-sec')
        assertThat(config.application.secretNamespace).isEqualTo('app-ns')
    }

    // ── CLI: MailSchema secrets ────────────────────────────────────────

    @Test
    void 'parses smtp secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--smtp-secret-name', 'mail-sec',
                '--smtp-secret-namespace', 'mail-ns'
        )

        assertThat(config.features.mail.smtpSecretName).isEqualTo('mail-sec')
        assertThat(config.features.mail.smtpSecretNamespace).isEqualTo('mail-ns')
    }

    // ── CLI: ScmCentralSchema secrets ──────────────────────────────────

    @Test
    void 'parses central gitlab secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--central-gitlab-secret-name', 'cg-sec',
                '--central-gitlab-secret-namespace', 'cg-ns'
        )

        assertThat(config.multiTenant.gitlab.secretName).isEqualTo('cg-sec')
        assertThat(config.multiTenant.gitlab.secretNamespace).isEqualTo('cg-ns')
    }

    @Test
    void 'parses central scmm secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--central-scmm-secret-name', 'cs-sec',
                '--central-scmm-secret-namespace', 'cs-ns'
        )

        assertThat(config.multiTenant.scmManager.secretName).isEqualTo('cs-sec')
        assertThat(config.multiTenant.scmManager.secretNamespace).isEqualTo('cs-ns')
    }

    // ── CLI: ScmTenantSchema secrets ───────────────────────────────────

    @Test
    void 'parses gitlab tenant secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--gitlab-secret-name', 'gt-sec',
                '--gitlab-secret-namespace', 'gt-ns'
        )

        assertThat(config.scm.gitlab.secretName).isEqualTo('gt-sec')
        assertThat(config.scm.gitlab.secretNamespace).isEqualTo('gt-ns')
    }

    @Test
    void 'parses scmm tenant secret CLI args'() {
        def config = new Config()

        new CommandLine(config).parseArgs(
                '--scmm-secret-name', 'st-sec',
                '--scmm-secret-namespace', 'st-ns'
        )

        assertThat(config.scm.scmManager.secretName).isEqualTo('st-sec')
        assertThat(config.scm.scmManager.secretNamespace).isEqualTo('st-ns')
    }

    // ── Map deserialization ────────────────────────────────────────────

    @Test
    void 'deserializes jenkins credentials with secretName from map'() {
        def config = Config.fromMap([
                jenkins: [
                        credentials: [
                                secretName     : 'my-sec',
                                secretNamespace: 'my-ns'
                        ]
                ]
        ])

        assertThat(config.jenkins.credentials.secretName).isEqualTo('my-sec')
        assertThat(config.jenkins.credentials.secretNamespace).isEqualTo('my-ns')
    }

    @Test
    void 'deserializes registry proxy credentials from map'() {
        def config = Config.fromMap([
                registry: [
                        proxyCredentials: [
                                username       : 'proxy-user',
                                secretName     : 'proxy-sec',
                                secretNamespace: 'proxy-ns'
                        ]
                ]
        ])

        assertThat(config.registry.proxyCredentials.username).isEqualTo('proxy-user')
        assertThat(config.registry.proxyCredentials.secretName).isEqualTo('proxy-sec')
        assertThat(config.registry.proxyCredentials.secretNamespace).isEqualTo('proxy-ns')
    }

    @Test
    void 'deserializes jenkins metrics credentials from map'() {
        def config = Config.fromMap([
                jenkins: [
                        metricsCredentials: [
                                username       : 'met-user',
                                secretName     : 'met-sec',
                                secretNamespace: 'met-ns'
                        ]
                ]
        ])

        assertThat(config.jenkins.metricsCredentials.username).isEqualTo('met-user')
        assertThat(config.jenkins.metricsCredentials.secretName).isEqualTo('met-sec')
        assertThat(config.jenkins.metricsCredentials.secretNamespace).isEqualTo('met-ns')
    }

    @Test
    void 'deserializes mail credentials from map'() {
        def config = Config.fromMap([
                features: [
                        mail: [
                                credentials: [
                                        username       : 'mail-user',
                                        secretName     : 'mail-sec',
                                        secretNamespace: 'mail-ns'
                                ]
                        ]
                ]
        ])

        assertThat(config.features.mail.credentials.username).isEqualTo('mail-user')
        assertThat(config.features.mail.credentials.secretName).isEqualTo('mail-sec')
        assertThat(config.features.mail.credentials.secretNamespace).isEqualTo('mail-ns')
    }

    // ── Roundtrip ──────────────────────────────────────────────────────

    @Test
    void 'setting legacy username-password reflects in credentials after lazy-init'() {
        def schema = new JenkinsSchema()
        schema.username = 'j-user'
        schema.password = 'j-pw'

        def creds = schema.credentials

        assertThat(creds.username).isEqualTo('j-user')
        assertThat(creds.password).isEqualTo('j-pw')
    }

    @Test
    void 'setting credentials then reading legacy fields returns credentials values'() {
        def schema = new ScmCentralSchema.GitlabCentralConfig()
        schema.credentials = new Credentials('gl-user', 'gl-pw', 'gl-sec', 'gl-ns')

        assertThat(schema.username).isEqualTo('gl-user')
        assertThat(schema.password).isEqualTo('gl-pw')
    }

    @Test
    void 'ScmManagerTenantConfig lazy-init uses default admin credentials'() {
        def schema = new ScmTenantSchema.ScmManagerTenantConfig()

        def creds = schema.credentials

        assertThat(creds.username).isEqualTo(Config.DEFAULT_ADMIN_USER)
        assertThat(creds.password).isEqualTo(Config.DEFAULT_ADMIN_PW)
    }
}
