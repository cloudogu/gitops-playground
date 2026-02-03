package com.cloudogu.gitops.kubernetes.api

import com.cloudogu.gitops.config.Credentials
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder

class K8sJavaApiClient {

    KubernetesClient client

    K8sJavaApiClient(){
        this.client = new KubernetesClientBuilder().build()
    }

    /**
     * Gets login credentials from a K8s secret
     */
    Credentials getCredentialsFromSecret(String secretname, String namespace, String usernameKey='username', String passwordKey='password') {
        try {
            Secret secret = this.client.secrets()
                    .inNamespace(namespace)
                    .withName(secretname)
                    .get()

            def secretData = secret.getData()
            String username = new String(Base64.getDecoder().decode(secretData[usernameKey]))
            String password = new String(Base64.getDecoder().decode(secretData[passwordKey]))
            return new Credentials(username, password)
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse credentials from K8s secret: ${secretname} in namespace ${namespace}", e)
        }
    }
}