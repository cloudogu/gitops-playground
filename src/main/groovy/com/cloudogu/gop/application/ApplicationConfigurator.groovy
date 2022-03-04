package com.cloudogu.gop.application

import ch.qos.logback.classic.Level
import com.cloudogu.gop.application.utils.FileSystemUtils
import com.cloudogu.gop.application.utils.NetworkingUtils
import groovy.util.logging.Slf4j
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

@Slf4j
class ApplicationConfigurator {

    private Map config
    private NetworkingUtils networkingUtils
    private FileSystemUtils fileSystemUtils

    ApplicationConfigurator(Map config, NetworkingUtils networkingUtils = new NetworkingUtils(), FileSystemUtils fileSystemUtils = new FileSystemUtils()) {
        this.config = config
        this.networkingUtils = networkingUtils
        this.fileSystemUtils = fileSystemUtils
    }

    Map populateConfig() {
        // TODO currently only variables were set which are at the beginning of the apply.sh.
        // There are still more to check and implement in the main function and elsewhere

        log.info("Populating application config with derived options from existing configuration")
        setLogLevel()
        addInternalStatus()
        addAdditionalApplicationConfig()
        setScmmConfig()
        setMailhogConfig()
        addServiceUrls()
        setDefaultImagesIfNotConfigured()
        addRepos()

        log.debug(prettyPrint(toJson(config)))
        return new LinkedHashMap(config)
    }

    private void addInternalStatus() {
        config.jenkins["internal"] = config.jenkins["url"] ? false : true
        config.registry["internal"] = config.registry["url"] ? false : true
    }

    private void addAdditionalApplicationConfig() {
        log.debug("Setting additional application config")
        String appUsername = config.application["username"]
        String appPassword = config.application["password"]

        if (appUsername == null) {
            log.debug("No application username was set. Setting default username: admin")
            config.application["username"] = "admin"
        }
        if (appPassword == null) {
            log.debug("No application password was set. Setting default password: admin")
            config.application["password"] = "admin"
        }
        if (System.getenv("KUBERNETES_SERVICE_HOST")) {
            log.debug("Gop installation is running in kubernetes.")
            config.application["runningInsideK8s"] = true
        } else {
            log.debug("Gop installation is not running in kubernetes")
            config.application["runningInsideK8s"] = false
        }
        String clusterBindAddress = networkingUtils.findClusterBindAddress()
        log.debug("Setting cluster bind Address: " + clusterBindAddress)
        config.application["clusterBindAddress"] = clusterBindAddress
    }

    private void setScmmConfig() {
        log.debug("Adding additional config for SCM-Manager")
        config.scmm["internal"] = true
        config.scmm["urlForJenkins"] = "http://scmm-scm-manager/scm"

        if (config.scmm["url"] != null && !(config.scmm["url"] as String).empty) {
            log.debug("Setting external scmm config")
            config.scmm["internal"] = false
            config.scmm["urlForJenkins"] = config.scmm["url"]
        } else if (config.application["runningInsideK8s"]) {
            log.debug("Setting scmm url to k8s service, since installation is running inside k8s")
            config.scmm["url"] = networkingUtils.createUrl("scmm-scm-manager.default.svc.cluster.local", "80", "/scm")
        } else {
            log.debug("Setting internal scmm configs")
            def port = fileSystemUtils.getLineFromFile(fileSystemUtils.getGopRoot() + "/scm-manager/values.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            String cba = config.application["clusterBindAddress"]
            config.scmm["url"] = networkingUtils.createUrl(cba, port, "/scm")
        }

        if (config.scmm["internal"]) {
            log.debug("Setting the scmm credentials")
            if (config.scmm["username"] == null) config.scmm["username"] = config.application["username"]
            if (config.scmm["password"] == null) config.scmm["password"] = config.application["password"]
        }
        String scmmUrl = config.scmm["url"]
        log.debug("Getting host and protocol from scmmUrl: " + scmmUrl)
        config.scmm["host"] = networkingUtils.getHost(scmmUrl)
        config.scmm["protocol"] = networkingUtils.getProtocol(scmmUrl)
    }

    private void setMailhogConfig() {
        if (config.mailhog["username"] == null) config.mailhog["username"] = config.application["username"]
        if (config.mailhog["password"] == null) config.mailhog["password"] = config.application["password"]
    }

    private void addServiceUrls() {
        log.debug("Adding additional config for Jenkins")
        config.jenkins["urlForScmm"] = "http://jenkins"
    }

    private void setDefaultImagesIfNotConfigured() {
        log.debug("Adding additional config for images")
        if (config.images["kubectl"] == null) config.images["kubectl"] = "lachlanevenson/k8s-kubectl:v1.21.2"
        if (config.images["helm"] == null) config.images["helm"] = "ghcr.io/cloudogu/helm:3.5.4-1"
        if (config.images["kubeval"] == null) config.images["kubeval"] = config.images["helm"]
        if (config.images["helmKubeval"] == null) config.images["helmKubeval"] = config.images["helm"]
        if (config.images["yamllint"] == null) config.images["yamllint"] = "cytopia/yamllint:1.25-0.7"
    }

    private void addRepos() {
        log.debug("Adding additional config for repos")

        config.repositories = [
                springBootHelmChart: "https://github.com/cloudogu/spring-boot-helm-chart.git",
                springPetclinic    : "https://github.com/cloudogu/spring-petclinic.git",
                gitopsBuildLib     : "https://github.com/cloudogu/gitops-build-lib.git",
                cesBuildLib        : "https://github.com/cloudogu/ces-build-lib.git"
        ]
    }

    private void setLogLevel() {
        boolean trace = config.application["trace"]
        boolean debug = config.application["debug"]
        Logger gopLogger = (Logger) LoggerFactory.getLogger("com.cloudogu.gop");
        if (trace) {
            log.info("Setting loglevel to trace")
            gopLogger.setLevel(Level.TRACE)
        } else if(debug) {
            log.info("Setting loglevel to debug")
            gopLogger.setLevel(Level.DEBUG);
        } else {
            gopLogger.setLevel(Level.INFO)
        }
    }
}
