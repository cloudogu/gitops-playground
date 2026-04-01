application:
  baseUrl: "http://localhost"
  insecure: true
features:
  argocd:
    active: true
  monitoring:
    active: true
    helm:
      grafanaImage: "<address>/library/grafana:latest"
      grafanaSidecarImage: "<address>/library/k8s-sidecar:latest"
      prometheusImage: "<address>/library/prometheus:latest"
      prometheusOperatorImage: "<address>/library/prometheus-operator:latest"
      prometheusConfigReloaderImage: "<address>/library/prometheus-config-reloader:latest"
  secrets:
    externalSecrets:
      helm:
        image: "<address>/library/external-secrets:latest"
    vault:
      helm:
        image: "<address>/library/vault:latest"
  ingress:
    active: true
    helm:
      values:
        image:
          registry: "<address>"
          repository: "library/traefik"
          tag: "v3.3.3"
  certManager:
    active: true
    helm:
      version: v1.16.1
      image: "<address>/library/cert-manager-controller:latest"
      webhookImage: "<address>/library/cert-manager-webhook:latest"
      cainjectorImage: "<address>/library/cert-manager-cainjector:latest"
  mail:
    mailServer: true
    helm:
      image: "<address>/library/mailhog:latest"

