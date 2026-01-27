package com.cloudogu.gitops.utils

import com.cloudogu.gitops.config.Credentials
import com.cloudogu.gitops.kubernetes.api.K8sJavaApiClient
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test


@EnableKubernetesMockClient(crud = true)
class K8sJavaApiTest {

    //https://github.com/fabric8io/kubernetes-client?tab=readme-ov-file#mocking-kubernetes
    KubernetesClient client //Client to set mock data
    K8sJavaApiClient k8sJavaApiClient
    KubernetesMockServer server //Use server for non CRUD

    @BeforeEach
    void init(){
        k8sJavaApiClient = new K8sJavaApiClient()
        k8sJavaApiClient.client = client
    }

    @Test
    void 'getCredentialsFromSecret'() {
        generateSecret()
        Credentials credentials = k8sJavaApiClient.getCredentialsFromSecret('test-secret', 'test')
        assert (credentials.password) == 's3cr3t'
        assert (credentials.username) == 'admin'
    }

    private generateSecret() {

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName("test-secret")
                .withNamespace('test')
                .endMetadata()
                .withType("Opaque")
                .withData(Map.of(
                        "username", "YWRtaW4=",
                        "password", "czNjcjN0"
                ))
                .build()

        client.secrets()
                .inNamespace('test')
                .create(secret)

    }
}