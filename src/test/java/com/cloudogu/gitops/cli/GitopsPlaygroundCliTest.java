package com.cloudogu.gitops.cli;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import com.cloudogu.gitops.application.Application;
import com.cloudogu.gitops.application.content.ContentLoader;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.destroy.Destroyer;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.AbstractTool;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GitopsPlaygroundCliTest {

	private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
	private static final String ORIGINAL_LOGGING_PATTERN = getLoggingEncoder().getPattern();

	private final K8sClient k8sClient = mock(K8sClient.class);
	private final Application application = mock(Application.class);
	private final ApplicationConfigurator applicationConfigurator = mock(ApplicationConfigurator.class);
	private final Destroyer destroyer = mock(Destroyer.class);
	private final GitopsPlaygroundCliForTest cli = new GitopsPlaygroundCliForTest();

	@AfterEach
	void setup() {
		// Restore logging pattern, if modified
		getLoggingEncoder().setPattern(ORIGINAL_LOGGING_PATTERN);
	}

	@Test
	void startsRegularly() {
		ReturnCode status = cli.run(new String[]{"--yes"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		verify(applicationConfigurator).initConfig(any(Config.class));
		verify(application).start();
	}

	@Test
	void runsConfigLifecycleHooksOnlyForParticipatingTools() {
		AbstractTool regularTool = mock(AbstractTool.class);
		ContentLoader configLifecycleHook = mock(ContentLoader.class);
		when(application.getTools()).thenReturn(List.of(regularTool, configLifecycleHook));

		ReturnCode status = cli.run(new String[]{"--yes"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		verify(configLifecycleHook).preConfigInit(any(Config.class));
		verify(configLifecycleHook).postConfigInit(any(Config.class));
		verifyNoInteractions(regularTool);
	}

	@Test
	void startsWithConfigFile() {
		String pathToConfigFile = "./src/test/resources/testMainConfig.yaml";

		assertThat(new File(pathToConfigFile).isFile())
			.withFailMessage("config file for test do not exists anymore.")
			.isTrue();

		ReturnCode status = cli.run(new String[]{"--config-file=" + pathToConfigFile});
		assertThat(status).isEqualTo(ReturnCode.SUCCESS);

		verify(applicationConfigurator).initConfig(any(Config.class));
		verify(application).start();
	}

	@Test
	void startsWithConfigMap() {
		when(k8sClient.getConfigMap("my-config", "config.yaml"))
			.thenReturn("{\"application\": {\"yes\": true}}");

		ReturnCode status = cli.run(new String[]{"--config-map=my-config"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		verify(applicationConfigurator).initConfig(any(Config.class));
		verify(application).start();
	}

	@Test
	void startsWithDocumentedKeycloakOidcProfile() {
		ReturnCode status = cli.run(new String[]{"--profile=keycloak"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		assertThat(cli.lastSchema.getFeatures().getArgocd().getOidc().isEnabled()).isTrue();
		assertThat(cli.lastSchema.getFeatures().getArgocd().getOidc().getClientId()).isEqualTo("argocd");
		assertThat(cli.lastSchema.getFeatures().getMonitoring().getOidc().isEnabled()).isTrue();
		assertThat(cli.lastSchema.getFeatures().getMonitoring().getOidc().getClientId()).isEqualTo("grafana");
		assertThat(cli.lastSchema.getFeatures().getSecrets().getVault().getOidc().isEnabled()).isTrue();
		assertThat(cli.lastSchema.getFeatures().getSecrets().getVault().getOidc().getClientId()).isEqualTo("vault");
		assertThat(cli.lastSchema.getJenkins().getOidc().isEnabled()).isTrue();
		assertThat(cli.lastSchema.getJenkins().getOidc().getClientId()).isEqualTo("jenkins");
	}

	@Test
	void outputsConfigFile() {
		ReturnCode status = cli.run(new String[]{"--output-config-file"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		verify(applicationConfigurator, never()).initConfig(any(Config.class));
		verify(application, never()).start();
	}

	@Test
	void outputsVersion() {
		GitopsPlaygroundCliForTest localCli = new GitopsPlaygroundCliForTest();
		ReturnCode status = localCli.run(new String[]{"--version"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		verify(applicationConfigurator, never()).initConfig(any(Config.class));
		verify(application, never()).start();
	}

	@Test
	void outputsHelp() {
		GitopsPlaygroundCliForTest localCli = new GitopsPlaygroundCliForTest();
		ReturnCode status = localCli.run(new String[]{"--help"});

		assertThat(status).isEqualTo(ReturnCode.SUCCESS);
		verify(applicationConfigurator, never()).initConfig(any(Config.class));
		verify(application, never()).start();
	}

	@Test
	void returnsErrorWhenApplyingIsNotConfirmed() {
		writeViaSystemIn("something");
		ReturnCode status = cli.run(new String[]{});

		assertThat(status).isEqualTo(ReturnCode.NOT_CONFIRMED);
	}

	@Test
	void runsWhenApplyingIsConfirmed() {
		writeViaSystemIn("y");

		cli.run(new String[]{});

		verify(application).start();
	}

	@Test
	void runsWithoutConfirmationWhenYesParameterIsSet() {
		cli.run(new String[]{"--yes"});

		verify(application).start();
	}

	@Test
	void returnsErrorWhenDestroyingIsNotConfirmed() {
		writeViaSystemIn("something");

		ReturnCode status = cli.run(new String[]{"--destroy"});

		assertThat(status).isEqualTo(ReturnCode.NOT_CONFIRMED);
	}

	@Test
	void destroysWhenConfirmed() {
		writeViaSystemIn("y");

		cli.run(new String[]{"--destroy"});

		verify(destroyer).destroy();
		verify(application, never()).start();
	}

	@Test
	void destroysWithoutConfirmationWhenYesParameterIsSet() {
		cli.run(new String[]{"--destroy", "--yes"});

		verify(destroyer).destroy();
	}

	@Test
	void setsSimplifiedLoggingPattern() {
		cli.run(new String[]{"--yes"});

		assertThat(getLoggingPattern()).doesNotContain("%logger", "%thread");
	}

	@Test
	void keepsSimplifiedLoggingPatternWhenTraceIsEnabled() {
		cli.run(new String[]{"--trace", "--yes"});

		assertThat(getLoggingPattern()).contains("%logger", "%thread");
	}

	@Test
	void keepsSimplifiedLoggingPatternWhenDebugIsEnabled() {
		cli.run(new String[]{"--debug", "--yes"});

		assertThat(getLoggingPattern()).contains("%logger", "%thread");
	}

	@Test
	void failsOnInvalidConfigFile() throws IOException {
		File configFile = File.createTempFile("gop", ".yaml");
		configFile.deleteOnExit();
		java.nio.file.Files.writeString(configFile.toPath(), "something: not-matching-our-schema");

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> cli.run(new String[]{"--config-file=" + configFile, "--yes"})
		);
		assertThat(exception.getMessage()).contains("Config file invalid");
	}

	@Test
	void failsOnInvalidConfigMap() {
		when(k8sClient.getConfigMap("my-config", "config.yaml"))
			.thenReturn("something: not-matching-our-schema");

		RuntimeException exception = assertThrows(
			RuntimeException.class,
			() -> cli.run(new String[]{"--config-map=my-config", "--yes"})
		);
		assertThat(exception.getMessage()).contains("Config file invalid");
	}

	@Test
	void precedenceConfigFileOverwritesConfigMapAndCliOverwritesConfigFile() throws IOException {
		Map<String, Object> cmConfig = Map.of(
			"application", Map.of("username", "cmUser", "password", "cmPw", "namePrefix", "cmPref")
		);
		Map<String, Object> fileConfig = Map.of(
			"application", Map.of("username", "fileUser", "password", "filePw")
		);

		File configFile = File.createTempFile("gop", ".yaml");
		configFile.deleteOnExit();

		java.nio.file.Files.writeString(configFile.toPath(), toYaml(fileConfig));
		when(k8sClient.getConfigMap("my-config", "config.yaml")).thenReturn(toYaml(cmConfig));

		cli.run(new String[]{
			"--config-file=" + configFile,
			"--config-map=my-config",
			"--username=paramUser",
			"--yes"
		});

		assertThat(cli.lastSchema.getApplication().getUsername()).isEqualTo("paramUser");
		assertThat(cli.lastSchema.getApplication().getPassword()).isEqualTo("filePw");
		assertThat(cli.lastSchema.getApplication().getNamePrefix()).isEqualTo("cmPref");
	}

	@Test
	void helmNullValuesOverwrite() throws IOException {
		Map<String, Object> fileConfig = Map.of(
			"features", Map.of(
				"monitoring", Map.of(
					"helm", Map.of("repoURL", "https://prometheus-community.github.io/helm-chartsTEST")
				)
			)
		);

		File configFile = File.createTempFile("gop", ".yaml");
		configFile.deleteOnExit();

		java.nio.file.Files.writeString(configFile.toPath(), toYaml(fileConfig));

		cli.run(new String[]{"--config-file=" + configFile, "--yes"});

		assertThat(cli.lastSchema.getFeatures().getMonitoring().getHelm().getChart())
			.isEqualTo("kube-prometheus-stack");
		assertThat(cli.lastSchema.getFeatures().getMonitoring().getHelm().getRepoURL())
			.isEqualTo("https://prometheus-community.github.io/helm-chartsTEST");
		assertThat(cli.lastSchema.getFeatures().getMonitoring().getHelm().getVersion()).isEqualTo("80.2.2");
	}

	@Test
	void ensureHelmDefaultsAreUsedIfNotSet() throws IOException {
		Map<String, Object> fileConfig = Map.of(
			"jenkins", Map.of("helm", Map.of("version", "5.8.1")),
			"scm", Map.of(
				"scmManager", Map.of(
					"helm", Map.of(
						"values", Map.of("initialDelaySeconds", 120)
					)
				)
			),
			"features", Map.of(
				"monitoring", Map.of(
					"helm", Map.of(
						"version", "66.2.1",
						"grafanaImage", "localhost:30000/proxy/grafana:latest"
					)
				),
				"secrets", Map.of(
					"externalSecrets", Map.of("helm", Map.of("chart", "my-secrets")),
					"vault", Map.of("helm", Map.of("repoURL", "localhost:3000/proxy/vault:latest"))
				),
				"certManager", Map.of(
					"helm", Map.of("image", "localhost:30000/proxy/cert-manager-controller:latest")
				)
			)
		);

		File configFile = File.createTempFile("gop", ".yaml");
		configFile.deleteOnExit();

		java.nio.file.Files.writeString(configFile.toPath(), toYaml(fileConfig));

		cli.run(new String[]{"--config-file=" + configFile, "--yes"});
		Config myConfig = cli.lastSchema;
		assertThat(myConfig.getJenkins().getHelm().getChart()).isEqualTo("jenkins");
		assertThat(myConfig.getJenkins().getHelm().getRepoURL()).isEqualTo("https://charts.jenkins.io");
		assertThat(myConfig.getJenkins().getHelm().getVersion()).isEqualTo("5.8.1");

		assertThat(myConfig.getScm().getScmManager().getHelm().getChart()).isEqualTo("scm-manager");
		assertThat(myConfig.getScm().getScmManager().getHelm().getRepoURL())
			.isEqualTo("https://packages.scm-manager.org/repository/helm-v2-releases/");
		assertThat(myConfig.getScm().getScmManager().getHelm().getVersion()).isEqualTo("3.11.10");
		assertThat(myConfig.getScm().getScmManager().getHelm().getValues().get("initialDelaySeconds"))
			.isEqualTo(120);

		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getChart()).isEqualTo("kube-prometheus-stack");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getRepoURL())
			.isEqualTo("https://prometheus-community.github.io/helm-charts");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getVersion()).isEqualTo("66.2.1");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getGrafanaSidecarImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getPrometheusImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getPrometheusConfigReloaderImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getPrometheusOperatorImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getMonitoring().getHelm().getGrafanaImage())
			.isEqualTo("localhost:30000/proxy/grafana:latest");

		assertThat(myConfig.getFeatures().getSecrets().getExternalSecrets().getHelm().getChart()).isEqualTo("my-secrets");
		assertThat(myConfig.getFeatures().getSecrets().getExternalSecrets().getHelm().getRepoURL())
			.isEqualTo("https://charts.external-secrets.io");
		assertThat(myConfig.getFeatures().getSecrets().getExternalSecrets().getHelm().getVersion()).isEqualTo("0.9.16");

		assertThat(myConfig.getFeatures().getSecrets().getVault().getHelm().getChart()).isEqualTo("vault");
		assertThat(myConfig.getFeatures().getSecrets().getVault().getHelm().getRepoURL())
			.isEqualTo("localhost:3000/proxy/vault:latest");
		assertThat(myConfig.getFeatures().getSecrets().getVault().getHelm().getVersion()).isEqualTo("0.25.0");

		assertThat(myConfig.getFeatures().getCertManager().getHelm().getChart()).isEqualTo("cert-manager");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getRepoURL()).isEqualTo("https://charts.jetstack.io");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getVersion()).isEqualTo("1.19.4");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getStartupAPICheckImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getWebhookImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getCainjectorImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getAcmeSolverImage()).isEqualTo("");
		assertThat(myConfig.getFeatures().getCertManager().getHelm().getImage())
			.isEqualTo("localhost:30000/proxy/cert-manager-controller:latest");
	}

	private static String getLoggingPattern() {
		return getLoggingEncoder().getPattern();
	}

	private static PatternLayoutEncoder getLoggingEncoder() {
		LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
		Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
		ConsoleAppender<?> consoleAppender = (ConsoleAppender<?>) rootLogger.getAppender("STDOUT");
		return (PatternLayoutEncoder) consoleAppender.getEncoder();
	}

	private void writeViaSystemIn(String value) {
		ByteArrayInputStream inContent = new ByteArrayInputStream((value + "\n").getBytes(StandardCharsets.UTF_8));
		System.setIn(inContent);
	}

	private static String toYaml(Map<String, Object> map) throws IOException {
		return YAML_MAPPER.writeValueAsString(map);
	}

	class GitopsPlaygroundCliForTest extends GitopsPlaygroundCli {
		private final ApplicationContext applicationContext = mock(ApplicationContext.class);
		private Config lastSchema;

		GitopsPlaygroundCliForTest() {
			super(GitopsPlaygroundCliTest.this.k8sClient, GitopsPlaygroundCliTest.this.applicationConfigurator);

			when(applicationConfigurator.initConfig(any(Config.class))).thenAnswer(new Answer<Config>() {
				@Override
				public Config answer(InvocationOnMock invocation) {
					lastSchema = invocation.getArgument(0);
					return lastSchema;
				}
			});
		}

		@Override
		protected ApplicationContext createApplicationContext() {
			when(applicationContext.getBean(Application.class)).thenReturn(application);
			when(applicationContext.getBean(Destroyer.class)).thenReturn(destroyer);

			return applicationContext;
		}
	}
}
