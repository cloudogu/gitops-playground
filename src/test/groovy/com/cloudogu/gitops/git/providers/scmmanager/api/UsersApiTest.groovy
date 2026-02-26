package com.cloudogu.gitops.git.providers.scmmanager.api

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat

import com.cloudogu.gitops.config.Credentials

import javax.net.ssl.SSLHandshakeException

import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class UsersApiTest {

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance()
		.options(wireMockConfig()
			.dynamicPort()
			.dynamicHttpsPort())
		.build()

	private Credentials credentials = new Credentials("user", "pass")

	@Test
	void 'allows self-signed certificates when using insecure option'() {
		wireMock.stubFor(delete(urlPathEqualTo("/scm/api/v2/users/test-user"))
			.willReturn(aResponse().withStatus(204)))

		def api = usersApi(true, true)
		// insecure=true, useHttps=true
		def resp = api.delete('test-user').execute()

		assertThat(resp.isSuccessful()).isTrue()
		wireMock.verify(1, deleteRequestedFor(urlPathEqualTo("/scm/api/v2/users/test-user")))
	}

	@Test
	void 'does not allow self-signed certificates by default'() {
		wireMock.stubFor(delete(urlPathEqualTo("/scm/api/v2/users/test-user"))
			.willReturn(aResponse().withStatus(204)))

		def api = usersApi(false, true)
		// insecure=false, useHttps=true

		shouldFail(SSLHandshakeException) {
			api.delete('test-user').execute()
		}

		wireMock.verify(0, deleteRequestedFor(urlPathEqualTo("/scm/api/v2/users/test-user")))
	}

	private UsersApi usersApi(boolean insecure, boolean useHttps = false) {
		def client = new ScmManagerApiClient(apiBaseUrl(useHttps), credentials, insecure)
		return client.usersApi()
	}

	private String apiBaseUrl(boolean useHttps) {
		if (useHttps) {
			// Use the proper HTTPS port from WireMock
			def httpsPort = wireMock.httpsPort
			return "https://localhost:${httpsPort}/scm/api/"
		} else {
			return "${wireMock.baseUrl()}/scm/api/"
		}
	}
}