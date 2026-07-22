package com.cloudogu.gitops.utils;

import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import jakarta.inject.Singleton;
import java.io.UncheckedIOException;
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
      throw new IllegalStateException(
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
        String address = firstSiteLocalAddress(anInterface);
        if (address != null) {
          return address;
        }
      }
      return "";
    } catch (SocketException e) {
      throw new UncheckedIOException("Could not determine local ip address", e);
    }
  }

  private static String firstSiteLocalAddress(NetworkInterface networkInterface) {
    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
      if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
        return address.getHostAddress();
      }
    }
    return null;
  }
}
