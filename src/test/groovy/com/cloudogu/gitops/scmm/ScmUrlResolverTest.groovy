package com.cloudogu.gitops.scmm

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.gitHandling.providers.ScmUrlResolver
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ScmUrlResolverTest {

    // ---------- externalHost ----------
    @Test
    void 'externalHost uses scmm url trims whitespace and enforces trailing slash'() {
        Config config = new Config(
                scmm: new Config.ScmmSchema(url: 'http://scmm '))
        URI uri = ScmUrlResolver.externalHost(config)
        assertEquals("http://scmm/", uri.toString())
    }

    @Test
    void 'externalHost preserve existing path and only appends trailing slash'() {
        Config config = new Config(
                scmm: new Config.ScmmSchema(
                        url: 'https://git.example.com/gitlab'
                )
        )
        URI uri = ScmUrlResolver.externalHost(config)
        assertEquals("https://git.example.com/gitlab/",uri.toString())

    }
    // ---------- scmmBaseUri ----------
    @Test
    void 'scmmBaseUri internal true returns svc cluster local url including scm'() {
        Config config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'dev-',
                ),
                scmm: new Config.ScmmSchema(
                        internal: true
                )
        )
        URI uri = ScmUrlResolver.scmmBaseUri(config);
        assertEquals("http://scmm.dev-scm-manager.svc.cluster.local/scm/", uri.toString());
    }

    @Test
    void 'scmmBaseUri external throws when url missing'() {
        Config config = new Config(
                scmm: new Config.ScmmSchema(
                        internal: false,
                )
        )
        assertThrows(IllegalArgumentException.class, () -> ScmUrlResolver.scmmBaseUri(config))
    }

    @Test
    void 'scmmBaseUri external preserves path and ensures trailing slash'() {
        Config config = new Config(
                scmm: new Config.ScmmSchema(
                        internal: false,
                        url: 'http://scmm.example.com/scm'
                )
        )
        URI uri = ScmUrlResolver.scmmBaseUri(config);
        assertEquals("http://scmm.example.com/scm/", uri.toString());

    }

    // ---------- tenantBaseUrl ----------
    @Test
    void 'tenantBaseUrl scmManager based on scmmBaseUri no trailing slash'() {
        Config config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'tenant-',
                ),
                scmm: new Config.ScmmSchema(
                        internal: false,
                        url: 'http://scmm.example.com/scm',
                        rootPath: "repo"
                )
        )
        String url = ScmUrlResolver.tenantBaseUrl(config);
        assertEquals("http://scmm.example.com/scm/repo/tenant-", url);
        assertFalse(url.endsWith("/"));
    }

    @Test
    void 'tenantBaseUrl gitlab based on externalHost no trailing slash'() {
        Config config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'tenant-',
                ),
                scmm: new Config.ScmmSchema(
                        provider: 'gitlab',
                        url: 'http://gitlab.example.com',
                        rootPath: "group"
                )
        )
        String url = ScmUrlResolver.tenantBaseUrl(config);

        assertEquals("http://gitlab.example.com/tenant-group", url);
        assertFalse(url.endsWith("/"));
    }

    @Test
    void 'tenantBaseUrl unknown provider throws'() {
        Config config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'tenant-',
                ),
                scmm: new Config.ScmmSchema(
                        provider: 'gitlabb',
                        url: 'http://gitlab.example.com',
                        rootPath: "group"
                )
        )
        assertThrows(IllegalArgumentException.class, () -> ScmUrlResolver.tenantBaseUrl(config));
    }

    // ---------- scmmRepoUrl ----------
    @Test
    void 'scmmRepoUrl appends root path and namespaceName after scmmBaseUri'() {
        Config config = new Config(
                application: new Config.ApplicationSchema(
                        namePrefix: 'dev-',
                ),
                scmm: new Config.ScmmSchema(
                        internal: true,
                        rootPath: 'repo',
                )
        )
        String url = ScmUrlResolver.scmmRepoUrl(config, "my-ns/my-repo");
        assertEquals("http://scmm.dev-scm-manager.svc.cluster.local/scm/repo/my-ns/my-repo", url);
    }
}
