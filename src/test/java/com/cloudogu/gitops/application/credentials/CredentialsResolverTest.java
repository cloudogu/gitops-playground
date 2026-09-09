package com.cloudogu.gitops.application.credentials;

import com.cloudogu.gitops.config.Credentials;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CredentialsResolverTest {

	private final K8sClient k8sClient = mock(K8sClient.class);
	private final CredentialsResolver resolver = new CredentialsResolver(k8sClient);

	@Test
	void returnsFallbackCredentialsWithoutSecretReference() {
		ResolvedCredentials resolved = resolver.resolve(null, "plain-user", "plain-password");

		assertThat(resolved.username()).isEqualTo("plain-user");
		assertThat(resolved.password()).isEqualTo("plain-password");
		verifyNoInteractions(k8sClient);
	}

	@Test
	void returnsFallbackCredentialsForEmptySecretReference() {
		ResolvedCredentials resolved = resolver.resolve(new Credentials(), "plain-user", "plain-password");

		assertThat(resolved.username()).isEqualTo("plain-user");
		assertThat(resolved.password()).isEqualTo("plain-password");
		verifyNoInteractions(k8sClient);
	}

	@Test
	void resolvesCredentialsFromSecretWithoutMutatingReference() {
		Credentials reference = secretReference();
		when(k8sClient.getCredentialsFromSecret(any(Credentials.class)))
			.thenReturn(new Credentials("secret-user", "secret-password"));

		ResolvedCredentials resolved = resolver.resolve(reference, "plain-user", "plain-password");

		assertThat(resolved.username()).isEqualTo("secret-user");
		assertThat(resolved.password()).isEqualTo("secret-password");
		assertThat(reference.getUsername()).isNull();
		assertThat(reference.getPassword()).isNull();

		ArgumentCaptor<Credentials> captor = ArgumentCaptor.forClass(Credentials.class);
		verify(k8sClient).getCredentialsFromSecret(captor.capture());
		Credentials effectiveReference = captor.getValue();
		assertThat(effectiveReference).isNotSameAs(reference);
		assertThat(effectiveReference.getUsername()).isEqualTo("plain-user");
		assertThat(effectiveReference.getPassword()).isNull();
		assertThat(effectiveReference.getSecretName()).isEqualTo("tool-credentials");
		assertThat(effectiveReference.getSecretNamespace()).isEqualTo("gop-job");
		assertThat(effectiveReference.getUsernameKey()).isEqualTo("custom-user");
		assertThat(effectiveReference.getPasswordKey()).isEqualTo("custom-password");
	}

	@Test
	void resolvesImmutableSecretReference() {
		CredentialsReference reference = new CredentialsReference(
			"tool-credentials",
			"gop-job",
			"custom-user",
			"custom-password"
		);
		when(k8sClient.getCredentialsFromSecret(any(Credentials.class)))
			.thenReturn(new Credentials("secret-user", "secret-password"));

		ResolvedCredentials resolved = resolver.resolveReference(reference, "plain-user", "plain-password");

		assertThat(resolved.username()).isEqualTo("secret-user");
		assertThat(resolved.password()).isEqualTo("secret-password");
	}

	@Test
	void usesFallbackUsernameWhenSecretDoesNotProvideOne() {
		Credentials reference = secretReference();
		when(k8sClient.getCredentialsFromSecret(any(Credentials.class)))
			.thenAnswer(invocation -> {
				Credentials effectiveReference = invocation.getArgument(0);
				return new Credentials(effectiveReference.getUsername(), "secret-password");
			});

		ResolvedCredentials resolved = resolver.resolve(reference, "oauth2.0", "plain-password");

		assertThat(resolved.username()).isEqualTo("oauth2.0");
		assertThat(resolved.password()).isEqualTo("secret-password");
	}

	@Test
	void rejectsSecretReferenceWithoutNamespace() {
		Credentials reference = new Credentials();
		reference.setSecretName("tool-credentials");

		assertThatThrownBy(() -> resolver.resolve(reference, "plain-user", "plain-password"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Kubernetes Secret credentials require both secretName and secretNamespace");
		verifyNoInteractions(k8sClient);
	}

	@Test
	void rejectsSecretReferenceWithoutName() {
		Credentials reference = new Credentials();
		reference.setSecretNamespace("gop-job");

		assertThatThrownBy(() -> resolver.resolve(reference, "plain-user", "plain-password"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Kubernetes Secret credentials require both secretName and secretNamespace");
		verifyNoInteractions(k8sClient);
	}

	@Test
	void doesNotExposePasswordInToString() {
		ResolvedCredentials resolved = new ResolvedCredentials("user", "do-not-log-me");

		assertThat(resolved.toString())
			.contains("user", "<redacted>")
			.doesNotContain("do-not-log-me");
	}

	private static Credentials secretReference() {
		Credentials reference = new Credentials();
		reference.setSecretName("tool-credentials");
		reference.setSecretNamespace("gop-job");
		reference.setUsernameKey("custom-user");
		reference.setPasswordKey("custom-password");
		return reference;
	}
}
