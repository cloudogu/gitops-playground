package com.cloudogu.gitops.utils

import groovy.util.logging.Slf4j
import jakarta.inject.Singleton

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

        String localAddress = localAddress

        log.debug("Local address: " + localAddress)
        log.debug("Cluster address: " + potentialClusterBindAddress)

        if(!potentialClusterBindAddress) {
            throw new RuntimeException("Could not connect to kubernetes cluster: no cluster bind address")
        }

        if (localAddress == potentialClusterBindAddress) {
            // This happens, when running on local cluster that runs in the host network.
            // The reasons for introducing this might not be valid anymore:
            // https://github.com/cloudogu/gitops-playground/commit/ea805d
            // We no longer use jenkins notifications and have removed the address part from the welcome screen.
            // So in the future, we might consider removing this and the whole localAdresse part to reduce complexity.
            log.debug("Local address and cluster bind address are equal, so returning localhost")
            return "localhost"
        } else {
            log.debug("Installing on external cluster, so returning cluster ip address")
            return potentialClusterBindAddress
        }
    }

    /**
     * Try to emulate the command "ip route get 1" by iterating the interfaces by index and returning first local address
     */
    String getLocalAddress() {
        try {
            List<NetworkInterface> sortedInterfaces = 
                    Collections.list(NetworkInterface.getNetworkInterfaces()).sort { it.index }

            for (NetworkInterface anInterface : sortedInterfaces) {
                for (InetAddress address : Collections.list(anInterface.inetAddresses)) {
                    if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress()
                    }
                }
            }
            return ''
        } catch (SocketException e) {
            throw new RuntimeException("Could not determine local ip address", e)
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
}
