package com.cloudogu.gitops.destroy;

import com.cloudogu.gitops.config.Config;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DestroyerDependencyInjectionTest {

	@Test
	void canCreateBean() {
		Config config = Config.fromMap(Map.of(
			"scm", Map.of(
				"scmManager", Map.of(
					"url", "http://localhost:9091/scm",
					"username", "admin",
					"password", "admin"
				)
			),
			"jenkins", Map.of(
				"url", "http://localhost:9090",
				"username", "admin",
				"password", "admin"
			),
			"application", Map.of("insecure", true)
		));

		Destroyer destroyer = ApplicationContext.run()
			.registerSingleton(config)
			.getBean(Destroyer.class);

		assertThat(destroyer.getDestructionHandlers()).hasSize(3);
	}
}
