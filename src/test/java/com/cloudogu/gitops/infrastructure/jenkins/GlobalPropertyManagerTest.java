package com.cloudogu.gitops.infrastructure.jenkins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalPropertyManagerTest {

	@Test
	void setsGlobalProperty() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		GlobalPropertyManager propertyManager = new GlobalPropertyManager(client);

		when(client.runScript(anyString())).thenReturn("Done");
		propertyManager.setGlobalProperty("the-key", "the-value");

		verify(client).runScript("""
			instance = Jenkins.getInstance()
			globalNodeProperties = instance.getGlobalNodeProperties()
			envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)

			def newEnvVarsNodeProperty
			def envVars

			if ( envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0 ) {
				newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty()
				globalNodeProperties.add(newEnvVarsNodeProperty)
				envVars = newEnvVarsNodeProperty.getEnvVars()
			} else {
				envVars = envVarsNodePropertyList.get(0).getEnvVars()

			}

			envVars.put('the-key', 'the-value')

			instance.save()
			print("Done")
			""");
	}

	@Test
	void throwsWhenThereWasAnErrorWhenCreatingGlobalProperty() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString()))
			.thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]");

		assertThrows(RuntimeException.class,
			() -> new GlobalPropertyManager(client).setGlobalProperty("the-key", "the-value"));
	}

	@Test
	void deletesGlobalProperty() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		GlobalPropertyManager propertyManager = new GlobalPropertyManager(client);

		when(client.runScript(anyString())).thenReturn("Nothing to do");
		propertyManager.deleteGlobalProperty("the-key");

		verify(client).runScript("""
			def instance = Jenkins.getInstance()
			def globalNodeProperties = instance.getGlobalNodeProperties()
			def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)

			if (envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0) {
				print("Nothing to do")
				return
			}

			envVars = envVarsNodePropertyList.get(0).getEnvVars()
			envVars.remove('the-key')
			print("Done")
			""");
	}

	@Test
	void throwsWhenThereWasAnErrorWhenDeletingGlobalProperty() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString()))
			.thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]");

		assertThrows(RuntimeException.class,
			() -> new GlobalPropertyManager(client).deleteGlobalProperty("the-key"));
	}

	@Test
	void escapesSingleQuotesInKeyAndValueToAvoidBreakingOutOfTheGroovyScript() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);
		when(client.runScript(anyString())).thenReturn("Done");

		new GlobalPropertyManager(client).setGlobalProperty("the'key", "the'value");

		verify(client).runScript(contains("envVars.put('the\\'key', 'the\\'value')"));
	}

	@Test
	void rejectsValuesContainingBackslashes() {
		JenkinsApiClient client = mock(JenkinsApiClient.class);

		assertThrows(IllegalArgumentException.class,
			() -> new GlobalPropertyManager(client).setGlobalProperty("the-key", "the\\value"));
	}
}
