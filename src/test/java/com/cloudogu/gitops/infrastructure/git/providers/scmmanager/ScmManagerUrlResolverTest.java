package com.cloudogu.gitops.infrastructure.git.providers.scmmanager;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.scm.ScmTenantSchema;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.utils.NetworkingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScmManagerUrlResolverTest {

	private Config config;

	@Mock
	private K8sClient k8s;

	@Mock
	private NetworkingUtils net;

	@BeforeEach
	void setUp() {
		config = new Config();
		Config.ApplicationSchema application = new Config.ApplicationSchema();
		application.setNamePrefix("fv40-");
		application.setRunningInsideK8s(false);
		config.setApplication(application);
	}

	private ScmManagerUrlResolver resolverWith() {
		return resolverWith(Map.of(), "fv40-");
	}

	private ScmManagerUrlResolver resolverWith(Map<String, Object> args) {
		return resolverWith(args, "fv40-");
	}

	private ScmManagerUrlResolver resolverWith(Map<String, Object> args, String servicePrefix) {
		ScmTenantSchema.ScmManagerTenantConfig scmmConfig = new ScmTenantSchema.ScmManagerTenantConfig();
		scmmConfig.setInternal(args.containsKey("internal") ? (Boolean) args.get("internal") : true);
		scmmConfig.setNamespace(args.containsKey("namespace") ? (String) args.get("namespace") : "scm-manager");
		scmmConfig.setUrl(args.containsKey("url") ? (String) args.get("url") : "");
		scmmConfig.setIngress(args.containsKey("ingress") ? (String) args.get("ingress") : "");

		return new ScmManagerUrlResolver(
			scmmConfig,
			k8s,
			net,
			config.getApplication().getNamePrefix(),
			config.getApplication().getRunningInsideK8s(),
			servicePrefix
		);
	}

	@Test
	void clientBaseTenantInternalOutsideK8sUsesPrefixedNodePortLookupAndAppendsScmOnlyOnce() {
		when(k8s.waitForNodePort("fv40-scmm", "fv40-scm-manager")).thenReturn("30080");
		when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

		ScmManagerUrlResolver resolver = resolverWith();
		var base1 = resolver.clientBase();
		var base2 = resolver.clientBase();

		assertEquals("http://10.0.0.1:30080/scm", base1.toString());
		assertEquals(base1, base2);

		verify(k8s, times(1)).waitForNodePort("fv40-scmm", "fv40-scm-manager");
		verify(net, times(1)).findClusterBindAddress();
		verifyNoMoreInteractions(k8s, net);
	}

	@Test
	void clientBaseCentralInternalOutsideK8sKeepsUnprefixedServiceNameAndNamespace() {
		when(k8s.waitForNodePort("scmm", "scm-manager")).thenReturn("30080");
		when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

		ScmManagerUrlResolver resolver = resolverWith(Map.of(), "");

		assertEquals("http://10.0.0.1:30080/scm", resolver.clientBase().toString());

		verify(k8s).waitForNodePort("scmm", "scm-manager");
		verify(net).findClusterBindAddress();
		verifyNoMoreInteractions(k8s, net);
	}

	@Test
	void clientApiBaseAppendsApiToClientBase() {
		when(k8s.waitForNodePort("fv40-scmm", "fv40-scm-manager")).thenReturn("30080");
		when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

		ScmManagerUrlResolver resolver = resolverWith();

		assertEquals("http://10.0.0.1:30080/scm/api/", resolver.clientApiBase().toString());
	}

	@Test
	void clientRepoUrlTrimsRepoTargetAndRemovesTrailingSlash() {
		when(k8s.waitForNodePort("fv40-scmm", "fv40-scm-manager")).thenReturn("30080");
		when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

		ScmManagerUrlResolver resolver = resolverWith();

		assertEquals(
			"http://10.0.0.1:30080/scm/repo/ns/project",
			resolver.clientRepoUrl("  ns/project  ")
		);
	}

	@Test
	void inClusterBaseTenantInternalUsesPrefixedServiceDns() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith();

		assertEquals(
			"http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm",
			resolver.inClusterBase().toString()
		);
	}

	@Test
	void inClusterBaseTenantInternalPrefixesCustomNamespaceWhenNeeded() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith(Map.of("namespace", "custom-ns"));

		assertEquals(
			"http://fv40-scmm.fv40-custom-ns.svc.cluster.local/scm",
			resolver.inClusterBase().toString()
		);
	}

	@Test
	void inClusterBaseTenantInternalDoesNotDuplicateAlreadyPrefixedNamespace() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith(Map.of("namespace", "fv40-scm-manager"));

		assertEquals(
			"http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm",
			resolver.inClusterBase().toString()
		);
	}

	@Test
	void inClusterBaseCentralInternalUsesUnprefixedServiceDns() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith(Map.of(), "");

		assertEquals(
			"http://scmm.scm-manager.svc.cluster.local/scm",
			resolver.inClusterBase().toString()
		);
	}

	@Test
	void inClusterBaseExternalUsesExternalBaseAndScm() {
		ScmManagerUrlResolver resolver = resolverWith(Map.of(
			"internal", false,
			"url", "https://fv40-scmm.external"
		));

		assertEquals("https://fv40-scmm.external/scm", resolver.inClusterBase().toString());
	}

	@Test
	void inClusterRepoUrlBuildsFullTenantInClusterRepoUrlWithoutTrailingSlash() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith();

		assertEquals(
			"http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm/repo/admin/admin",
			resolver.inClusterRepoUrl("admin/admin")
		);
	}

	@Test
	void inClusterRepoPrefixTenantServiceUsesServicePrefixAndRepoNamespaceUsesApplicationNamePrefix() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith();

		assertEquals(
			"http://fv40-scmm.fv40-scm-manager.svc.cluster.local/scm/repo/fv40-",
			resolver.inClusterRepoPrefix()
		);
	}

	@Test
	void inClusterRepoPrefixCentralServiceStaysUnprefixedButRepoNamespaceStillUsesApplicationNamePrefix() {
		config.getApplication().setRunningInsideK8s(true);

		ScmManagerUrlResolver resolver = resolverWith(Map.of(), "");

		assertEquals(
			"http://scmm.scm-manager.svc.cluster.local/scm/repo/fv40-",
			resolver.inClusterRepoPrefix()
		);
	}

	@Test
	void inClusterRepoPrefixEmptyApplicationNamePrefixYieldsBaseRepoPath() {
		config.getApplication().setRunningInsideK8s(true);
		config.getApplication().setNamePrefix("   ");

		ScmManagerUrlResolver resolver = resolverWith(Map.of(), "");

		assertEquals(
			"http://scmm.scm-manager.svc.cluster.local/scm/repo/",
			resolver.inClusterRepoPrefix()
		);
	}

	@Test
	void externalBasePrefersUrlOverIngress() {
		ScmManagerUrlResolver resolver = resolverWith(Map.of(
			"internal", false,
			"url", "https://scmm.external",
			"ingress", "ingress.example.org"
		));

		assertEquals("https://scmm.external/scm", resolver.inClusterBase().toString());
	}

	@Test
	void externalBaseUsesIngressWhenUrlIsMissing() {
		Map<String, Object> args = new HashMap<>();
		args.put("internal", false);
		args.put("url", null);
		args.put("ingress", "ingress.example.org");
		ScmManagerUrlResolver resolver = resolverWith(args);

		assertEquals("http://ingress.example.org/scm", resolver.inClusterBase().toString());
	}

	@Test
	void externalBaseThrowsWhenNeitherUrlNorIngressIsSet() {
		Map<String, Object> args = new HashMap<>();
		args.put("internal", false);
		args.put("url", null);
		args.put("ingress", null);
		ScmManagerUrlResolver resolver = resolverWith(args);

		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class,
			resolver::inClusterBase
		);

		assertTrue(exception.getMessage().contains(
			"Either scmm.url or scmm.ingress must be set when internal=false"
		));
	}

	@Test
	void nodePortBaseTenantFallsBackToPrefixedDefaultNamespaceWhenNoneProvided() {
		when(k8s.waitForNodePort(eq("fv40-scmm"), eq("fv40-scm-manager"))).thenReturn("30080");
		when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

		Map<String, Object> args = new HashMap<>();
		args.put("namespace", null);
		ScmManagerUrlResolver resolver = resolverWith(args);

		assertEquals("http://10.0.0.1:30080/scm", resolver.clientBase().toString());
	}

	@Test
	void nodePortBaseCentralFallsBackToUnprefixedDefaultNamespaceWhenNoneProvided() {
		when(k8s.waitForNodePort(eq("scmm"), eq("scm-manager"))).thenReturn("30080");
		when(net.findClusterBindAddress()).thenReturn("10.0.0.1");

		Map<String, Object> args = new HashMap<>();
		args.put("namespace", null);
		ScmManagerUrlResolver resolver = resolverWith(args, "");

		assertEquals("http://10.0.0.1:30080/scm", resolver.clientBase().toString());
	}

	@Test
	void ensureScmAddsScmIfMissingAndKeepsItIfPresent() {
		ScmManagerUrlResolver resolverWithoutScm = resolverWith(Map.of(
			"internal", false,
			"url", "https://fv40-scmm.localhost"
		));
		assertEquals("https://fv40-scmm.localhost/scm", resolverWithoutScm.clientBase().toString());

		ScmManagerUrlResolver resolverWithScm = resolverWith(Map.of(
			"internal", false,
			"url", "https://fv40-scmm.localhost/scm"
		));
		assertEquals("https://fv40-scmm.localhost/scm", resolverWithScm.clientBase().toString());
	}

	@Test
	void prometheusEndpointResolves() {
		ScmManagerUrlResolver resolver = resolverWith(Map.of(
			"internal", false,
			"url", "https://fv40-scmm.localhost"
		));

		assertEquals(
			"https://fv40-scmm.localhost/scm/api/v2/metrics/prometheus",
			resolver.prometheusEndpoint().toString()
		);
	}
}
