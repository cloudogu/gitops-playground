#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$( cd ${BASEDIR} && pwd )"

source ${ABSOLUTE_BASEDIR}/utils.sh

confirm "Removing gitops playground from kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' \
 || exit 0
 
helm delete scmm -n default
helm delete jenkins -n default
helm delete flux-operator -n default
helm delete helm-operator -n default
helm delete docker-registry -n default

kubectl delete -f jenkins/resources
kubectl delete -f scm-manager/resources

