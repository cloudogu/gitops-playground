package com.cloudogu.gitops.jenkins

import jakarta.inject.Singleton
import org.intellij.lang.annotations.Language

@Singleton
class GlobalPropertyManager {
    private ApiClient apiClient

    GlobalPropertyManager(ApiClient apiClient) {
        this.apiClient = apiClient
    }

    void setGlobalProperty(String key, String value) {
        @Language("groovy")
        def script = """
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
            
            envVars.put("$key", "$value")
            
            instance.save()
            print("Done")
        """

        def result = apiClient.runScript(script)
        if (result != 'Done') {
            throw new RuntimeException("Could not create global property: $result")
        }
    }

    void deleteGlobalProperty(String key) {
        @Language("groovy")
        def script = """
            def instance = Jenkins.getInstance()
            def globalNodeProperties = instance.getGlobalNodeProperties()
            def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)
            
            if (envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0) {
                print("Nothing to do")
                return
            }
            
            envVars = envVarsNodePropertyList.get(0).getEnvVars()            
            envVars.remove("$key")
            print("Done")
        """

        def result = apiClient.runScript(script)
        if (result != 'Nothing to do' && result != 'Done') {
            throw new RuntimeException("Could not delete global property: $result")
        }
    }
}
