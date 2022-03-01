package com.cloudogu.gop.application.utils

import com.cloudogu.gop.application.clients.k8s.K8sClient
import groovy.util.logging.Slf4j

import java.util.regex.Matcher
import java.util.regex.Pattern

@Slf4j
class NetworkingUtils {

    static String createUrl(String hostname, String port, String postfix = "") {
        // argo forwards to HTTPS so symply us HTTP here
        String url = "http://" + hostname + ":" + port + postfix
        log.debug("Creating url: " + url)
        return url
    }

    static String findClusterBindAddress() {
        log.debug("Figuring out the address of the k8s cluster")

        String potentialClusterBindAddress = new K8sClient().getInternalNodeIp()
        potentialClusterBindAddress = potentialClusterBindAddress.replaceAll("'", "")
        String ipConfig = CommandExecutor.execute("ip route get 1")
        String substringWithSrcIp = ipConfig.substring(ipConfig.indexOf("src"))
        String localAddress = getIPFromString(substringWithSrcIp)

        log.debug("Local address: " + localAddress)
        log.debug("Cluster address: " + potentialClusterBindAddress)

        if (localAddress == potentialClusterBindAddress) {
            log.debug("Local address and cluster bind address are equal, so returning localhost")
            return "localhost"
        } else {
            log.debug("Installing on external cluster, so returning cluster ip address")
            return potentialClusterBindAddress
        }
    }

    static String getHost(String url) {
        if (url.contains("https://"))
            return url.substring(8)
        if (url.contains("http://"))
            return url.substring(7)
    }

    static String getProtocol(String url) {
        if (url.contains("https://"))
            return "https"
        if (url.contains("http://"))
            return "http"
    }

    static String getIPFromString(String ipString) {
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
