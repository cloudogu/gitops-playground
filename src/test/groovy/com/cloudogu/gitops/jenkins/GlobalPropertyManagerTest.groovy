package com.cloudogu.gitops.jenkins


import org.junit.jupiter.api.Test

import static groovy.test.GroovyAssert.shouldFail
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class GlobalPropertyManagerTest {
    @Test
    void 'sets global property'() {
        def client = mock(ApiClient)
        def propertyManager = new GlobalPropertyManager(client)

        when(client.runScript(anyString())).thenReturn("Done")
        propertyManager.setGlobalProperty('the-key', 'the-value')

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
            
            envVars.put("the-key", "the-value")
            
            instance.save()
            print("Done")
        """)
    }

    @Test
    void 'throws when there was an error when creating global property'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]")

        shouldFail(RuntimeException) {
            new GlobalPropertyManager(client).setGlobalProperty("the-key", "the-value")
        }
    }

    @Test
    void 'deletes global property'() {
        def client = mock(ApiClient)
        def propertyManager = new GlobalPropertyManager(client)

        when(client.runScript(anyString())).thenReturn("Nothing to do")
        propertyManager.deleteGlobalProperty('the-key')

        verify(client).runScript("""
            def instance = Jenkins.getInstance()
            def globalNodeProperties = instance.getGlobalNodeProperties()
            def envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)
            
            if (envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0) {
                print("Nothing to do")
                return
            }
            
            envVars = envVarsNodePropertyList.get(0).getEnvVars()            
            envVars.remove("the-key")
            print("Done")
        """)
    }

    @Test
    void 'throws when there was an error when deleting global property'() {
        def client = mock(ApiClient)
        when(client.runScript(anyString())).thenReturn("groovy.lang.MissingPropertyException: No such property: asd for class: Script1[...]")

        shouldFail(RuntimeException) {
            new GlobalPropertyManager(client).deleteGlobalProperty("the-key")
        }
    }
}
