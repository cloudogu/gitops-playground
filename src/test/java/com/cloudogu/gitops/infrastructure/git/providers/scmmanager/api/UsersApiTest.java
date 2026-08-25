package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import com.cloudogu.gitops.config.Credentials;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsersApiTest {

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
		.options(wireMockConfig()
			.dynamicPort()
			.dynamicHttpsPort())
		.build();

	private final Credentials credentials = new Credentials("user", "pass");

	@Test
	void allowsSelfSignedCertificatesWhenUsingInsecureOption() throws IOException {
		wireMock.stubFor(delete(urlPathEqualTo("/scm/api/v2/users/test-user"))
			.willReturn(aResponse().withStatus(204)));

		UsersApi api = usersApi(true, true);
		var response = api.delete("test-user").execute();

		assertThat(response.isSuccessful()).isTrue();
		wireMock.verify(1, deleteRequestedFor(urlPathEqualTo("/scm/api/v2/users/test-user")));
	}

	@Test
	void doesNotAllowSelfSignedCertificatesByDefault() {
		wireMock.stubFor(delete(urlPathEqualTo("/scm/api/v2/users/test-user"))
			.willReturn(aResponse().withStatus(204)));

		UsersApi api = usersApi(false, true);

		assertThrows(SSLHandshakeException.class, () -> api.delete("test-user").execute());

		wireMock.verify(0, deleteRequestedFor(urlPathEqualTo("/scm/api/v2/users/test-user")));
	}

	private UsersApi usersApi(boolean insecure, boolean useHttps) {
		return new ScmManagerApiClient(apiBaseUrl(useHttps), credentials, insecure).usersApi();
	}

	private String apiBaseUrl(boolean useHttps) {
		if (useHttps) {
			return "https://localhost:" + wireMock.getRuntimeInfo().getHttpsPort() + "/scm/api/";
		}
		return wireMock.baseUrl() + "/scm/api/";
	}
}
