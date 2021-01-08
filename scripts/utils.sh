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

# getExternalIP servicename namespace
function getExternalIP() {
  external_ip=""
  while [ -z $external_ip ]; do
#    echo "Waiting for end point..."
    external_ip=$(kubectl -n $2 get svc $1 --template="{{range .status.loadBalancer.ingress}}{{.ip}}{{end}}")
    [ -z "$external_ip" ] && sleep 10
  done
  echo $external_ip
}

function bcryptPassword() {
  echo $(htpasswd -bnBC 10 "" $1 | tr -d ':\n')
}

function spinner() {
    local info="$1"
    local pid=$!
    local delay=0.1
    local spinstr='|/-\'
    while kill -0 $pid 2> /dev/null; do
        local temp=${spinstr#?}
        printf " [%c]  $info" "$spinstr"
        local spinstr=$temp${spinstr%"$temp"}
        sleep $delay
        local reset="\b\b\b\b\b"
        for ((i=1; i<=$(echo $info | wc -c); i++)); do
            reset+="\b"
        done
        printf $reset
    done
    echo " [ok] $info"
}