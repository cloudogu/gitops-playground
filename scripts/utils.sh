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

function extractHost() {
    echo "$1" | grep -oP 'http[s]?://\K[^:]*'
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

    if wait -n $pid; then
      echo " [ok] $info"
    else
      echo " [failed] $info"
      exit 1
    fi
}

function error() {
     echo "$@" 1>&2; 
}

# Entry point for the new generation of our apply script, written in groovy
function runGroovy() {
  ADDITIONAL_OPTIONS=""
  [ -z "${DEBUG-}" ] || ADDITIONAL_OPTIONS+=" --debug"
  [ -z "${TRACE-}" ] || ADDITIONAL_OPTIONS+=" --trace"
  [ -z "${INSECURE-}" ] || ADDITIONAL_OPTIONS+=" --insecure"

  if [[ -f "$PLAYGROUND_DIR/apply-ng" ]]; then
      "$PLAYGROUND_DIR"/apply-ng "$@" $ADDITIONAL_OPTIONS
  else
      echo "apply-ng binary not found, calling groovy scripts"
      groovy --classpath "$PLAYGROUND_DIR"/src/main/groovy \
        "$PLAYGROUND_DIR"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy "$@" $ADDITIONAL_OPTIONS
  fi
}

function groovy() {
  # We don't need the groovy "binary" (script) to start, because the gitops-playground.jar already contains groovy-all.

  # Set params like startGroovy does (which is called by the "groovy" script)
  # See https://github.com/apache/groovy/blob/master/src/bin/startGroovy
  java \
    -classpath "$PLAYGROUND_DIR"/gitops-playground.jar \
    org.codehaus.groovy.tools.GroovyStarter \
          --main groovy.ui.GroovyMain \
           "$@"
}
