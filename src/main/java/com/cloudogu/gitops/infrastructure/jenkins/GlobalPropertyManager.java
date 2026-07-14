package com.cloudogu.gitops.infrastructure.jenkins;

import jakarta.inject.Singleton;

@Singleton
public class GlobalPropertyManager {

    private final JenkinsApiClient apiClient;

    public GlobalPropertyManager(JenkinsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void setGlobalProperty(String key, String value) {
        String script = "\n" +
                "            instance = Jenkins.getInstance()\n" +
                "            globalNodeProperties = instance.getGlobalNodeProperties()\n" +
                "            envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)\n" +
                "            \n" +
                "            def newEnvVarsNodeProperty\n" +
                "            def envVars\n" +
                "            \n" +
                "            if ( envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0 ) {\n" +
                "                newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty()\n" +
                "                globalNodeProperties.add(newEnvVarsNodeProperty)\n" +
                "                envVars = newEnvVarsNodeProperty.getEnvVars()\n" +
                "            } else {\n" +
                "                envVars = envVarsNodePropertyList.get(0).getEnvVars()\n" +
                "            \n" +
                "            }\n" +
                "            \n" +
                "            envVars.put(\"%KEY%\", \"%VALUE%\")\n" +
                "            \n" +
                "            instance.save()\n" +
                "            print(\"Done\")\n" +
                "        ";

        script = script.replace("%KEY%", key)
                .replace("%VALUE%", value);

        String result = apiClient.runScript(script);
        if (!"Done".equals(result)) {
            throw new RuntimeException("Could not create global property: " + result);
        }
    }

    public void deleteGlobalProperty(String key) {
        String script = "\n" +
                "            def instance = Jenkins.getInstance()\n" +
                "            def globalNodeProperties = instance.getGlobalNodeProperties()\n" +
                "            def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)\n" +
                "            \n" +
                "            if (envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0) {\n" +
                "                print(\"Nothing to do\")\n" +
                "                return\n" +
                "            }\n" +
                "            \n" +
                "            envVars = envVarsNodePropertyList.get(0).getEnvVars()            \n" +
                "            envVars.remove(\"%KEY%\")\n" +
                "            print(\"Done\")\n" +
                "        ";

        script = script.replace("%KEY%", key);

        String result = apiClient.runScript(script);
        if (!"Nothing to do".equals(result) && !"Done".equals(result)) {
            throw new RuntimeException("Could not delete global property: " + result);
        }
    }
}
