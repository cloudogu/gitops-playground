package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class NetworkingUtils {

  private final K8sClient k8sClient;
  private final CommandExecutor commandExecutor;

  public NetworkingUtils() {
    this(new K8sClient(), new CommandExecutor());
  }

  public NetworkingUtils(K8sClient k8sClient) {
    this(k8sClient, new CommandExecutor());
  }

  public String createUrl(String hostname, String port) {
    return createUrl(hostname, port, "");
  }

  public String createUrl(String hostname, String port, String postfix) {
    String url = "http://" + hostname + ":" + port + postfix;
    log.debug("Creating url: {}", url);
    return url;
  }

  public String findClusterBindAddress() {
    log.debug("Figuring out the address of the k8s cluster");

    String potentialClusterBindAddress = k8sClient.waitForInternalNodeIp();
    if (potentialClusterBindAddress != null) {
      potentialClusterBindAddress = potentialClusterBindAddress.replace("'", "");
    }

    String localAddress = getLocalAddress();

    log.debug("Local address: {}", localAddress);
    log.debug("Cluster address: {}", potentialClusterBindAddress);

    if (potentialClusterBindAddress == null || potentialClusterBindAddress.isEmpty()) {
      throw new RuntimeException(
          "Could not connect to kubernetes cluster: no cluster bind address");
    }

    if (localAddress.equals(potentialClusterBindAddress)) {
      log.debug("Local address and cluster bind address are equal, so returning localhost");
      return "localhost";
    } else {
      log.debug("Installing on external cluster, so returning cluster ip address");
      return potentialClusterBindAddress;
    }
  }

  public String getLocalAddress() {
    try {
      List<NetworkInterface> sortedInterfaces =
          Collections.list(NetworkInterface.getNetworkInterfaces());
      sortedInterfaces.sort(Comparator.comparingInt(NetworkInterface::getIndex));

      for (NetworkInterface anInterface : sortedInterfaces) {
        for (InetAddress address : Collections.list(anInterface.getInetAddresses())) {
          if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
            return address.getHostAddress();
          }
        }
      }
      return "";
    } catch (SocketException e) {
      throw new RuntimeException("Could not determine local ip address", e);
    }
  }

  @Deprecated
  public static String getHost(String url) {
    String protocol = getProtocol(url);
    if (protocol.isEmpty()) {
      return url;
    }
    return url.substring(protocol.length() + "://".length());
  }

  @Deprecated
  public static String getProtocol(String url) {
    if (url.contains("https://")) {
      return "https";
    }
    if (url.contains("http://")) {
      return "http";
    }
    return "";
  }
}
