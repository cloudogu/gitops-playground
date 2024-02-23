#!/usr/bin/env bash

function confirm() {
  # shellcheck disable=SC2145
  # - the line break between args is intended here!
  printf "%s\n" "${@:-Are you sure? [y/N]} "
  
  read -r response
  case "$response" in
  [yY][eE][sS] | [yY])
    true
    ;;
  *)
    false
    ;;
  esac
}

function getExternalIP() {
  servicename=$1
  namespace=$2
  
  external_ip=""
  while [ -z $external_ip ]; do
    external_ip=$(kubectl -n ${namespace} get svc ${servicename} -o=jsonpath="{.status.loadBalancer.ingress[0]['hostname','ip']}")
    [ -z "$external_ip" ] && sleep 10
  done
  echo $external_ip
}

function createSecret() {
  kubectl create secret generic "$@" --dry-run=client -oyaml | kubectl apply -f-
}

function extractHost() {
    echo "$1" | awk -F[/:] '{print $4}'
}

function injectSubdomain() {
    local BASE_URL="$1"
    local SUBDOMAIN="$2"

    if [[ "$BASE_URL" =~ ^http:// ]]; then
        echo "${BASE_URL/http:\/\//http://${SUBDOMAIN}.}"
    elif [[ "$BASE_URL" =~ ^https:// ]]; then
        echo "${BASE_URL/https:\/\//https://${SUBDOMAIN}.}"
    else
        echo "Invalid BASE URL: ${BASE_URL}. It should start with either http:// or https://"
        return 1
    fi
}

function setExternalHostnameIfNecessary() {
  local variablePrefix="$1"
  local serviceName="$2"
  local namespace="$3"

  # :-} expands to empty string, e.g. for INTERNAL_ARGO which does not exist.
  # This only works when checking for != false 😬
  if [[ $REMOTE_CLUSTER == true && "$(eval echo "\${INTERNAL_${variablePrefix}:-}")" != 'false' ]]; then
    # Update SCMM_URL or JENKINS_URL or ARGOCD_URL
    # Only if apps are not external
    # Our apps are configured to use port 80 on remote clusters
    # Argo forwards to HTTPS so simply use HTTP here
    declare -g "${variablePrefix}_URL"="http://$(getExternalIP "${serviceName}" "${namespace}")"
  fi
}

function error() {
    # Print to stderr in red
    echo -e "\033[31m$@\033[0m" 1>&2;
}