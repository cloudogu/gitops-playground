package com.cloudogu.gitops.kubernetes.rbac

class ServiceAccountRef {
    final String name
    final String namespace

    ServiceAccountRef(String name, String namespace) {
        this.name = name
        this.namespace = namespace
    }

    static List<ServiceAccountRef> fromNames(String namespace, List<String> names) {
        return names.collect { new ServiceAccountRef(it, namespace) }
    }

    Map<String, String> toMap() {
        return [name: name, namespace: namespace]
    }
}
