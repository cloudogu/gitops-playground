#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$( cd ${BASEDIR} && pwd )"

source ${ABSOLUTE_BASEDIR}/utils.sh

confirm "Removing gitops playground from kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' \
 || exit 0
 
# Don't fail when resources were not there 

helm delete scmm -n default || true
helm delete jenkins -n default || true
helm delete flux-operator -n default || true
helm delete helm-operator -n default || true
helm delete docker-registry -n default || true

kubectl delete -f jenkins/resources || true
kubectl delete -f scm-manager/resources || true

kubectl delete -f argocd/resources || true
helm delete argocd -n default || true

kubectl delete -f k8s-namespaces || true

# remove symlink
echo "Removing /var/jenkins_home/workspace (which symlinks into this directory)"
sudo rm -rf /var/jenkins_home/workspace