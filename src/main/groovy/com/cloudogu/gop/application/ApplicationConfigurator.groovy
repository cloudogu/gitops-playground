package com.cloudogu.gop.application

import com.cloudogu.gop.tools.k8s.K8sClient
import com.cloudogu.gop.utils.CommandExecutor
import com.cloudogu.gop.utils.FileSystemUtils
import groovy.util.logging.Slf4j

import java.util.regex.Matcher
import java.util.regex.Pattern

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

@Slf4j
class ApplicationConfigurator {

    static void populateConfig(Map config) {
        // TODO currently only variables were set which are at the beginning of the apply.sh.
        // There are still more to check and implement in the main function and elsewhere
        addInternalStatus(config)
        addAdditionalApplicationConfig(config)
        setScmmConfig(config)
        addServiceUrls(config)
        setDefaultImagesIfNotConfigured(config)
        addRepos(config)

        log.debug(prettyPrint(toJson(config)))
    }

    private static void addInternalStatus(Map config) {
        config.jenkins["internal"] = config.jenkins["url"] ? false : true
        config.registry["internal"] = config.registry["url"] ? false : true
    }

    private static void addAdditionalApplicationConfig(Map config) {
        if (System.getenv("KUBERNETES_SERVICE_HOST")) {
            config.application["runningInsideK8s"] = true
        } else {
            config.application["runningInsideK8s"] = false
        }
        config.application["clusterBindAddress"] = findClusterBindAddress()
    }

    private static void setScmmConfig(Map config) {
        config.scmm["internal"] = true
        config.scmm["urlForJenkins"] = "http://scmm-scm-manager/scm"

        if (config.scmm["url"] != null && !(config.scmm["url"] as String).empty) {
            config.scmm["internal"] = false
            config.scmm["urlForJenkins"] = config.scmm["url"]
        } else if (config.application["runningInsideK8s"]) {
            config.scmm["url"] = createUrl("scmm-scm-manager.default.svc.cluster.local", "80", "/scm")
        } else {
            def port = FileSystemUtils.getLineFromFile(FileSystemUtils.getGopRoot() + "/scm-manager/values.yaml", "nodePort:").findAll(/\d+/)*.toString().get(0)
            config.scmm["url"] = createUrl(config.application["clusterBindAddress"] as String, port, "/scm")
        }

        if (config.scmm["internal"]) {
            config.scmm["username"] = config.application["username"]
            config.scmm["password"] = config.application["password"]
        }

        config.scmm["host"] = getHost(config.scmm["url"] as String)
        config.scmm["protocol"] = getProtocol(config.scmm["url"] as String)
    }

    private static void addServiceUrls(Map config) {
        config.jenkins["urlForScmm"] = "http://jenkins"
    }

    private static void setDefaultImagesIfNotConfigured(Map config) {
        if (config.images["kubectl"] == null) config.images["kubectl"] = "lachlanevenson/k8s-kubectl:v1.21.2"
        if (config.images["helm"] == null) config.images["helm"] = "ghcr.io/cloudogu/helm:3.5.4-1"
        if (config.images["kubeval"] == null) config.images["kubeval"] = config.images["helm"]
        if (config.images["helmKubeval"] == null) config.images["helmKubeval"] = config.images["helm"]
        if (config.images["yamllint"] == null) config.images["yamllint"] = "cytopia/yamllint:1.25-0.7"
    }

    private static void addRepos(Map config) {
        config.repositories = [
                springBootHelmChart: "https://github.com/cloudogu/spring-boot-helm-chart.git",
                springPetclinic    : "https://github.com/cloudogu/spring-petclinic.git",
                gitopsBuildLib     : "https://github.com/cloudogu/gitops-build-lib.git",
                cesBuildLib        : "https://github.com/cloudogu/ces-build-lib.git"
        ]
    }

    private static String createUrl(String hostname, String port, String postfix = "") {
        // argo forwards to HTTPS so symply us HTTP here
        return "http://" + hostname + ":" + port + postfix
    }

    private static String findClusterBindAddress() {
        String potentialClusterBindAddress = new K8sClient().getInternalNodeIp()

        String ipConfig = CommandExecutor.execute("ip route get 1")
        String substringWithSrcIp = ipConfig.substring(ipConfig.indexOf("src"))
        String localAddress = getIPFromString(substringWithSrcIp)

        if (localAddress.equals(potentialClusterBindAddress)) {
            return "localhost"
        } else {
            return potentialClusterBindAddress
        }
    }

    private static String getHost(String url) {
        if (url.contains("https://"))
            return url.substring(8)
        if (url.contains("http://"))
            return url.substring(7)
    }

    private static String getProtocol(String url) {
        if (url.contains("https://"))
            return "https"
        if (url.contains("http://"))
            return "http"
    }

    private static String getIPFromString(String ipString) {
        String IPADDRESS_PATTERN =
                "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)"

        Pattern pattern = Pattern.compile(IPADDRESS_PATTERN);
        Matcher matcher = pattern.matcher(ipString);
        if (matcher.find()) {
            return matcher.group()
        } else {
            return "0.0.0.0"
        }
    }
}
