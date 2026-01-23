#!/bin/bash

cat <<EOF > values.yaml
controller:
  annotations:
    ingressclass.kubernetes.io/is-default-class: "true"
  watchIngressWithoutClass: true
  admissionWebhooks:
    enabled: false
  kind: Deployment
  service:
    externalTrafficPolicy: Local
  replicaCount: 2
  resources: null
  ingressClassResource:
    enabled: true
    default: true
  config:
    use-gzip: "true"
    enable-brotli: "true"
    log-format-upstream: >
      \$remote_addr - \$remote_user [\$time_local] "\$request" \$status \$body_bytes_sent
      "\$http_referer" "\$http_user_agent" "\$host" \$request_length \$request_time
      [\$proxy_upstream_name] [\$proxy_alternative_upstream_name] \$upstream_addr
      \$upstream_response_length \$upstream_response_time \$upstream_status \$req_id
EOF

helm upgrade --install traefik traefik/traefik \
  --version 38.0.2 \
  --namespace traefik \
  --create-namespace \
  -f values.yaml && rm ./values.yaml