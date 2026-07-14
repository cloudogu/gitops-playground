package com.cloudogu.gitops.tools.common;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the registry image pull secret for tools that deploy workloads into Kubernetes.
 *
 * <p>The creator is intentionally not part of the Tool base class. Tools call it explicitly
 * in their setup flow when an image pull secret is relevant for their namespace.</p>
 */
@Singleton
public class ImagePullSecretCreator {

    private static final Logger log = LoggerFactory.getLogger(ImagePullSecretCreator.class);
    private static final String IMAGE_PULL_SECRET_NAME = "proxy-registry";

    private final K8sClient k8sClient;

    public ImagePullSecretCreator(K8sClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    public void createIfRequired(Config config, String namespace) {
        if (!config.getRegistry().getCreateImagePullSecrets()) {
            return;
        }

        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("Namespace must be set before creating an image pull secret.");
        }

        log.trace("Creating image pull secret '{}' in namespace {}", IMAGE_PULL_SECRET_NAME, namespace);

        String url = (config.getRegistry().getProxyUrl() != null && !config.getRegistry().getProxyUrl().isEmpty())
                ? config.getRegistry().getProxyUrl()
                : config.getRegistry().getUrl();

        String user = (config.getRegistry().getProxyUsername() != null && !config.getRegistry().getProxyUsername().isEmpty())
                ? config.getRegistry().getProxyUsername()
                : (config.getRegistry().getReadOnlyUsername() != null && !config.getRegistry().getReadOnlyUsername().isEmpty())
                    ? config.getRegistry().getReadOnlyUsername()
                    : config.getRegistry().getUsername();

        String password = (config.getRegistry().getProxyPassword() != null && !config.getRegistry().getProxyPassword().isEmpty())
                ? config.getRegistry().getProxyPassword()
                : (config.getRegistry().getReadOnlyPassword() != null && !config.getRegistry().getReadOnlyPassword().isEmpty())
                    ? config.getRegistry().getReadOnlyPassword()
                    : config.getRegistry().getPassword();

        k8sClient.createNamespace(namespace);
        k8sClient.createImagePullSecret(IMAGE_PULL_SECRET_NAME, namespace, url, user, password);
    }
}
