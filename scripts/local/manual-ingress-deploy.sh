#!/bin/bash

cat <<EOF > values.yaml
deployment:
  kind: Deployment
  podAnnotations:
    ingressclass.kubernetes.io/is-default-class: "true"
  podLabels:
    traefik.http.middlewares.gzip.compress: "true"
  admissionWebhooks:
    enabled: false
  service:
    externalTrafficPolicy: Local
  ports:
    websecure:
      proxyProtocol:
        trustedIPs:
          - "127.0.0.1/32"
          - "172.18.0.0/12"
      forwardedHeaders:
        trustedIPs:
          - "127.0.0.1/32"
          - "172.18.0.0/12"
  replicaCount: 2
  resources:
    general:
      level: INFO
    access:
      enabled: true
  global:
    checknewversion: false
    sendAnonymousUsage: false
providers:
  kubernetesGateway:
    enabled: true
gatewayClass:
  enabled: true
  name: "traefik"
gateway:
  enabled: true
EOF

helm repo add traefik https://traefik.github.io/charts

helm upgrade --install traefik traefik/traefik \
  --version 39.0.0 \
  --namespace ingress \
  --create-namespace \
  -f values.yaml && rm ./values.yaml