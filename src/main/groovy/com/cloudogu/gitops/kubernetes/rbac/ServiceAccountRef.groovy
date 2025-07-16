package com.cloudogu.gitops.kubernetes.rbac

class ServiceAccountRef {
    String name
    String namespace

    ServiceAccountRef(String name, String namespace) {
        if (!name?.trim()) {
            throw new IllegalArgumentException("ServiceAccount name must not be blank")
        }
        if (!namespace?.trim()) {
            throw new IllegalArgumentException("ServiceAccount namespace must not be blank")
        }
        this.name = name
        this.namespace = namespace
    }

    static List<ServiceAccountRef> fromNames(String namespace, List<String> names) {
        if (!namespace?.trim()) {
            throw new IllegalArgumentException("Namespace must not be blank for service accounts")
        }

        return names
                .findAll { it?.trim() }
                .unique()
                .collect { new ServiceAccountRef(it, namespace) }
    }

    Map<String, String> toMap() {
        return [name: name, namespace: namespace]
    }
}
