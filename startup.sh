#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

kubectl apply -f jenkins/resources
kubectl apply -f scm-manager/resources

helm repo add jenkins https://charts.jenkins.io
helm repo add fluxcd https://charts.fluxcd.io

helm upgrade -i  scmm --values scm-manager/values.yaml --set-file=postStartHookScript=scm-manager/initscmm.sh scm-manager/chart -n default
helm upgrade -i jenkins --values jenkins/values.yaml --version 2.13.0 jenkins/jenkins -n default
helm upgrade -i flux-operator --values flux-operator/values.yaml --version 1.3.0 fluxcd/flux -n default
helm upgrade -i helm-operator --values helm-operator/values.yaml --version 1.0.2 fluxcd/helm-operator -n default