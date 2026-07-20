package com.cloudogu.gitops.cli;

import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.utils.FileSystemUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class ApplicationConfigurator {

    private final FileSystemUtils fileSystemUtils;

    public ApplicationConfigurator() {
        this(new FileSystemUtils());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    /**
     * Sets dynamic fields and validates params
     */
    public Config initConfig(Config newConfig) {
        addAdditionalApplicationConfig(newConfig);
        addNamePrefix(newConfig);
        checkAndSetNamespaces(newConfig);
        addScmConfig(newConfig);
        addRegistryConfig(newConfig);
        addJenkinsConfig(newConfig);
        addFeatureConfig(newConfig);
        evaluateBaseUrl(newConfig);
        setResourceInclusionsCluster(newConfig);
        setMultiTenantModeConfig(newConfig);

        return newConfig;
    }

    private void addFeatureConfig(Config newConfig) {
        if (newConfig.getFeatures().getSecrets().getVault().getMode() != null) {
            newConfig.getFeatures().getSecrets().setActive(true);
        }

        if (hasText(newConfig.getFeatures().getMail().getSmtpAddress())) {
            newConfig.getFeatures().getMail().setActive(true);
        }

        if (Boolean.TRUE.equals(newConfig.getFeatures().getIngress().getActive()) &&
                !hasText(newConfig.getApplication().getBaseUrl())) {
            log.warn("Ingress-controller is activated without baseUrl parameter. Services will not be accessible by hostnames. To avoid this use baseUrl with ingress. ");
        }
    }

    private void addNamePrefix(Config newConfig) {
        String namePrefix = newConfig.getApplication().getNamePrefix();
        if (hasText(namePrefix)) {
            if (!namePrefix.endsWith("-")) {
                newConfig.getApplication().setNamePrefix(namePrefix + "-");
            }
            newConfig.getApplication().setNamePrefixForEnvVars(
                    newConfig.getApplication().getNamePrefix().toUpperCase().replace('-', '_')
            );
        }
    }

    private void addRegistryConfig(Config newConfig) {
        // Process image pull secrets first, they might even be relevant if no registry is set
        if (Boolean.TRUE.equals(newConfig.getRegistry().getCreateImagePullSecrets())) {
            String username = firstNonBlank(newConfig.getRegistry().getReadOnlyUsername(), newConfig.getRegistry().getUsername());
            String password = firstNonBlank(newConfig.getRegistry().getReadOnlyPassword(), newConfig.getRegistry().getPassword());

            if (!hasText(username) || !hasText(password)) {
                throw new RuntimeException("createImagePullSecrets needs to be used with either registry username and password or the readOnly variants");
            }
        }

        if (hasText(newConfig.getRegistry().getUrl())) {
            newConfig.getRegistry().setInternal(false);
            newConfig.getRegistry().setActive(true);
        } else if (Boolean.TRUE.equals(newConfig.getRegistry().getActive())) {
            /* Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on
               docker push in the example application's Jenkins Jobs.
               Both setting up HTTPS or allowing insecure registry via daemon.json makes the playground difficult to use.
               So, always use localhost.
               Allow overriding the port, in case multiple playground instance run on a single host in different
               k3d clusters. */
            newConfig.getRegistry().setInternal(true);
            newConfig.getRegistry().setUrl("localhost:" + newConfig.getRegistry().getInternalPort());
        } else {
            // Registry not active, no need to set the following values
            return;
        }

        if (hasText(newConfig.getRegistry().getProxyUrl())) {
            newConfig.getRegistry().setTwoRegistries(true);
            if (!hasText(newConfig.getRegistry().getProxyUsername()) || !hasText(newConfig.getRegistry().getProxyPassword())) {
                throw new RuntimeException("Proxy URL needs to be used with proxy-username and proxy-password");
            }
        }
    }

    private void addAdditionalApplicationConfig(Config newConfig) {
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) {
            log.debug("installation is running in kubernetes.");
            newConfig.getApplication().setRunningInsideK8s(true);
        }
    }

    private void addScmConfig(Config newConfig) {
        log.debug("Adding additional config for SCM");

        if (hasText(newConfig.getScm().getScmManager().getUrl())) {
            log.debug("Setting external scmm config");
            newConfig.getScm().getScmManager().setInternal(false);
            newConfig.getScm().getScmManager().setUrlForJenkins(newConfig.getScm().getScmManager().getUrl());
        } else {
            log.debug("Setting configs for internal SCM-Manager");
            newConfig.getScm().getScmManager().setInternal(true);
            // We use the K8s service as default name here, because it is the only option:
            // "scmm.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9091)
            // will not work on Windows and MacOS.
            newConfig.getScm().getScmManager().setUrlForJenkins("http://scmm." + newConfig.getApplication().getNamePrefix() + newConfig.getScm().getScmManager().getNamespace() + ".svc.cluster.local/scm");
        }

        // We probably could get rid of some of the complexity by refactoring url, host and ingress into a single var
        if (hasText(newConfig.getApplication().getBaseUrl())) {
            try {
                newConfig.getScm().getScmManager().setIngress(new URL(injectSubdomain("scmm",
                        newConfig.getApplication().getBaseUrl(), Boolean.TRUE.equals(newConfig.getApplication().getUrlSeparatorHyphen()))).getHost());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Failed to evaluate SCM ingress URL", e);
            }
        }

        // When specific user/pw are not set, set them to global values
        if (Config.DEFAULT_ADMIN_PW.equals(newConfig.getScm().getScmManager().getPassword())) {
            newConfig.getScm().getScmManager().setPassword(newConfig.getApplication().getPassword());
        }
        if (Config.DEFAULT_ADMIN_USER.equals(newConfig.getScm().getScmManager().getUsername())) {
            newConfig.getScm().getScmManager().setUsername(newConfig.getApplication().getUsername());
        }
    }

    private void addJenkinsConfig(Config newConfig) {
        log.debug("Adding additional config for Jenkins");
        if (hasText(newConfig.getJenkins().getUrl())) {
            log.debug("Setting external jenkins config");
            newConfig.getJenkins().setActive(true);
            newConfig.getJenkins().setInternal(false);
            newConfig.getJenkins().setUrlForScm(newConfig.getJenkins().getUrl());
        } else if (Boolean.TRUE.equals(newConfig.getJenkins().getActive())) {
            log.debug("Setting configs for internal jenkins");
            // We use the K8s service as default name here, because it is the only option:
            // "jenkins.localhost" will not work inside the Pods and k3d-container IP + Port (e.g. 172.x.y.z:9090)
            // will not work on Windows and MacOS.
            String defaultNamespace = newConfig.getJenkins().getNamespace();
            newConfig.getJenkins().setUrlForScm("http://jenkins." + newConfig.getApplication().getNamePrefix() + defaultNamespace + ".svc.cluster.local");
        } else {
            // Jenkins not active, no need to set the following values
            return;
        }

        if (hasText(newConfig.getApplication().getBaseUrl())) {
            try {
                newConfig.getJenkins().setIngress(new URL(injectSubdomain("jenkins",
                        newConfig.getApplication().getBaseUrl(), Boolean.TRUE.equals(newConfig.getApplication().getUrlSeparatorHyphen()))).getHost());
            } catch (MalformedURLException e) {
                throw new RuntimeException("Failed to evaluate Jenkins ingress URL", e);
            }
        }

        // When specific user/pw are not set, set them to global values
        if (Config.DEFAULT_ADMIN_USER.equals(newConfig.getJenkins().getUsername())) {
            newConfig.getJenkins().setUsername(newConfig.getApplication().getUsername());
        }
        if (Config.DEFAULT_ADMIN_PW.equals(newConfig.getJenkins().getPassword())) {
            newConfig.getJenkins().setPassword(newConfig.getApplication().getPassword());
        }
    }

    private void evaluateBaseUrl(Config newConfig) {
        String baseUrl = newConfig.getApplication().getBaseUrl();
        if (!hasText(baseUrl)) {
            return;
        }
        log.debug("Base URL set, adapting to individual tools");
        var argocd = newConfig.getFeatures().getArgocd();
        var monitoring = newConfig.getFeatures().getMonitoring();
        var vault = newConfig.getFeatures().getSecrets().getVault();
        boolean urlSeparatorHyphen = Boolean.TRUE.equals(newConfig.getApplication().getUrlSeparatorHyphen());

        if (Boolean.TRUE.equals(argocd.getActive()) && !hasText(argocd.getUrl())) {
            argocd.setUrl(injectSubdomain("argocd", baseUrl, urlSeparatorHyphen));
            log.debug("Setting ArgoCD URL {}", argocd.getUrl());
        }
        if (Boolean.TRUE.equals(monitoring.getActive()) && !hasText(monitoring.getGrafanaUrl())) {
            monitoring.setGrafanaUrl(injectSubdomain("grafana", baseUrl, urlSeparatorHyphen));
            log.debug("Setting Monitoring URL {}", monitoring.getGrafanaUrl());
        }
        if (Boolean.TRUE.equals(newConfig.getFeatures().getSecrets().getActive()) && !hasText(vault.getUrl())) {
            vault.setUrl(injectSubdomain("vault", baseUrl, urlSeparatorHyphen));
            log.debug("Setting Vault URL {}", vault.getUrl());
        }
    }

    public void setMultiTenantModeConfig(Config newConfig) {
        if (Boolean.TRUE.equals(newConfig.getMultiTenant().getUseDedicatedInstance())) {
            if (!hasText(newConfig.getApplication().getNamePrefix())) {
                throw new RuntimeException("To enable Central Multi-Tenant mode, you must define a name prefix to distinguish between instances.");
            }

            if (!Boolean.TRUE.equals(newConfig.getFeatures().getArgocd().getOperator())) {
                newConfig.getFeatures().getArgocd().setOperator(true);
            }

            // Removes trailing slash from the input URL to avoid duplicated slashes in further URL handling
            if (newConfig.getMultiTenant().getScmManager().getUrl() != null) {
                String urlString = newConfig.getMultiTenant().getScmManager().getUrl();
                if (urlString.endsWith("/")) {
                    urlString = urlString.substring(0, urlString.length() - 1);
                }
                newConfig.getMultiTenant().getScmManager().setUrl(urlString);
            }

            // Disabling Ingress in DedicatedInstances Mode for now.
            newConfig.getFeatures().getIngress().setActive(false);
        }
    }

    private String injectSubdomain(String subdomain, String baseUrl, boolean urlSeparatorHyphen) {
        try {
            URL url = new URL(baseUrl);
            String newUrl;

            if (urlSeparatorHyphen) {
                newUrl = url.getProtocol() + "://" + subdomain + "-" + url.getHost();
            } else {
                newUrl = url.getProtocol() + "://" + subdomain + "." + url.getHost();
            }
            if (url.getPort() != -1) {
                newUrl += ":" + url.getPort();
            }
            newUrl += url.getPath();
            return newUrl;
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to inject subdomain '" + subdomain + "' into base URL: " + baseUrl, e);
        }
    }

    private void setResourceInclusionsCluster(Config configToSet) {
        // Return early if NOT deploying via operator
        if (!Boolean.TRUE.equals(configToSet.getFeatures().getArgocd().getOperator())) {
            log.debug("ArgoCD operator is not enabled. Skipping features.argocd.resourceInclusionsCluster setup.");
            return;
        }
        log.info("Starting setup of features.argocd.resourceInclusionsCluster for ArgoCD Operator");

        if (!isUrlSetAndValid(configToSet)) {
            buildAndValidateURLFromEnvironment(configToSet);
        }
    }

    public boolean isUrlSetAndValid(Config config) {
        String url = config.getFeatures().getArgocd().getResourceInclusionsCluster();

        if (hasText(url)) {
            try {
                log.debug("Validating user-provided features.argocd.resourceInclusionsCluster URL: {}", url);
                new URL(url);
                log.info("Found valid URL in features.argocd.resourceInclusionsCluster: {}", url);
                return true;
            } catch (MalformedURLException e) {
                 throw new IllegalArgumentException("Invalid URL for 'features.argocd.resourceInclusionsCluster': " + url + ".", e);
            }
        }
        return false;
    }

    public void buildAndValidateURLFromEnvironment(Config config) {
        log.debug("Attempting to set features.argocd.resourceInclusionsCluster via Kubernetes ENV variables.");

        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");

        String errorMessage = "Could not determine 'features.argocd.resourceInclusionsCluster' which is required when argocd.operator=true. " +
                "Ensure Kubernetes environment variables 'KUBERNETES_SERVICE_HOST' and 'KUBERNETES_SERVICE_PORT' are set properly. " +
                "Alternatively, try setting 'features.argocd.resourceInclusionsCluster' in the config to manually override.";

        if (!hasText(host) || !hasText(port)) {
            throw new RuntimeException(errorMessage);
        }

        String internalClusterUrl = "https://" + host + ":" + port;
        log.debug("Constructed internal Kubernetes API Server URL: {}", internalClusterUrl);

        try {
            new URL(internalClusterUrl);
            config.getFeatures().getArgocd().setResourceInclusionsCluster(internalClusterUrl);
            log.info("Successfully set features.argocd.resourceInclusionsCluster via Kubernetes ENV to: {}", internalClusterUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException(errorMessage, e);
        }
    }

    public void checkAndSetNamespaces(Config config) {
        if (hasText(config.getApplication().getNamespace())) {
            String namespace = config.getApplication().getNamespace();
            config.getApplication().setGopNamespace(namespace);
            config.getRegistry().setNamespace(namespace);
            config.getJenkins().setNamespace(namespace);
            config.getScm().getScmManager().setNamespace(namespace);
            config.getFeatures().getArgocd().setNamespace(namespace);
            config.getFeatures().getMonitoring().setNamespace(namespace);
            config.getFeatures().getSecrets().setNamespace(namespace);
            config.getFeatures().getIngress().setIngressNamespace(namespace);
            config.getFeatures().getCertManager().setNamespace(namespace);

            config.getContent().getNamespaces().clear();
            String contentNamespace = config.getApplication().getNamePrefix() + namespace;
            config.getContent().getNamespaces().add(contentNamespace);
        }
    }
}
