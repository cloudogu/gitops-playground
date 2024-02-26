#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

ABSOLUTE_BASEDIR="$(cd "$(dirname $0)" && pwd)"
PLAYGROUND_DIR="$(cd ${ABSOLUTE_BASEDIR} && cd .. && pwd)"

source ${ABSOLUTE_BASEDIR}/utils.sh

function main() {
  readParameters "$@"

  if [[ $ASSUME_YES == false ]]; then
    confirm "Applying gitops playground to kubernetes cluster: '$(kubectl config current-context)'." 'Continue? y/n [n]' ||
      # Return error here to avoid get correct state when used with kubectl
      exit 1
  fi

  if [[ $TRACE == true ]]; then
    set -x
  fi

  if [[ "$DESTROY" != true ]]; then
    # Apply ServiceMonitor CRD; Argo CD fails if it is not there. Chicken-egg-problem.
    # TODO make note next to helm chart version to also upgrade this
    kubectl apply -f https://raw.githubusercontent.com/prometheus-community/helm-charts/kube-prometheus-stack-42.0.3/charts/kube-prometheus-stack/crds/crd-servicemonitors.yaml
  fi
  
  # call our groovy cli and pass in all params
  runGroovy "$@"
  
  if [[ "$OUTPUT_CONFIG_FILE" != true ]]; then
      # Not longer print every command from here. Not needed for the welcome screen
      set +x
    printWelcomeScreen
  fi
}

# Entry point for the new generation of our apply script, written in groovy
function runGroovy() {
  if [[ -f "$PLAYGROUND_DIR/apply-ng" ]]; then
      "$PLAYGROUND_DIR"/apply-ng "$@"
  else
      groovy --classpath "$PLAYGROUND_DIR"/src/main/groovy \
        "$PLAYGROUND_DIR"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy "$@"
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
    --long help,config-file:,config-map:,output-config-file,destroy,argocd,argocd-url:,debug,remote,base-url:,username:,password:,jenkins-url:,jenkins-username:,jenkins-password:,jenkins-metrics-username:,jenkins-metrics-password:,registry-url:,registry-path:,registry-username:,registry-password:,internal-registry-port:,scmm-url:,scmm-username:,scmm-password:,kubectl-image:,helm-image:,kubeval-image:,helmkubeval-image:,yamllint-image:,grafana-url:,grafana-image:,grafana-sidecar-image:,prometheus-image:,prometheus-operator-image:,prometheus-config-reloader-image:,external-secrets-image:,external-secrets-certcontroller-image:,external-secrets-webhook-image:,vault-url:,vault-image:,nginx-image:,trace,insecure,yes,skip-helm-update,metrics,monitoring,grafana-email-from:,grafana-email-to:,argocd-email-from:,argocd-email-to-admin:,argocd-email-to-user:,mail,mailhog,mailhog-url:,smtp-address:,smtp-port:,smtp-user:,smtp-password:,vault:,petclinic-base-domain:,nginx-base-domain:,name-prefix:,ingress-nginx \
    -- "$@")
  
  if [ $? != 0 ]; then
    echo "Terminating..." >&2
    exit 1
  fi
  
  eval set -- "$COMMANDS"
  
  DEBUG=false
  INSTALL_ARGOCD=false
  REMOTE_CLUSTER=false
  BASE_URL=""
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
      --argocd-email-from     ) shift ;; # Ignore, used in groovy only
      --argocd-email-to-user  ) shift ;; # Ignore, used in groovy only
      --argocd-email-to-admin ) shift ;; # Ignore, used in groovy only
      --base-url           ) BASE_URL="$2"; shift 2 ;;
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
      --grafana-email-from ) shift ;; # Ignore, used in groovy only
      --grafana-email-to   ) shift ;; # Ignore, used in groovy only
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
      --mail               ) shift;; # Ignore, used in groovy only
      --mailhog            ) shift;; # Ignore, used in groovy only
      --mailhog-url        ) shift 2;; # Ignore, used in groovy only
      --smtp-address         ) shift ;; # Ignore, used in groovy only
      --smtp-port     ) shift ;; # Ignore, used in groovy only
      --smtp-user     ) shift ;; # Ignore, used in groovy only
      --smtp-password ) shift ;; # Ignore, used in groovy only
      --ingress-nginx      ) shift ;; # Ignore, used in groovy only
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
