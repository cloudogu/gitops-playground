package com.cloudogu.gitops.features.argocd

import com.cloudogu.gitops.Feature
import groovy.util.logging.Slf4j
import groovy.yaml.YamlBuilder
import org.yaml.snakeyaml.Yaml

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson

@Slf4j
class ArgoCDOperator extends Feature {

    private Map config
    private Map argoCDConfig

    ArgoCDOperator(Map config) {
        this.config = config
        this.argoCDConfig = config.features['argocd'] as Map
        log.debug("Deploy ArgoCD via Operator: ${prettyPrint(toJson(this.config))}")
    }

    def generateResource() {
        def resource = generateBasicConfiguration()

        if (config.application['openshift'])
            addOpenshiftConfiguration(resource)
        else
            addDefaultConfiguration(resource)

        return resource as Map
    }

    def addDefaultConfiguration(Map resource) {

    }

    def addOpenshiftConfiguration(Map resource) {
        addResourceInclusions(resource)
        addOpenshiftSSO(resource)
        addOpenshiftRoute(resource)
    }

    def generateBasicConfiguration() {
         return  ['apiVersion': 'argoproj.io/v1alpha1',
             'kind': 'ArgoCD',
             'metadata': [
                     'name'     : 'argocd',
                     'namespace': argoCDConfig.namespace
             ],
             'spec': [
                     'applicationSet': [:],
                     'notifications': [
                             'enabled': true
                     ],
                     'controller': [:],
                     'ha': [
                             'enabled': false
                     ],
                     'rbac': [
                             'defaultPolicy': '',
                             'policy': """
g, system:cluster-admins, role:admin
g, platform-admin, role:admin""",
                             'scopes': '[groups]'
                     ],
                     'redis': [:],
                     'repo': [:],
                     'server': [:]
             ]
            ] as Map
    }

    def addOpenshiftRoute(Map resource) {
        resource.spec['server']['route'] = [
                'enabled': true,
                'path': argoCDConfig.url
        ]
    }

    def addOpenshiftSSO(Map resource) {
        resource.spec['dex'] = [
                'openShiftOAuth': true
        ]
    }

    def addResourceInclusions(Map argocdResource) {
//        argocdResource.spec['resourceInclusions'] = [
//                ['apiGroups': [''], 'kinds': ['Secret', 'ConfigMap', 'PersistentVolumeClaim', 'Service', 'ServiceAccount', 'Pod']],
//                ['apiGroups': ['apps'], 'kinds': ['Deployment', 'ReplicaSet', 'StatefulSet', 'DaemonSet']],
//                ['apiGroups': ['extensions'], 'kinds': ['Ingress']],
//                ['apiGroups': ['networking.k8s.io'], 'kinds': ['Ingress', 'NetworkPolicy']],
//                ['apiGroups': ['argoproj.io'], 'kinds': ['Application', 'AppProject']],
//                ['apiGroups': ['route.openshift.io'], 'kinds': ['Route']],
//                ['apiGroups': ['rbac.authorization.k8s.io'], 'kinds': ['Role', 'RoleBinding']],
//                ['apiGroups': ['external-secrets.io'], 'kinds': ['SecretStore', 'ExternalSecret']],
//                ['apiGroups': ['argoproj.io'], 'kinds': ['ArgoCD']],
//                ['apiGroups': ['monitoring.coreos.com'], 'kinds': ['Alertmanager', 'AlertmanagerConfig', 'Prometheus', 'PrometheusRule', 'ServiceMonitor', 'PodMonitor', 'Probe']],
//                ['apiGroups': ['storage.k8s.io'], 'kinds': ['StorageClass', 'PersistentVolumeClaim']]
//        ]

        argocdResource.spec['resourceInclusions'] = """
  - apiGroups:
    - ""
    kinds:
    - "Secret"
    - "ConfigMap"
    - "PersistentVolumeClaim"
    - "Service"
    - "ServiceAccount"
    - "Pod"
  - apiGroups:
    - "apps"
    kinds:
    - "Deployment"
    - "ReplicaSet"
    - "StatefulSet"
    - "DaemonSet"
  - apiGroups:
    - "extensions"
    kinds:
    - "Ingress"
  - apiGroups:
    - "networking.k8s.io"
    kinds:
    - "Ingress"
    - "NetworkPolicy"
  - apiGroups:
    - "argoproj.io"
    kinds:
    - "Application"
    - "AppProject"
  - apiGroups:
    - "route.openshift.io"
    kinds:
    - "Route"
  - apiGroups:
    - "rbac.authorization.k8s.io"
    kinds:
    - "Role"
    - "RoleBinding"
  - apiGroups:
    - "external-secrets.io"
    kinds:
    - "SecretStore"
    - "ExternalSecret"
  - apiGroups:
    - "argoproj.io"
    kinds:
    - "ArgoCD"
  - apiGroups:
    - "monitoring.coreos.com"
    kinds:
    - "Alertmanager"
    - "AlertmanagerConfig"
    - "Prometheus"
    - "PrometheusRule"
    - "ServiceMonitor"
    - "PodMonitor"
    - "Probe"
  - apiGroups:
    - "storage.k8s.io"
    kinds:
    - "StorageClass"
    - "PersistentVolumeClaim"
"""
    }

    def apply() {
        def sout = new StringBuilder(), serr = new StringBuilder()
//        YamlBuilder yaml = new YamlBuilder()

        def yaml = new YamlBuilder()
        yaml generateResource()

        def argocdYaml = yaml.toString()
        println(argocdYaml)
        def cmd = "echo '''${argocdYaml}''' | kubectl apply -f -"
        def proc = ['bash', '-c', cmd].execute()
        proc.consumeProcessOutput(sout, serr)
        proc.waitFor()
        if (proc.exitValue() != 0) {
            throw new RuntimeException("Failed to apply YAML: ${serr}")
        }
        log.info("Successfully applied ArgoCD YAML: ${sout}")
    }

    @Override
    boolean isEnabled() {
        return argoCDConfig.active && !argoCDConfig.configOnly && argoCDConfig.operator
    }

    @Override
    void enable() {
        apply()
    }
}
