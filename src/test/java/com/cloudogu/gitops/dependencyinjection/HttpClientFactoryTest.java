package com.cloudogu.gitops.dependencyinjection;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.Credentials;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpClientFactoryTest {

	@RegisterExtension
	static final WireMockExtension wireMock = WireMockExtension.newInstance()
															   .options(wireMockConfig().dynamicPort().dynamicHttpsPort())
															   .build();

	private final Config config = new Config();

	@Test
	void sendsBasicAuthorizationHeader() throws IOException {
		wireMock.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200)));
		OkHttpClient client = HttpClientFactory.buildOkHttpClient(new Credentials("user", "pass"), false);

		try (Response response = client.newCall(request(httpUrl("/ping"))).execute()) {
			assertThat(response.isSuccessful()).isTrue();
		}

		wireMock.verify(1, getRequestedFor(urlEqualTo("/ping")).withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz")));
	}

	@Test
	void rejectsSelfSignedCertificatesByDefault() {
		wireMock.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200)));
		OkHttpClient client = HttpClientFactory.buildOkHttpClient(new Credentials("user", "pass"), false);

		assertThrows(SSLHandshakeException.class, () -> client.newCall(request(httpsUrl("/ping"))).execute());
	}

	@Test
	void acceptsSelfSignedCertificatesWhenInsecure() throws IOException {
		wireMock.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200)));
		OkHttpClient client = HttpClientFactory.buildOkHttpClient(new Credentials("user", "pass"), true);

		try (Response response = client.newCall(request(httpsUrl("/ping"))).execute()) {
			assertThat(response.isSuccessful()).isTrue();
		}
	}

	@Test
	void jenkinsClientKeepsCookiesBetweenRequests() throws IOException {
		wireMock.stubFor(get(urlEqualTo("/login")).willReturn(aResponse().withHeader("Set-Cookie", "JSESSIONID=abc; Path=/")));
		wireMock.stubFor(get(urlEqualTo("/next")).willReturn(aResponse().withStatus(200)));
		OkHttpClient client = new HttpClientFactory().okHttpClientJenkins(config);

		client.newCall(request(httpUrl("/login"))).execute().close();
		client.newCall(request(httpUrl("/next"))).execute().close();

		wireMock.verify(1, getRequestedFor(urlEqualTo("/next")).withHeader("Cookie", containing("JSESSIONID=abc")));
	}

	@Test
	void jenkinsClientRejectsSelfSignedCertificatesByDefault() {
		wireMock.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200)));
		OkHttpClient client = new HttpClientFactory().okHttpClientJenkins(config);

		assertThrows(SSLHandshakeException.class, () -> client.newCall(request(httpsUrl("/ping"))).execute());
	}

	@Test
	void jenkinsClientAcceptsSelfSignedCertificatesWhenInsecure() throws IOException {
		wireMock.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200)));
		config.getApplication().setInsecure(true);
		OkHttpClient client = new HttpClientFactory().okHttpClientJenkins(config);

		try (Response response = client.newCall(request(httpsUrl("/ping"))).execute()) {
			assertThat(response.isSuccessful()).isTrue();
		}
	}

	@Test
	void loggingInterceptorLogsHeadersOnly() {
		HttpLoggingInterceptor interceptor = HttpClientFactory.createLoggingInterceptor();

		assertThat(interceptor.getLevel()).isEqualTo(HttpLoggingInterceptor.Level.HEADERS);
	}

	@Test
	void insecureSslContextTrustsAnyCertificate() throws Exception {
		HttpClientFactory.InsecureSslContext context = HttpClientFactory.insecureSslContext();

		context.getTrustManager().checkServerTrusted(null, "RSA");

		assertThat(context.getTrustManager().getAcceptedIssuers()).isEmpty();
		assertThat(context.getSocketFactory()).isNotNull();
	}

	private static Request request(String url) {
		return new Request.Builder().url(url).build();
	}

	private static String httpUrl(String path) {
		return "http://localhost:" + wireMock.getRuntimeInfo().getHttpPort() + path;
	}

	private static String httpsUrl(String path) {
		return "https://localhost:" + wireMock.getRuntimeInfo().getHttpsPort() + path;
	}
}
