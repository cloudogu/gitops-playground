package com.cloudogu.gitops.infrastructure.jenkins;

import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

@Singleton
@RequiredArgsConstructor
public class GlobalPropertyManager {

	private final JenkinsApiClient apiClient;

	public void setGlobalProperty(String key, String value) {
		String script = """
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
				
				envVars.put('%KEY%', '%VALUE%')
				
				instance.save()
				print("Done") 
				""";

		script = script.replace("%KEY%", escapeString(key)).replace("%VALUE%", escapeString(value));

		String result = apiClient.runScript(script);
		if (!"Done".equals(result)) {
			throw new IllegalStateException("Could not create global property: " + result);
		}
	}

	public void deleteGlobalProperty(String key) {
		String script = """
				def instance = Jenkins.getInstance()
				def globalNodeProperties = instance.getGlobalNodeProperties()
				def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)
				
				if (envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0) {
					print("Nothing to do")
					return
				}
				
				envVars = envVarsNodePropertyList.get(0).getEnvVars()
				envVars.remove('%KEY%')
				print("Done")
				""";

		script = script.replace("%KEY%", escapeString(key));

		String result = apiClient.runScript(script);
		if (!"Nothing to do".equals(result) && !"Done".equals(result)) {
			throw new IllegalStateException("Could not delete global property: " + result);
		}
	}

	private static String escapeString(String str) {
		if (str.contains("\\")) {
			// We don't want to get in trouble with escaping,
			// e.g. `foo\'foo` => `foo\\'foo`. Now we would have a backslash followed by an unescaped
			// quote.
			throw new IllegalArgumentException("Backslashes within the escaped variables are forbidden.");
		}

		return str.replace("'", "\\'");
	}
}
