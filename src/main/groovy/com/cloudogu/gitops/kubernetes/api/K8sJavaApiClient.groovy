package com.cloudogu.gitops.kubernetes.api

import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder

import com.cloudogu.gitops.config.Credentials

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

    Service createNodePortService(
            String namespace,
            String serviceName,
            Map<String, String> selector,
            Integer port,
            Integer nodePort,
            String portName = 'custom-port'
    ) {

        def service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceName)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withType("NodePort")
                    .addToSelector(selector)
                    .addNewPort()
                        .withName(portName)
                        .withPort(port)
                        .withTargetPort(new IntOrString(port))
                        .withNodePort(nodePort)
                .endPort()
                .endSpec()
                .build()

        client.services()
                .inNamespace(namespace)
                .resource(service)
                .create()
    }

    /**
     * Gets login credentials from a K8s secret
     */
    Credentials getCredentialsFromSecret(Credentials credentials) {
        try {
            Secret secret = this.client.secrets()
                    .inNamespace(credentials.secretNamespace)
                    .withName(credentials.secretName)
                    .get()

            def secretData = secret.getData()
            def usernameEncoded = secretData[credentials.usernameKey]
            String username = usernameEncoded != null
                    ? new String(Base64.decoder.decode(usernameEncoded))
                    : credentials.username
            String password = new String(Base64.getDecoder().decode(secretData[credentials.passwordKey]))
            Credentials credentialsNew = new Credentials(credentials)
            credentialsNew.username = username
            credentialsNew.password = password

            return credentialsNew
        } catch (Exception e) {
            throw new RuntimeException("Couldn't parse credentials from K8s secret: ${credentials.secretName} in namespace ${credentials.secretNamespace}", e)
        }
    }
}