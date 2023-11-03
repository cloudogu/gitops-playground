#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

BASEDIR=$(dirname $0)
export BASEDIR
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
export ABSOLUTE_BASEDIR
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"
export PLAYGROUND_DIR

# When updating, update in ApplicationConfigurator.groovy as well
SPRING_BOOT_HELM_CHART_COMMIT=0.3.2
K8S_VERSION=1.25.4

source ${ABSOLUTE_BASEDIR}/utils.sh
source ${ABSOLUTE_BASEDIR}/jenkins/init-jenkins.sh
source ${ABSOLUTE_BASEDIR}/scm-manager/init-scmm.sh

INTERNAL_SCMM=true
INTERNAL_JENKINS=true
INTERNAL_REGISTRY=true
# When running in k3d, connection between SCMM <-> Jenkins must be via k8s services, because external "localhost"
# addresses will not work
JENKINS_URL_FOR_SCMM="http://jenkins"
SCMM_URL_FOR_JENKINS="http://scmm-scm-manager/scm"

SPRING_BOOT_HELM_CHART_REPO=${SPRING_BOOT_HELM_CHART_REPO:-'https://github.com/cloudogu/spring-boot-helm-chart.git'}
GITOPS_BUILD_LIB_REPO=${GITOPS_BUILD_LIB_REPO:-'https://github.com/cloudogu/gitops-build-lib.git'}
CES_BUILD_LIB_REPO=${CES_BUILD_LIB_REPO:-'https://github.com/cloudogu/ces-build-lib.git'}

JENKINS_PLUGIN_FOLDER=${JENKINS_PLUGIN_FOLDER:-''}

function main() {
  readParameters "$@"

  if [[ $ASSUME_YES == false ]]; then
    confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
      # Return error here to avoid get correct state when used with kubectl
      exit 1
  fi

  if [[ $TRACE == true ]]; then
    set -x
    # Trace without debug does not make to much sense, as the spinner spams the output
    DEBUG=true
  fi

  # The - avoids "unbound variable", because it expands to empty string if unset
  if [[ -n "${KUBERNETES_SERVICE_HOST-}" ]]; then
    RUNNING_INSIDE_K8S=true
  else
    RUNNING_INSIDE_K8S=false
  fi

  CLUSTER_BIND_ADDRESS=$(findClusterBindAddress)

  export ORIG_NAME_PREFIX="$NAME_PREFIX"
  if [[ -n "${NAME_PREFIX}" ]]; then
    # Name-prefix should always end with '-'
    NAME_PREFIX="${NAME_PREFIX}-"
  fi
  export NAME_PREFIX_ENVIRONMENT_VARS="$ORIG_NAME_PREFIX"
  if [ -n "$NAME_PREFIX_ENVIRONMENT_VARS" ]; then
      NAME_PREFIX_ENVIRONMENT_VARS="${NAME_PREFIX_ENVIRONMENT_VARS^^}_"
  fi

  if [[ $INSECURE == true ]]; then
    CURL_HOME="${PLAYGROUND_DIR}"
    export CURL_HOME
    export GIT_SSL_NO_VERIFY=1
  fi

  if [[ -n "${SCMM_URL}" ]]; then
    INTERNAL_SCMM=false
    # We can't use internal kubernetes services in this scenario
    SCMM_URL_FOR_JENKINS=${SCMM_URL}
  elif [[ $RUNNING_INSIDE_K8S == true ]]; then
    SCMM_URL="$(createUrl "scmm-scm-manager.default.svc.cluster.local" "80")/scm"
  else
    local scmmPortFromValuesYaml="$(grep 'nodePort:' "${PLAYGROUND_DIR}"/scm-manager/values.yaml | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')"
    SCMM_URL="$(createUrl "${CLUSTER_BIND_ADDRESS}" "${scmmPortFromValuesYaml}")/scm"
  fi

  if [[ -n "${JENKINS_URL}" ]]; then
    INTERNAL_JENKINS=false
    # We can't use internal kubernetes services in this scenario
    JENKINS_URL_FOR_SCMM=${JENKINS_URL}
  elif [[ $RUNNING_INSIDE_K8S == true ]]; then
    JENKINS_URL=$(createUrl "jenkins.default.svc.cluster.local" "80")
  else
    local jenkinsPortFromValuesYaml="$(grep 'nodePort:' "${PLAYGROUND_DIR}"/jenkins/values.yaml | grep nodePort | tail -n1 | cut -f2 -d':' | tr -d '[:space:]')"
    JENKINS_URL=$(createUrl "${CLUSTER_BIND_ADDRESS}" "${jenkinsPortFromValuesYaml}")
  fi

  if [[ -z "${REGISTRY_URL}" ]]; then
    local registryPort
    registryPort='30000'
    if [[ -n "${INTERNAL_REGISTRY_PORT}" ]]; then
      registryPort="${INTERNAL_REGISTRY_PORT}"
    fi
    # Internal Docker registry must be on localhost. Otherwise docker will use HTTPS, leading to errors on docker push
    # in the example application's Jenkins Jobs.
    # Both setting up HTTPS or allowing insecure registry via daemon.json makes the playground difficult to use.
    # So, always use localhost.
    # Allow overriding the port, in case multiple playground instance run on a single host in different k3d clusters.
    REGISTRY_URL="localhost:${registryPort}"
    REGISTRY_PATH=""
  else
    INTERNAL_REGISTRY=false
  fi

  checkPrerequisites

  if [[ $DEBUG != true ]]; then
    backgroundLogFile=$(mktemp /tmp/playground-log-XXXXXXXXX)
    echo "Full log output is appended to ${backgroundLogFile}"
  fi

  if [[ "$DESTROY" != true ]] && [[ "$OUTPUT_CONFIG_FILE" != true ]]; then
    evalWithSpinner "Basic setup & configuring registry..." applyBasicK8sResources

    initSCMMVars
    evalWithSpinner "Starting SCM-Manager..." initSCMM

    initJenkins
  fi

  if [[ $TRACE == true ]]; then
    # Not longer print every command from here. Not needed for groovy and the welcome screen.
    set +x
  fi

  if [[ "$OUTPUT_CONFIG_FILE" != true ]]; then
    # call our groovy cli and pass in all params
    evalWithSpinner "Running apply-ng..." "$PLAYGROUND_DIR/scripts/apply-ng.sh" "$@"
    printWelcomeScreen
  else
    # we don't want the spinner hiding the output
    "$PLAYGROUND_DIR/scripts/apply-ng.sh" "$@"
  fi
}

function findClusterBindAddress() {
  local potentialClusterBindAddress
  local localAddress
  
  # Use an internal IP to contact Jenkins and SCMM
  # For k3d this is either the host's IP or the IP address of the k3d API server's container IP (when --bind-localhost=false)
  # Note that this might return multiple InternalIP (IPV4 and IPV6) - we assume the first one is IPV4 (break after first)
  potentialClusterBindAddress="$(kubectl get "$(waitForNode)" \
          --template='{{range .status.addresses}}{{ if eq .type "InternalIP" }}{{.address}}{{break}}{{end}}{{end}}')"

  localAddress="$(ip route get 1 | sed -n 's/^.*src \([0-9.]*\) .*$/\1/p')"

  # Check if we can use localhost instead of the external address
  # This address is later printed on the welcome screen, where localhost is much more constant and intuitive as the 
  # the external address. Also Jenkins notifications only work on localhost, not external addresses.
  # Note that this will only work when executed as a script locally, or in a container with --net=host.
  # When executing via kubectl run, this will still output the potentialClusterBindAddress.
  if [[ "${localAddress}" == "${potentialClusterBindAddress}" ]]; then 
    echo "localhost" 
  else 
    echo "${potentialClusterBindAddress}"
  fi
}

function waitForNode() {
  # With TLDR command from readme "kubectl get node" might be executed right after cluster start, where no nodes are 
  # returned, resulting in 'error: the server doesn't have a resource type ""'
  local nodes=""
  while [ -z "${nodes}" ]; do
    nodes=$(kubectl get node -oname)
    [ -z "${nodes}" ] && sleep 1
  done
  # Return first node
  kubectl get node -oname | head -n1
}

function evalWithSpinner() {

  spinnerOutput="${1}"
  shift
  function_args=("$@")

  commandToEval="$(printf "'%s' " "${function_args[@]}")"

  if [[ $DEBUG == true ]]; then
    eval "$commandToEval"
  else
    eval "$commandToEval" >>"${backgroundLogFile}" 2>&1 &
    spinner "${spinnerOutput}"
  fi
}

function checkPrerequisites() {
  if [[ ${INTERNAL_SCMM} == false || ${INTERNAL_JENKINS} == false ]]; then
    if [[ ${INTERNAL_SCMM} == true || ${INTERNAL_JENKINS} == true ]]; then
      error "When setting JENKINS_URL, SCMM_URL must also be set and the other way round."
      exit 1
    fi
  fi
}

function applyBasicK8sResources() {
  kubectl create namespace "${NAME_PREFIX}argocd" || true
  kubectl create namespace "${NAME_PREFIX}example-apps-production" || true
  kubectl create namespace "${NAME_PREFIX}example-apps-staging" || true
  kubectl create namespace "${NAME_PREFIX}monitoring" || true
  kubectl create namespace "${NAME_PREFIX}secrets" || true

  createSecrets

  # Apply ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.
  # TODO try to extract it from the monitoring helm-chart, so we don't have to maintain the version twice
  kubectl apply -f https://raw.githubusercontent.com/prometheus-operator/kube-prometheus/v0.9.0/manifests/setup/prometheus-operator-0servicemonitorCustomResourceDefinition.yaml
  
  initRegistry
}

function initRegistry() {
  if [[ "${INTERNAL_REGISTRY}" == true ]]; then
    helm repo add stable https://charts.helm.sh/stable
    helm repo update
    # We need a hostPort in order to work around our builds running on the host's docker daemon.
    # So here, a ClusterIP is not enough
    # Registry runs without auth, so don't expose as LB!
    helm upgrade -i docker-registry --version 1.9.4 stable/docker-registry -n default \
      --set service.nodePort=30000 --set service.type=NodePort
      
    if [[ -n "${INTERNAL_REGISTRY_PORT}" ]]; then
      # Add additional node port
      # 30000 is needed as a static by docker via port mapping of k3d, e.g. 32769 -> 30000 on server-0 container
      # See "-p 30000" in init-cluster.sh
      # e.g 32769 is needed so the kubelet can access the image inside the server-0 container
      kubectl create service nodeport docker-registry-internal-port --tcp=5000 --node-port ${INTERNAL_REGISTRY_PORT} -n default --dry-run=client -oyaml | kubectl apply -f-
    fi
  fi
}

function initJenkins() {
  if [[ ${INTERNAL_JENKINS} == true ]]; then
    deployJenkinsCommand=(deployLocalJenkins "${SET_USERNAME}" "${SET_PASSWORD}" "${REMOTE_CLUSTER}" "${JENKINS_URL}")
    evalWithSpinner "Deploying Jenkins..." "${deployJenkinsCommand[@]}"

    setExternalHostnameIfNecessary "JENKINS" "jenkins" "default"

    JENKINS_USERNAME="${SET_USERNAME}"
    JENKINS_PASSWORD="${SET_PASSWORD}"
  fi

  configureJenkinsCommand=(configureJenkins "${JENKINS_URL}" "${JENKINS_USERNAME}" "${JENKINS_PASSWORD}"
    "${SCMM_URL_FOR_JENKINS}" "${SCMM_PASSWORD}" "${REGISTRY_URL}"
    "${REGISTRY_PATH}" "${REGISTRY_USERNAME}" "${REGISTRY_PASSWORD}"
    "${INSTALL_ARGOCD}" "${JENKINS_METRICS_USERNAME}" "${JENKINS_METRICS_PASSWORD}")

  evalWithSpinner "Configuring Jenkins..." "${configureJenkinsCommand[@]}"
}

function initSCMMVars() {
  if [[ ${INTERNAL_SCMM} == true ]]; then
    SCMM_USERNAME=${SET_USERNAME}
    SCMM_PASSWORD=${SET_PASSWORD}
  fi

  # Those are set here, because the "initSCMM" methods might be running in a background process (to display the spinner only)
  SCMM_HOST=$(getHost "${SCMM_URL}")
  SCMM_PROTOCOL=$(getProtocol "${SCMM_URL}")
}

function initSCMM() {
  if [[ ${INTERNAL_SCMM} == true ]]; then
    deployLocalScmmManager "${REMOTE_CLUSTER}" "${SET_USERNAME}" "${SET_PASSWORD}"
  fi

  setExternalHostnameIfNecessary 'SCMM' 'scmm-scm-manager' 'default'
  [[ "${SCMM_URL}" != *scm ]] && SCMM_URL=${SCMM_URL}/scm

  # When running in k3d, BASE_URL must be the internal URL. Otherwise webhooks from SCMM->Jenkins will fail, as
  # They contain Repository URLs create with BASE_URL. Jenkins uses the internal URL for repos. So match is only
  # successful, when SCM also sends the Repo URLs using the internal URL
  configureScmmManager "${SCMM_USERNAME}" "${SCMM_PASSWORD}" "${SCMM_URL}" "${JENKINS_URL_FOR_SCMM}" \
    "${SCMM_URL_FOR_JENKINS}" "${INTERNAL_SCMM}" "${INSTALL_ARGOCD}"

  pushHelmChartRepo "3rd-party-dependencies/spring-boot-helm-chart"
  pushHelmChartRepoWithDependency "3rd-party-dependencies/spring-boot-helm-chart-with-dependency"
  pushRepoMirror "${GITOPS_BUILD_LIB_REPO}" "3rd-party-dependencies/gitops-build-lib"
  pushRepoMirror "${CES_BUILD_LIB_REPO}" "3rd-party-dependencies/ces-build-lib" 'develop'
}

function setExternalHostnameIfNecessary() {
  local variablePrefix="$1"
  local serviceName="$2"
  local namespace="$3"

  # :-} expands to empty string, e.g. vor INTERNAL_ARGO which does not exist.
  # This only works when checking for != false 😬
  if [[ $REMOTE_CLUSTER == true && "$(eval echo "\${INTERNAL_${variablePrefix}:-}")" != 'false' ]]; then
    # Update SCMM_URL or JENKINS_URL or ARGOCD_URL
    # Only if apps are not external
    # Our apps are configured to use port 80 on remote clusters
    # Argo forwards to HTTPS so simply use HTTP here
    declare -g "${variablePrefix}_URL"="http://$(getExternalIP "${serviceName}" "${namespace}")"
  fi
}

function createSecrets() {
  createSecret gitops-scmm --from-literal="USERNAME=${NAME_PREFIX}gitops" --from-literal=PASSWORD=$SET_PASSWORD -n default
}

function createSecret() {
  kubectl create secret generic "$@" --dry-run=client -oyaml | kubectl apply -f-
}

function pushHelmChartRepo() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)
  git clone -n "${SPRING_BOOT_HELM_CHART_REPO}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${SPRING_BOOT_HELM_CHART_COMMIT} --quiet

    # Create a defined version to use in demo applications
    git tag 1.0.0

    git branch --quiet -d main
    git checkout --quiet -b main

    waitForScmManager
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet --force
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function pushHelmChartRepoWithDependency() {
  TARGET_REPO_SCMM="$1"

  TMP_REPO=$(mktemp -d)
  git clone -n "${SPRING_BOOT_HELM_CHART_REPO}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    # Checkout a defined commit in order to get a deterministic result
    git checkout ${SPRING_BOOT_HELM_CHART_COMMIT} --quiet

    # Create a defined version to use in demo applications
    git tag 1.0.0

    git branch --quiet -d main
    git checkout --quiet -b main

    echo "dependencies:
- name: podinfo
  version: \"5.2.0\"
  repository: \"https://stefanprodan.github.io/podinfo\"" >>./Chart.yaml

    git commit -a -m "Added dependency" --quiet

    waitForScmManager
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" HEAD:main --force --quiet
    git push "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" refs/tags/1.0.0 --quiet --force
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}"
}

function pushRepoMirror() {
  SOURCE_REPO_URL="$1"
  TARGET_REPO_SCMM="$2"
  DEFAULT_BRANCH="${3:-main}"

  TMP_REPO=$(mktemp -d)
  git clone --bare "${SOURCE_REPO_URL}" "${TMP_REPO}" --quiet
  (
    cd "${TMP_REPO}"
    waitForScmManager
    git push --mirror "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" --force --quiet
  )

  rm -rf "${TMP_REPO}"

  setDefaultBranch "${TARGET_REPO_SCMM}" "${DEFAULT_BRANCH}"
}

function setDefaultBranch() {
  TARGET_REPO_SCMM="$1"
  DEFAULT_BRANCH="${2:-main}"

  curl -s -L -X PUT -H 'Content-Type: application/vnd.scmm-gitConfig+json' \
    --data-raw "{\"defaultBranch\":\"${DEFAULT_BRANCH}\"}" \
    "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/api/v2/config/git/${TARGET_REPO_SCMM}"
}

function createUrl() {
  local hostname="$1"
  local port="$2"

  if [[ -z "${port}" ]]; then
    error "No port found for hostname ${hostname}"
    exit 1
  fi

  # Argo forwards to HTTPS so simply use HTTP here
  echo -n "http://${hostname}"
  echo -n ":${port}"
  #  [[ "${port}" != 80 && "${port}" != 443 ]] && echo -n ":${port}"
}

function printWelcomeScreen() {

  echo
  echo
  echo    "|----------------------------------------------------------------------------------------------|"
  echo    "|                     ☁️  Welcome to the GitOps playground by Cloudogu! ☁️                       |"
  echo    "|----------------------------------------------------------------------------------------------|"
  echo    "|"
  echo    "| 📖 Please find the URLs of the individual applications in our README:"
  echo -e "| \e[32mhttps://github.com/cloudogu/gitops-playground/blob/main/README.md#table-of-contents\e[0m"
  echo    "|"
  echo -e "| \e[33m▶️\e[0m A good starting point might also be the services or ingresses inside your cluster: "
  echo -e "| \e[32mkubectl get svc -A\e[0m"
  echo -e "| Or (depending on your config)"
  echo -e "| \e[32mkubectl get ing -A\e[0m"
  echo    "|"
  echo -e "| \e[33m⏳\e[0mPlease be aware, Jenkins and Argo CD may take some time to build and deploy all apps."
  echo    "|----------------------------------------------------------------------------------------------|"
}

function printUsage() {
  runGroovy '--help'
}

readParameters() {
  COMMANDS=$(getopt \
    -o hdxyc \
    --long help,config-file:,config-map:,output-config-file,destroy,argocd,argocd-url:,debug,remote,username:,password:,jenkins-url:,jenkins-username:,jenkins-password:,jenkins-metrics-username:,jenkins-metrics-password:,registry-url:,registry-path:,registry-username:,registry-password:,internal-registry-port:,scmm-url:,scmm-username:,scmm-password:,kubectl-image:,helm-image:,kubeval-image:,helmkubeval-image:,yamllint-image:,grafana-url:,grafana-image:,grafana-sidecar-image:,prometheus-image:,prometheus-operator-image:,prometheus-config-reloader-image:,external-secrets-image:,external-secrets-certcontroller-image:,external-secrets-webhook-image:,vault-url:,vault-image:,nginx-image:,trace,insecure,yes,skip-helm-update,metrics,monitoring,mailhog-url:,vault:,petclinic-base-domain:,nginx-base-domain:,name-prefix: \
    -- "$@")
  
  if [ $? != 0 ]; then
    echo "Terminating..." >&2
    exit 1
  fi
  
  eval set -- "$COMMANDS"
  
  DEBUG=false
  INSTALL_ARGOCD=false
  REMOTE_CLUSTER=false
  SET_USERNAME="admin"
  SET_PASSWORD="admin"
  JENKINS_URL=""
  JENKINS_USERNAME=""
  JENKINS_PASSWORD=""
  JENKINS_METRICS_USERNAME="metrics"
  JENKINS_METRICS_PASSWORD="metrics"
  REGISTRY_URL=""
  REGISTRY_PATH=""
  REGISTRY_USERNAME=""
  REGISTRY_PASSWORD=""
  INTERNAL_REGISTRY_PORT=""
  SCMM_URL=""
  SCMM_USERNAME=""
  SCMM_PASSWORD=""
  INSECURE=false
  TRACE=false
  ASSUME_YES=false
  DESTROY=false
  OUTPUT_CONFIG_FILE=false
  NAME_PREFIX=""

  while true; do
    case "$1" in
      -h | --help          ) printUsage; exit 0 ;;
      --argocd             ) INSTALL_ARGOCD=true; shift ;;
      --argocd-url         ) shift 2 ;; # Ignore, used in groovy only
      --remote             ) REMOTE_CLUSTER=true; shift ;;
      --jenkins-url        ) JENKINS_URL="$2"; shift 2 ;;
      --jenkins-username   ) JENKINS_USERNAME="$2"; shift 2 ;;
      --jenkins-password   ) JENKINS_PASSWORD="$2"; shift 2 ;;
      --jenkins-metrics-username   ) JENKINS_METRICS_USERNAME="$2"; shift 2 ;;
      --jenkins-metrics-password   ) JENKINS_METRICS_PASSWORD="$2"; shift 2 ;;
      --registry-url       ) REGISTRY_URL="$2"; shift 2 ;;
      --registry-path      ) REGISTRY_PATH="$2"; shift 2 ;;
      --registry-username  ) REGISTRY_USERNAME="$2"; shift 2 ;;
      --registry-password  ) REGISTRY_PASSWORD="$2"; shift 2 ;;
      --internal-registry-port ) INTERNAL_REGISTRY_PORT="$2"; shift 2 ;;
      --scmm-url           ) SCMM_URL="$2"; shift 2 ;;
      --scmm-username      ) SCMM_USERNAME="$2"; shift 2 ;;
      --scmm-password      ) SCMM_PASSWORD="$2"; shift 2 ;;
      --kubectl-image      ) shift 2;; # Ignore, used in groovy only
      --helm-image         ) shift 2;; # Ignore, used in groovy only
      --kubeval-image      ) shift 2;; # Ignore, used in groovy only
      --helmkubeval-image  ) shift 2;; # Ignore, used in groovy only
      --yamllint-image     ) shift 2;; # Ignore, used in groovy only
      --grafana-url        ) shift 2;; # Ignore, used in groovy only
      --grafana-image      ) shift 2;; # Ignore, used in groovy only
      --grafana-sidecar-image ) shift 2;; # Ignore, used in groovy only
      --prometheus-image ) shift 2;; # Ignore, used in groovy only
      --prometheus-operator-image ) shift 2;; # Ignore, used in groovy only
      --prometheus-config-reloader-image ) shift 2;; # Ignore, used in groovy only
      --external-secrets-image ) shift 2;; # Ignore, used in groovy only
      --external-secrets-certcontroller-image ) shift 2;; # Ignore, used in groovy only
      --external-secrets-webhook-image ) shift 2;; # Ignore, used in groovy only
      --vault-url          ) shift 2;; # Ignore, used in groovy only
      --vault-image        ) shift 2;; # Ignore, used in groovy only
      --nginx-image        ) shift 2;; # Ignore, used in groovy only
      --insecure           ) INSECURE=true; shift ;;
      --username           ) SET_USERNAME="$2"; shift 2 ;;
      --password           ) SET_PASSWORD="$2"; shift 2 ;;
      --name-prefix        ) NAME_PREFIX="$2"; shift 2 ;;
      -d | --debug         ) DEBUG=true; shift ;;
      -x | --trace         ) TRACE=true; shift ;;
      -y | --yes           ) ASSUME_YES=true; shift ;;
      --metrics | --monitoring ) shift;; # Ignore, used in groovy only
      --mail               ) shift 2;; # Ignore, used in groovy only
      --mailhog-url        ) shift 2;; # Ignore, used in groovy only
      --vault              ) shift 2;; # Ignore, used in groovy only
      --petclinic-base-domain ) shift 2;; # Ignore, used in groovy only
      --nginx-base-domain  ) shift 2;; # Ignore, used in groovy only
      --destroy            ) DESTROY=true; shift;;
      --config-file        ) shift;; # Ignore, used in groovy only
      --config-map         ) shift;; # Ignore, used in groovy only
      --output-config-file ) OUTPUT_CONFIG_FILE=true; shift;;
      --                   ) shift; break ;;
    *) break ;;
    esac
  done
}

main "$@"
