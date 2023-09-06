package com.cloudogu.gitops.scmm.jgit


import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class InsecureCredentialProviderTest {
    @Test
    void 'ignores irrelevant items'() {
        def provider = new InsecureCredentialProvider()

        assertThat(provider.supports(new CredentialItem.Username(), new CredentialItem.Password())).isFalse()
        assertThat(provider.supports(
                new CredentialItem.InformationalMessage("This is not a relevant message"),
                new CredentialItem.YesNoType("This prompt is irrelevant as well")
        )).isFalse()
    }

    @Test
    void 'confirms insecure https processing'() {
        def provider = new InsecureCredentialProvider()

        def message = new CredentialItem.InformationalMessage("A secure connection to https://192.168.178.37/scm/repo/argocd/cluster-resources could not be established because the server's certificate could not be validated.\n" +
                "SSL reported: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target\n" +
                "Do you want to skip SSL verification for this server?")
        def skipSingle = new CredentialItem.YesNoType("Skip SSL verification for this single git operation")
        def skipRepository = new CredentialItem.YesNoType("Skip SSL verification for git operations for repository /tmp/groovy-generated-tmpdir-2746077697650757929/.git")
        def skipAlways = new CredentialItem.YesNoType("Always skip SSL verification for this server from now on")

        assertThat(provider.supports(
                message,
                skipSingle,
                skipRepository,
                skipAlways
        )).isTrue()

        assertThat(provider.get(new URIish("https://192.168.178.37/scm/repo/argocd/cluster-resources"), message, skipSingle, skipRepository, skipAlways))
            .isTrue()

        assertThat(skipSingle.value).isTrue()
        assertThat(skipRepository.value).isTrue()
        assertThat(skipAlways.value).isFalse()
    }
}
