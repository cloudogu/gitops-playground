#!/usr/bin/env bash

# Note that this is also used in scripts/get-remote-url
# Move it to get-remote-url, once setExternalHostnameIfNecessary() is no longer needed in init-scmm and init-jenkins.sh
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

function setExternalHostnameIfNecessary() {
  local variablePrefix="$1"
  local serviceName="$2"
  local namespace="$3"

  if [[ $REMOTE_CLUSTER == true ]]; then
    # Update SCMM_URL or JENKINS_URL
    # Only if apps are not external
    # Our apps are configured to use port 80 on remote clusters
    declare -g "${variablePrefix}_URL"="http://$(getExternalIP "${serviceName}" "${namespace}")"
  fi
}