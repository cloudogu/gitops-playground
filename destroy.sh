#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

helm delete scmm -n default
helm delete jenkins -n default
helm delete flux-operator -n default
helm delete helm-operator -n default

kubectl delete -f jenkins/resources
kubectl delete -f scm-manager/resources

