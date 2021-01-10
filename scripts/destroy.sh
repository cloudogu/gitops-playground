#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"

source ${ABSOLUTE_BASEDIR}/utils.sh

function removeFluxv1() {
  helm delete flux-operator -n fluxv1 || true
  helm delete helm-operator -n fluxv1 || true
}

function removeFluxv2() {
  # The following line is needed because the cr "fluxv2-kustomizer" has a finalizer set which can lead to a deadlock while deleting
  # https://stackoverflow.com/a/52012367
  kubectl patch kustomization fluxv2-kustomizer -p '{"metadata":{"finalizers":[]}}' --type=merge -n fluxv2 || true
  kubectl delete -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-kustomization.yaml || true
  kubectl delete -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml || true
  kubectl delete -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-components.yaml || true
}

function removeArgoCD() {
  kubectl delete -f argocd/resources || true
  helm delete argocd -n argocd || true
}

function removeSCMM() {
  helm delete scmm -n default || true
  kubectl delete -f scm-manager/resources || true
}

function removeJenkins() {
  helm delete jenkins -n default || true
  kubectl delete -f jenkins/resources || true
}

function removeK8sResources() {
  helm delete docker-registry -n default || true
  kubectl delete secret jenkins-credentials -n default || true
  kubectl delete secret scmm-credentials -n default || true
  kubectl delete secret gitops-scmm -n default || true
  kubectl delete secret gitops-scmm -n argocd || true
  kubectl delete secret gitops-scmm -n fluxv1 || true
  kubectl delete secret gitops-scmm -n fluxv2 || true
  kubectl delete -f k8s-namespaces/ || true
}

function cleanup () {
  kubectl delete crd/helmreleases.helm.fluxcd.io || true
  kubectl delete customresourcedefinition.apiextensions.k8s.io/applications.argoproj.io || true
  kubectl delete customresourcedefinition.apiextensions.k8s.io/appprojects.argoproj.io || true
  kubectl delete apiservice.apiregistration.k8s.io/v1alpha1.argoproj.io || true
  kubectl delete appproject.argoproj.io/default || true
}

function main() {
  DEBUG=$1

  if [[ $DEBUG = true ]]; then
    removeFluxv1
    removeFluxv2
    removeArgoCD
    removeSCMM
    removeJenkins
    removeK8sResources
    cleanup
  else
    removeFluxv1 > /dev/null 2>&1 & spinner "Removing Flux V1"
    removeFluxv2 > /dev/null 2>&1 & spinner "Removing Flux V2"
    removeArgoCD > /dev/null 2>&1 & spinner "Removing ArgoCD"
    removeSCMM > /dev/null 2>&1 & spinner "Removing SCM-Manager"
    removeJenkins > /dev/null 2>&1 & spinner "Removing Jenkins"
    removeK8sResources > /dev/null 2>&1 & spinner "Removing other K8s Resources"
    cleanup > /dev/null 2>&1 & spinner "Cleaning up"
  fi

  confirm 'Remove Jenkins agent workspace as well? (tmp/k8s-gitops-playground-jenkins-agent)' 'y/n [n]' && rm -rf /tmp/k8s-gitops-playground-jenkins-agent
}

function printUsage()
{
    echo "This script will remove all k8s-resources which were installed via the apply.sh script."
    echo ""
    printParameters
    echo ""
}

function printParameters() {
    echo "The following parameters are valid"
    echo
    echo "-h | --help     >> Help screen"
    echo
    echo "-d | --debug    >> Debug output"
}

COMMANDS=$(getopt \
                -o hd \
                --long help,debug \
                -- "$@")

if [ $? != 0 ] ; then echo "Terminating..." >&2 ; exit 1 ; fi

eval set -- "$COMMANDS"

DEBUG=false
while true; do
  case "$1" in
    -h | --help     ) printUsage; exit 0 ;;
    -d | --debug    ) DEBUG=true; shift ;;
    --              ) shift; break ;;
    *               ) break ;;
  esac
done

confirm "Removing gitops playground from kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
  exit 0

main $DEBUG