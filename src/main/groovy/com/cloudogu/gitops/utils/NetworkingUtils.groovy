package com.cloudogu.gitops.utils


import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

import java.util.regex.Matcher
import java.util.regex.Pattern

@Slf4j
@Singleton
class NetworkingUtils {

    private K8sClient k8sClient
    private CommandExecutor commandExecutor

    NetworkingUtils(K8sClient k8sClient, CommandExecutor commandExecutor) {
        this.k8sClient = k8sClient
        this.commandExecutor = commandExecutor
    }

    String createUrl(String hostname, String port, String postfix = "") {
        // argo forwards to HTTPS so symply us HTTP here
        String url = "http://" + hostname + ":" + port + postfix
        log.debug("Creating url: " + url)
        return url
    }

    String findClusterBindAddress() {
        log.debug("Figuring out the address of the k8s cluster")

        String potentialClusterBindAddress = k8sClient.getInternalNodeIp()
        potentialClusterBindAddress = potentialClusterBindAddress.replaceAll("'", "")

        def ipCommand = 'ip route get 1'
        String outputIpCommand = commandExecutor.execute(ipCommand).stdOut
        if (!outputIpCommand.contains('src'))  {
            throw new RuntimeException("Could not determine local ip address, because command '${ipCommand}' returned: '${outputIpCommand}'")
        }
        String substringWithSrcIp = outputIpCommand.substring(outputIpCommand.indexOf('src'))
        String localAddress = getIpFromString(substringWithSrcIp)

        log.debug("Local address: " + localAddress)
        log.debug("Cluster address: " + potentialClusterBindAddress)

        if(potentialClusterBindAddress == null || potentialClusterBindAddress.isEmpty()) {
            throw new RuntimeException("Could not connect to kubernetes cluster: no cluster bind address")
        }

        if (localAddress == potentialClusterBindAddress) {
            log.debug("Local address and cluster bind address are equal, so returning localhost")
            return "localhost"
        } else {
            log.debug("Installing on external cluster, so returning cluster ip address")
            return potentialClusterBindAddress
        }
    }

    String getHost(String url) {
        if (url.contains("https://"))
            return url.substring(8)
        if (url.contains("http://"))
            return url.substring(7)
    }

    String getProtocol(String url) {
        if (url.contains("https://"))
            return "https"
        if (url.contains("http://"))
            return "http"
    }

    String getIpFromString(String ipString) {
        log.debug("Extracting ip address from string: " + ipString)
        String IPADDRESS_PATTERN =
                "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)"

        Pattern pattern = Pattern.compile(IPADDRESS_PATTERN);
        Matcher matcher = pattern.matcher(ipString);
        if (matcher.find()) {
            String ipAddress = matcher.group()
            log.debug("Returning found ip address: " + ipAddress)
            return ipAddress
        } else {
            log.debug("No ip address found in String. Returning 0.0.0.0")
            return "0.0.0.0"
        }
    }
}
