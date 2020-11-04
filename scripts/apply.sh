#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

SCM_USER=scmadmin
SCM_PWD=scmadmin

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$( cd ${BASEDIR} && pwd )"
PLAYGROUND_DIR="$( cd ${BASEDIR} && cd .. && pwd )"

source ${ABSOLUTE_BASEDIR}/utils.sh

confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' \
 || exit 0

kubectl apply -f k8s-namespaces/staging.yaml
kubectl apply -f k8s-namespaces/production.yaml

kubectl apply -f jenkins/resources
kubectl apply -f scm-manager/resources

helm repo add jenkins https://charts.jenkins.io
helm repo add fluxcd https://charts.fluxcd.io
helm repo add helm-stable https://kubernetes-charts.storage.googleapis.com

helm upgrade -i  scmm --values scm-manager/values.yaml --set-file=postStartHookScript=scm-manager/initscmm.sh scm-manager/chart -n default
helm upgrade -i jenkins --values jenkins/values.yaml --version 2.13.0 jenkins/jenkins -n default
helm upgrade -i flux-operator --values flux-operator/values.yaml --version 1.3.0 fluxcd/flux -n default
helm upgrade -i helm-operator --values helm-operator/values.yaml --version 1.0.2 fluxcd/helm-operator -n default
helm upgrade -i docker-registry --values docker-registry/values.yaml --version 1.9.4 helm-stable/docker-registry -n default

# get scm-manager port from values
SCMM_PORT=$(grep -A1 'service:' scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')
cd /tmp && git clone https://github.com/cloudogu/spring-petclinic.git && cd spring-petclinic
git checkout feature/gitops_ready
while [[ "$(curl -s -L -o /dev/null -w ''%{http_code}'' "http://localhost:${SCMM_PORT}/scm")" -ne "200" ]]; do sleep 5; done;
git push "http://${SCM_USER}:${SCM_PWD}@localhost:${SCMM_PORT}/scm/repo/application/petclinic-plain" feature/gitops_ready:master --force
cd .. && rm -rf spring-petclinic

