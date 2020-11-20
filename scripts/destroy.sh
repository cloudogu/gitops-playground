#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail
#set -x

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"

source ${ABSOLUTE_BASEDIR}/utils.sh

function main() {
  # The following line is needed because the cr "fluxv2-kustomizer" has a finalizer set which can lead to a deadlock while deleting
  # https://stackoverflow.com/a/52012367
  kubectl patch kustomization fluxv2-kustomizer -p '{"metadata":{"finalizers":[]}}' --type=merge -n fluxv2 || true
  kubectl delete -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-kustomization.yaml || true
  kubectl delete -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-gitrepository.yaml || true
  kubectl delete -f fluxv2/clusters/k8s-gitops-playground/fluxv2/gotk-components.yaml || true

  helm delete scmm -n default || true
  helm delete jenkins -n default || true
  helm delete flux-operator -n default || true
  helm delete helm-operator -n default || true
  helm delete docker-registry -n default || true

  kubectl delete -f jenkins/resources || true
  kubectl delete -f scm-manager/resources || true
  kubectl delete secret gitops-scmm || true

  kubectl delete -f k8s-namespaces/ || true

  kubectl delete -f argocd/resources || true
  helm delete argocd -n default || true

  #cleanup
  kubectl delete crd/helmreleases.helm.fluxcd.io || true
  kubectl delete customresourcedefinition.apiextensions.k8s.io/applications.argoproj.io || true
  kubectl delete customresourcedefinition.apiextensions.k8s.io/appprojects.argoproj.io || true
  kubectl delete apiservice.apiregistration.k8s.io/v1alpha1.argoproj.io || true
  kubectl delete appproject.argoproj.io/default || true

  confirm "" 'Remove Jenkins agent workspace in this folder as well? y/n [n]' && rm -rf /tmp/k8s-gitops-playground-jenkins-agent
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
    echo "-h --help   - Help screen"
    echo "-d --debug  - Debug output"
}

confirm "Removing gitops playground from kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
exit 0

main "$@"

#main "$@" > /dev/null 2>&1 & spinner "Removing all Cloudogu GitOps Playground resources..."
