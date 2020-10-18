#!/usr/bin/env bash
#set -o errexit -o nounset -o pipefail
#set -x

[ "$UID" -eq 0 ] || exec sudo bash "$0" "$@"

rm /var/lib/rancher/k3s/server/manifests/flux-operator.yaml
rm /var/lib/rancher/k3s/server/manifests/flux-scmm-secret.yaml
rm /var/lib/rancher/k3s/server/manifests/helm-operator.yaml
rm /var/lib/rancher/k3s/server/manifests/jenkins-credentials.yaml
rm /var/lib/rancher/k3s/server/manifests/jenkins-helm-chart.yaml
rm /var/lib/rancher/k3s/server/manifests/jenkins.yaml
rm /var/lib/rancher/k3s/server/manifests/jenkins-pvcs.yaml
rm /var/lib/rancher/k3s/server/manifests/jenkins-scmm-secret.yaml
rm /var/lib/rancher/k3s/server/manifests/scm-manager.yaml
