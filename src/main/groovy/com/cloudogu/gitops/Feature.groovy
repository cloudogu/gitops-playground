package com.cloudogu.gitops

import com.cloudogu.gitops.utils.MapUtils
import com.cloudogu.gitops.utils.TemplatingEngine
import groovy.util.logging.Slf4j
import groovy.yaml.YamlSlurper

import java.nio.file.Path

/**
 * A single tool to be deployed by GOP.
 * 
 * Typically, this is a helm chart (see {@link com.cloudogu.gitops.features.deployment.DeploymentStrategy} and 
 * {@code downloadHelmCharts.sh}) with its own section in the config
 * (see {@link com.cloudogu.gitops.config.schema.Schema#features}).<br/><br/>
 * 
 * In the config, features typically set their default helm chart coordinates and provide options to
 * <ul>
 *   <li>configure images</li>
 *   <li>overwrite default helm values</li>
 * </ul><br/>
 * 
 * In addition to their own config, features react to several generic GOP config options.<br/>
 * Here are some typical examples:
 * <ul>
 *   <li>Mirror the Helm Chart: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#mirrorRepos} see {@link com.cloudogu.gitops.utils.AirGappedUtils#mirrorHelmRepoToGit(java.util.Map)} </li>
 *   <li>Create Image Pull Secrets: {@link com.cloudogu.gitops.config.schema.Schema.RegistrySchema#createImagePullSecrets} see {@link FeatureWithImage}</li>
 *   <li>Install with Network Policies: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#netpols}</li>
 *   <li>Install with Resource requests + limits: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#podResources}</li>
 *   <li>Install without CRDs: {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#skipCrds}</li>
 *   <li>For apps with UI: Setting {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#username} and {@link com.cloudogu.gitops.config.schema.Schema.ApplicationSchema#password}</li>
 * </ul>
 */

@Slf4j
abstract class Feature {

    boolean install() {
        if (isEnabled()) {
            log.info("Installing Feature ${getClass().getSimpleName()}")
            
            if (this instanceof FeatureWithImage) {
                (this as FeatureWithImage).createImagePullSecret()
            }

            enable()
            return true
        } else {
            log.debug("Feature ${getClass().getSimpleName()} is disabled")
            disable()
            return false
        }
    }

    String getActiveNamespaceFromFeature() {
        //using reflection to get all subclasses implementing a own namespace
        if (this.metaClass.hasProperty(this, 'namespace'))  {
            return isEnabled() ? this.getProperty('namespace') : null
        }
        return null
    }

    static Map templateToMap(String filePath, Map parameters) {
        def hydratedString = new TemplatingEngine().template(new File(filePath), parameters)

        if (hydratedString.trim().isEmpty()) {
            // Otherwise YamlSlurper returns an empty array, whereas we expect a Map
            return [:]
        }
        return new YamlSlurper().parseText(hydratedString) as Map
    }

    abstract boolean isEnabled()



    /*
     *  Hooks for enabling or disabling a feature. Both optional, because not always needed.
     */
    protected void enable() {}
    protected void disable() {}

    /*
     * Hook for special feature validation. Optional.
     * Feature should throw RuntimeException to stop immediately.
     */
    protected void validate() { }

}