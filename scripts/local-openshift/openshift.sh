#!/usr/bin/env bash
# Orchestrates the local CRC + Jenkins-in-k3d sandbox. See docs/deploy-local-openshift.md.
set -o errexit -o nounset -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

JENKINS_K3D_CLUSTER_NAME="gitops-playground-jenkins"
GOP_NAMESPACE="gop"
# CRC's router is reachable at the host's own IP on this machine's networking mode (see gop-jenkins-values.yaml),
# so it really would clash with k3d's default 80/443 on the same host. CRC itself is left on its OWN default
# ports though -- CRC's internal URL generation (OAuth issuer, the registry's token "realm") hardcodes 80/443
# and ignores `crc config set ingress-*-port` (confirmed: the registry's WWW-Authenticate realm always says
# ":443" regardless), so remapping CRC breaks `oc login`, image pushes, and any client that follows those
# self-referential URLs. k3d's ports are fully under our control, so k3d moves instead.
K3D_HTTP_PORT=8880
K3D_HTTPS_PORT=8843
# The GOP registry component (registry.active in gop-jenkins-values.yaml) always exposes itself on NodePort
# 30000 -- and example-app pipelines always push to "localhost:30000" (a convention hardcoded in the external
# gitops-build-lib/ces-build-lib, not something GOP's own config can override). Jenkins agents build/push via
# the HOST's mounted docker.sock, so that "localhost" is resolved by the HOST's own dockerd against its own
# loopback -- it must actually be reachable there, not just inside the cluster's pod network.
K3D_REGISTRY_PORT=30000
REGISTRY_ROUTE_HOST="default-route-openshift-image-registry.apps-crc.testing"
# The user's kubeconfig may have other real clusters as contexts/current-context -- always target CRC
# explicitly, never rely on whatever's currently selected. `crc-admin` is a certificate-auth context
# `crc start` sets up itself, simpler than `oc login -u kubeadmin -p ...` (no password lookup needed).
OC_CONTEXT="crc-admin"
DAEMON_JSON="/etc/docker/daemon.json"
# Jenkins (the heaviest piece) no longer runs inside CRC, so CRC itself needs less than the 16GB/6 CPUs the
# original all-in-one guide recommended. Override via env var if OpenShift is unstable/sluggish at this size,
# e.g. `CRC_MEMORY_MB=16384 make openshift-up`.
: "${CRC_MEMORY_MB:=12288}"
: "${CRC_CPUS:=4}"
# Headroom for the Jenkins k3d cluster (Jenkins controller + one build agent) plus the rest of the desktop.
RAM_HEADROOM_MB=4096
# Namespaces GOP's "full" profile pre-creates tools in; see docs/deploy-local-openshift.md §6.3. SCM-Manager
# isn't here -- it runs in the Jenkins k3d cluster now, see gop-jenkins-values.yaml. Vault's namespace is
# "secrets" (Config$SecretsSchema.namespace default), not "vault" -- that's only the route hostname.
PLATFORM_NAMESPACES=(argocd secrets monitoring registry cert-manager)
# CRC routes the Jenkins-in-k3d run needs to reach (add more if other CRC-hosted tools are referenced from a
# Jenkins job). SCM-Manager isn't here -- it's k3d-local now, reachable without this CoreDNS customization.
CRC_ROUTE_HOSTNAMES=(argocd vault grafana)

function main() {
  local cmd="${1:-}"
  case "${cmd}" in
    setup-host) setupHost ;;
    cleanup-host) cleanupHost ;;
    up) up ;;
    down) down ;;
    reset) reset ;;
    destroy) destroy ;;
    *) usage; exit 1 ;;
  esac
}

function usage() {
  cat <<'EOF'
Usage: openshift.sh <setup-host|cleanup-host|up|down|reset|destroy>

  setup-host    One-time, explicit: trust the CRC registry route in the host docker daemon. Asks for
                confirmation and shows the diff before touching /etc/docker/daemon.json.
  cleanup-host  Revert setup-host.
  up            Start CRC + the Jenkins k3d cluster and deploy GOP across both.
  down          Stop both clusters. Keeps all data.
  reset         Recreate the Jenkins k3d cluster and redeploy GOP; the CRC VM is left running as-is.
                Use this for the day-to-day test cycle.
  destroy       Delete both the CRC VM and the Jenkins k3d cluster entirely. Use occasionally to verify
                full reproducibility.
EOF
}

function hostIp() {
  ip route get 1.1.1.1 | awk '{for (i=1;i<=NF;i++) if ($i=="src") print $(i+1)}'
}

function confirm() {
  local reply
  read -r -p "${1:-Proceed?} [y/N] " reply
  [[ "${reply}" =~ ^[yY]([eE][sS])?$ ]]
}

# CRC boots by SSHing into the VM; under memory pressure (host swapping) that SSH step times out and
# `crc start` fails outright. Warn early instead of letting it fail deep into the boot.
function checkHostResources() {
  local availableMb requiredMb
  availableMb=$(($(awk '/^MemAvailable:/{print $2}' /proc/meminfo) / 1024))
  requiredMb=$((CRC_MEMORY_MB + RAM_HEADROOM_MB))
  if (( availableMb < requiredMb )); then
    echo "Warning: only ${availableMb}MB RAM available (MemAvailable), but CRC alone is configured for" \
         "${CRC_MEMORY_MB}MB, plus ~${RAM_HEADROOM_MB}MB recommended headroom for the Jenkins k3d cluster" \
         "and the rest of the desktop."
    echo "Low memory makes CRC's boot (it SSHes into the VM) flaky or causes it to time out and fail."
    confirm "Continue anyway?" || { echo "Aborted. Free up memory (close browsers/IDEs) and re-run."; exit 1; }
  fi
}

# ---- host setup/cleanup (Q12: no silent, systemwide changes) ----------------------------------------------

# Two hosts the HOST's own dockerd (used by Jenkins build agents via the mounted socket, so pushes execute in
# the HOST's own network namespace, not any pod's) needs to trust as insecure/HTTP: the CRC registry route
# (self-signed cert) and the k3d registry's host-IP address (confirmed live: example-app pipelines push
# there once fixJenkinsBakedUrls rewrites Jenkins' REGISTRY_URL away from the k3d-only "localhost" default --
# "localhost" itself needs no entry, Docker already exempts it, but a real LAN IP doesn't get that pass).
function insecureRegistryHosts() {
  echo "${REGISTRY_ROUTE_HOST}"
  echo "$(hostIp):${K3D_REGISTRY_PORT}"
}

function setupHost() {
  local -a hosts=()
  mapfile -t hosts < <(insecureRegistryHosts)

  local allTrusted="true" h
  if [[ -f "${DAEMON_JSON}" ]]; then
    for h in "${hosts[@]}"; do
      jq -e --arg h "${h}" '(.["insecure-registries"] // []) | index($h)' "${DAEMON_JSON}" >/dev/null 2>&1 || allTrusted="false"
    done
  else
    allTrusted="false"
  fi
  if [[ "${allTrusted}" == "true" ]]; then
    echo "${DAEMON_JSON} already trusts: ${hosts[*]}"
    return 0
  fi

  echo "This adds the following to insecure-registries in ${DAEMON_JSON} and restarts docker: ${hosts[*]}"
  echo "Needed so the host docker daemon (used by Jenkins build agents via the mounted socket) can push to"
  echo "the CRC image registry route and the k3d registry (via the host's LAN IP) without a trusted TLS cert."
  if [[ -f "${DAEMON_JSON}" ]]; then
    echo "Current ${DAEMON_JSON}:"
    cat "${DAEMON_JSON}"
  else
    echo "${DAEMON_JSON} does not exist yet and will be created."
  fi
  confirm "Apply this change?" || { echo "Aborted."; exit 1; }

  local tmp hostsJson
  hostsJson="$(printf '%s\n' "${hosts[@]}" | jq -R . | jq -s .)"
  tmp="$(mktemp)"
  if [[ -f "${DAEMON_JSON}" ]]; then
    jq --argjson add "${hostsJson}" '.["insecure-registries"] = (((.["insecure-registries"] // []) + $add) | unique)' "${DAEMON_JSON}" >"${tmp}"
  else
    jq -n --argjson add "${hostsJson}" '{"insecure-registries": $add}' >"${tmp}"
  fi
  sudo install -m 644 "${tmp}" "${DAEMON_JSON}"
  rm -f "${tmp}"
  sudo systemctl restart docker
  echo "Done."
}

function cleanupHost() {
  local -a hosts=()
  mapfile -t hosts < <(insecureRegistryHosts)

  local anyTrusted="false" h
  if [[ -f "${DAEMON_JSON}" ]]; then
    for h in "${hosts[@]}"; do
      jq -e --arg h "${h}" '(.["insecure-registries"] // []) | index($h)' "${DAEMON_JSON}" >/dev/null 2>&1 && anyTrusted="true"
    done
  fi
  if [[ "${anyTrusted}" == "false" ]]; then
    echo "${DAEMON_JSON} does not reference any of: ${hosts[*]}. Nothing to do."
    return 0
  fi

  echo "This removes the following from insecure-registries in ${DAEMON_JSON} and restarts docker: ${hosts[*]}"
  confirm "Apply this change?" || { echo "Aborted."; exit 1; }

  local tmp hostsJson
  hostsJson="$(printf '%s\n' "${hosts[@]}" | jq -R . | jq -s .)"
  tmp="$(mktemp)"
  jq --argjson remove "${hostsJson}" '.["insecure-registries"] -= $remove' "${DAEMON_JSON}" >"${tmp}"
  sudo install -m 644 "${tmp}" "${DAEMON_JSON}"
  rm -f "${tmp}"
  sudo systemctl restart docker
  echo "Done."
}

# ---- lifecycle ----------------------------------------------------------------------------------------------

function up() {
  checkHostResources
  startCrc
  fixAnyuidSeccompProfiles
  fixCrcRegistryTrust
  exposeRegistryRoute
  wipePlatformNamespaces
  ensureGopProject
  buildAndPushGopImage
  createJenkinsCluster
  deployJenkins
  deployPlatform
  fixJenkinsBakedUrls
}

function down() {
  crc stop || true
  k3d cluster stop "${JENKINS_K3D_CLUSTER_NAME}" || true
}

function reset() {
  k3d cluster delete "${JENKINS_K3D_CLUSTER_NAME}" || true
  ensureGopProject
  buildAndPushGopImage
  createJenkinsCluster
  deployJenkins
  deployPlatform
}

function destroy() {
  crc delete -f || true
  k3d cluster delete "${JENKINS_K3D_CLUSTER_NAME}" || true
}

# ---- steps ----------------------------------------------------------------------------------------------

function startCrc() {
  # Explicitly clear any override: an earlier iteration of this script remapped these (see the port comment
  # above), and `crc config set` persists across runs, so a stale 8880/8843 would otherwise stick. Note `crc
  # config set ingress-*-port` rejects values below 1024 (so it can't be used to spell out "80"/"443"
  # explicitly) -- `unset` is the only way back to CRC's real default.
  crc config unset ingress-http-port >/dev/null
  crc config unset ingress-https-port >/dev/null

  if [[ "$(crc status -o json 2>/dev/null | jq -r '.crcStatus // empty')" == "Running" ]]; then
    echo "CRC VM already running."
  else
    crc start --cpus "${CRC_CPUS}" --memory "${CRC_MEMORY_MB}" --disk-size 80
  fi
  eval "$(crc oc-env)"

  echo "Waiting for the OpenShift/OKD control plane to finish starting..."
  until [[ "$(crc status -o json 2>/dev/null | jq -r '.openshiftStatus // empty')" == "Running" ]]; do
    sleep 10
  done
}

# This CRC bundle's "anyuid" SCC has no `seccompProfiles` set, so it silently rejects any pod that sets the
# modern `securityContext.seccompProfile` field (e.g. Argo CD's redis/dex-server subcharts set
# `RuntimeDefault`) -- regardless of SCC group/user grants, since that's a separate check entirely. The
# resulting error ("unable to validate against any security context constraint") doesn't even list anyuid as
# a candidate, which makes this look like an RBAC/grant problem when it isn't one.
function fixAnyuidSeccompProfiles() {
  if [[ "$(oc --context="${OC_CONTEXT}" get scc anyuid -o jsonpath='{.seccompProfiles}')" != "" ]]; then
    return 0
  fi
  oc --context="${OC_CONTEXT}" patch scc anyuid --type=json -p '[{"op":"add","path":"/seccompProfiles","value":["*"]}]'
}

# CRC pods (e.g. the example-app Deployments Argo CD manages) can reach the k3d registry fine over the
# network via the host's LAN IP (confirmed live) -- but CRI-O, like Docker, refuses non-localhost registries
# over plain HTTP unless explicitly told they're insecure, and only picks up a NEW registries.conf.d entry
# after a reload (confirmed live: crictl pull kept trying HTTPS until "systemctl reload crio" ran). Mirrors
# what setupHost() already does for the HOST's dockerd, just on the CRC node's own container runtime.
function fixCrcRegistryTrust() {
  local target
  target="$(hostIp):${K3D_REGISTRY_PORT}"
  oc --context="${OC_CONTEXT}" debug node/crc -- chroot /host sh -c \
    "grep -qF '${target}' /etc/containers/registries.conf.d/999-k3d-registry.conf 2>/dev/null && exit 0
     printf '[[registry]]\nlocation = \"%s\"\ninsecure = true\n' '${target}' > /etc/containers/registries.conf.d/999-k3d-registry.conf
     systemctl reload crio"
}

function exposeRegistryRoute() {
  # "pvc":null clears any storage config left over from a previous, differently-configured CRC instance --
  # otherwise the operator gets stuck "Progressing" with both emptyDir and pvc storage set at once.
  oc --context="${OC_CONTEXT}" patch configs.imageregistry.operator.openshift.io/cluster --type merge \
    -p '{"spec":{"managementState":"Managed","storage":{"emptyDir":{},"pvc":null,"managementState":"Managed"}}}'
  oc --context="${OC_CONTEXT}" patch configs.imageregistry.operator.openshift.io/cluster --type merge \
    -p '{"spec":{"defaultRoute":true}}'

  # A storage-backend change (e.g. a switch away from a broken/unmountable PVC, confirmed via a CSI driver
  # missing error on this CRC bundle) makes the operator roll the registry Deployment onto a new pod with a
  # fresh, empty emptyDir. If buildAndPushGopImage() below pushes while that rollout is still in flight, the
  # push can land on the outgoing pod (or straddle the pod swap): OpenShift's Image/ImageStreamTag API objects
  # (etcd-backed) still get created, but the actual manifest/blob data never ends up on the pod that's left
  # running -- confirmed live: an ImageStreamTag existed with zero bytes under the running pod's /registry,
  # and the later pull failed "manifest unknown" even though "oc get imagestreamtag" showed it fine. Waiting
  # here for the rollout to fully settle avoids the race instead of debugging a phantom missing image later.
  oc --context="${OC_CONTEXT}" rollout status deployment/image-registry -n openshift-image-registry --timeout=180s
}

# Argo CD (via the cluster-resources GitOps sync) becomes a co-owner of fields on the objects GOP also
# deploys imperatively (Server-Side Apply field ownership). Once Argo CD has synced a tool once, a later
# `helm upgrade` for that same tool from a fresh GOP run conflicts with that ownership and fails outright
# (seen on scm-manager and registry). Deleting these namespaces before redeploying, every time, avoids that
# entirely instead of debugging which specific namespace is stale on a given run.
function wipePlatformNamespaces() {
  local oc=(oc --context="${OC_CONTEXT}")
  local ns
  for ns in "${PLATFORM_NAMESPACES[@]}"; do
    "${oc[@]}" delete namespace "${ns}" --ignore-not-found --wait=false
  done
  for ns in "${PLATFORM_NAMESPACES[@]}"; do
    until ! "${oc[@]}" get namespace "${ns}" >/dev/null 2>&1; do
      sleep 5
    done
  done
}

# The registry route rejects pushes into a namespace/project that doesn't exist yet (fails with "denied", not
# a clearer error) -- buildAndPushGopImage() needs this to exist before it runs, same as deployPlatform() does.
function ensureGopProject() {
  local oc=(oc --context="${OC_CONTEXT}")
  "${oc[@]}" get project "${GOP_NAMESPACE}" >/dev/null 2>&1 || "${oc[@]}" new-project "${GOP_NAMESPACE}"
}

# gop-values.yaml points the platform run at the internal registry's gop/gop:latest image, so it needs to
# exist there before the job that deploys the platform tries to pull it (the original manual doc had this as
# a separate prerequisite step; automated here so `make openshift-up`/`openshift-reset` are self-contained).
function buildAndPushGopImage() {
  docker buildx prune -f
  docker build "${REPO_ROOT}" -t local/gop
  docker login -u "$(oc --context="${OC_CONTEXT}" whoami)" -p "$(oc --context="${OC_CONTEXT}" whoami -t)" "${REGISTRY_ROUTE_HOST}"
  docker tag local/gop:latest "${REGISTRY_ROUTE_HOST}/gop/gop:latest"
  docker push "${REGISTRY_ROUTE_HOST}/gop/gop:latest"
}

function createJenkinsCluster() {
  # scripts/init-cluster.sh prompts interactively (and exits non-zero on "no") if the cluster already exists --
  # fine for its own direct/manual use, but `up` must stay non-interactive and idempotent like startCrc() above.
  # `reset` explicitly deletes the cluster first, so this only skips on repeated `up` runs.
  if k3d cluster list "${JENKINS_K3D_CLUSTER_NAME}" >/dev/null 2>&1; then
    # Exists, but may be stopped (or its containers killed, e.g. by the OOM killer under memory pressure) --
    # `cluster start` is a no-op if it's already running.
    echo "k3d cluster '${JENKINS_K3D_CLUSTER_NAME}' already exists, ensuring it's running."
    k3d cluster start "${JENKINS_K3D_CLUSTER_NAME}"
  else
    "${REPO_ROOT}/scripts/init-cluster.sh" \
      --cluster-name="${JENKINS_K3D_CLUSTER_NAME}" \
      --bind-ingress-host="$(hostIp)" \
      --bind-ingress-port="${K3D_HTTP_PORT}" \
      --bind-ingress-https-port="${K3D_HTTPS_PORT}"
    # init-cluster.sh binds the registry NodePort to the SAME address as ingress (--bind-ingress-host, here
    # the host's real LAN IP so CRC, a separate VM, can reach Jenkins/SCM-Manager) -- but the registry must
    # additionally be reachable at 127.0.0.1 (see K3D_REGISTRY_PORT comment above). Adding this as an extra
    # loadbalancer port mapping here, instead of changing --bind-ingress-host or init-cluster.sh itself, keeps
    # init-cluster.sh untouched (it's shared with the main, non-OpenShift `make cluster` flow).
    k3d cluster edit "${JENKINS_K3D_CLUSTER_NAME}" --port-add "127.0.0.1:${K3D_REGISTRY_PORT}:30000@loadbalancer"
  fi
  configureJenkinsClusterDns
}

# apps-crc.testing only resolves on the Ubuntu host (CRC manages it via /etc/hosts there), not inside k3d's
# pod network -- teach the cluster's own CoreDNS the CRC route hostnames, cluster-wide (covers both the GOP
# job pod and Jenkins build agent pods, unlike a per-pod-template hostAliases override). Uses k3s's documented
# customization hook (https://docs.k3s.io/advanced#coredns-custom-configuration-imports); a plain second
# `hosts` block isn't allowed (CoreDNS: "this plugin can only be used once per Server Block"), so this uses
# `template` instead.
function configureJenkinsClusterDns() {
  local kubeconfig hostIpValue matchGroup
  kubeconfig="$(k3d kubeconfig write "${JENKINS_K3D_CLUSTER_NAME}")"
  hostIpValue="$(hostIp)"
  matchGroup="$(IFS='|'; echo "${CRC_ROUTE_HOSTNAMES[*]}")"

  # The AAAA block matters as much as the A block: GOP's JRE (Alpine/musl) does a combined A+AAAA lookup, and
  # without an explicit NOERROR/NODATA response here, the trailing AAAA NXDOMAIN after a successful A answer
  # makes musl's getaddrinfo discard the earlier successful A result and report UnknownHostException overall.
  cat <<EOF | KUBECONFIG="${kubeconfig}" kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: coredns-custom
  namespace: kube-system
data:
  crc.override: |
    template IN A apps-crc.testing {
      match "^(${matchGroup})\.apps-crc\.testing\.\$"
      answer "{{ .Name }} 60 IN A ${hostIpValue}"
      fallthrough
    }
    template IN AAAA apps-crc.testing {
      match "^(${matchGroup})\.apps-crc\.testing\.\$"
      rcode NOERROR
      fallthrough
    }
EOF
  KUBECONFIG="${kubeconfig}" kubectl -n kube-system rollout restart deployment coredns
  KUBECONFIG="${kubeconfig}" kubectl -n kube-system rollout status deployment coredns --timeout=60s
}

function deployJenkins() {
  local kubeconfig
  kubeconfig="$(k3d kubeconfig write "${JENKINS_K3D_CLUSTER_NAME}")"
  installMinimalIngressController "${kubeconfig}"
  sed "s/__HOST_IP__/$(hostIp)/g" "${SCRIPT_DIR}/helm/gop-jenkins-values.yaml" \
    | KUBECONFIG="${kubeconfig}" helm upgrade -i gop oci://ghcr.io/cloudogu/gop-helm -n gop --create-namespace -f -
  KUBECONFIG="${kubeconfig}" kubectl -n gop wait --for=condition=complete --timeout=10m -l app.kubernetes.io/name=gop-helm job
}

# GOP's own `features.ingress` (Traefik) only ever deploys via an Argo CD Application (Ingress.java uses
# ArgoCdApplicationStrategy unconditionally) -- since this cluster deliberately has no Argo CD, that
# Application manifest just sits in the git repo, never applied, and nothing ends up listening on port 80/443
# for k3d's loadbalancer to forward to (confirmed: "connection refused" from serverlb to the server node).
# scripts/init-cluster.sh disables k3s's own built-in traefik unconditionally (shared with the main project,
# not something to change here), so something has to fill that gap -- a plain ingress-nginx install with the
# chart's default (LoadBalancer) service type is picked up by k3d's own servicelb the same way k3s's built-in
# traefik would have been, restoring what --bind-ingress-port/--bind-ingress-https-port already expect.
function installMinimalIngressController() {
  local kubeconfig="$1"
  KUBECONFIG="${kubeconfig}" helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx >/dev/null
  KUBECONFIG="${kubeconfig}" helm upgrade -i ingress-nginx ingress-nginx/ingress-nginx \
    -n ingress-nginx --create-namespace \
    --set controller.ingressClassResource.default=true \
    --set controller.config.proxy-body-size=0 \
    --wait --timeout=5m
}

function deployPlatform() {
  local oc=(oc --context="${OC_CONTEXT}")

  ensureGopProject
  "${oc[@]}" apply -f "${SCRIPT_DIR}/manifest/gop-rbac.yaml"
  "${oc[@]}" adm policy add-scc-to-user anyuid -z gop-sa -n "${GOP_NAMESPACE}"

  for ns in "${PLATFORM_NAMESPACES[@]}"; do
    "${oc[@]}" create namespace "${ns}" --dry-run=client -o yaml | "${oc[@]}" apply -f -
    "${oc[@]}" adm policy add-scc-to-group anyuid "system:serviceaccounts:${ns}"
  done

  "${oc[@]}" delete job -l app.kubernetes.io/name=gop-helm -n "${GOP_NAMESPACE}" --ignore-not-found

  local jenkinsUrl="http://jenkins.$(hostIp).nip.io:${K3D_HTTP_PORT}"
  local scmUrl="http://scmm.$(hostIp).nip.io:${K3D_HTTP_PORT}"
  sed -e "s#__JENKINS_URL__#${jenkinsUrl}#g" -e "s#__SCM_URL__#${scmUrl}#g" "${SCRIPT_DIR}/helm/gop-values.yaml" \
    | helm upgrade -i gop oci://ghcr.io/cloudogu/gop-helm -n "${GOP_NAMESPACE}" --kube-context="${OC_CONTEXT}" -f -

  # The pod isn't necessarily running yet right after `helm upgrade` returns (image pull etc.) -- `oc logs -f`
  # on a not-yet-started container fails outright instead of waiting, so poll until it's actually available.
  until "${oc[@]}" logs -l app.kubernetes.io/name=gop-helm -n "${GOP_NAMESPACE}" &>/dev/null; do
    sleep 3
  done
  "${oc[@]}" logs -f -l app.kubernetes.io/name=gop-helm -n "${GOP_NAMESPACE}" || true

  # `oc logs -f` above only reports whether it could stream logs, not whether the job itself succeeded --
  # check that explicitly so a failed install job actually fails `make openshift-up`/`openshift-reset`.
  local jobName
  jobName="$("${oc[@]}" get job -l app.kubernetes.io/name=gop-helm -n "${GOP_NAMESPACE}" -o jsonpath='{.items[0].metadata.name}')"
  "${oc[@]}" wait --for=condition=complete --timeout=10m "job/${jobName}" -n "${GOP_NAMESPACE}"
}

# Two GOP-computed values get baked in assuming Jenkins/SCM-Manager/the registry and their consumers all
# share one cluster -- not true in this split-cluster setup, so both need rewriting after deployPlatform():
#
# 1. deployPlatform() (run from CRC) creates/updates the Jenkins multibranch jobs and can only reach
#    SCM-Manager via its external nip.io route (CRC can't resolve k3d's in-cluster DNS), so that's the
#    `<serverUrl>` it bakes into each job. When Jenkins' own SCM-Manager plugin later queries THAT URL for a
#    repository's clone link, SCM-Manager's response drops the port on it (confirmed: same request via the
#    in-cluster address `scmm.scm-manager.svc.cluster.local` returns a correct link, since port 80 needs no
#    port suffix in the first place) -- an SCM-Manager quirk, not something fixable via its own config
#    (baseUrl/forceBaseUrl) or ingress-nginx (X-Forwarded-Port) I could find. Since Jenkins and SCM-Manager
#    share this one k3d cluster, rewriting the already-created jobs to use that in-cluster address instead
#    sidesteps the bug entirely -- scoped to this cluster only, no host or SCM-Manager config changes.
#
# 2. GOP always sets the Jenkins global property REGISTRY_URL to "localhost:${internalPort}"
#    (ApplicationConfigurator.java) for its internal registry -- correct ONLY when whatever consumes the
#    built image (here: Argo CD's example-app Deployments, on CRC) is the SAME "localhost" as the registry.
#    It isn't here, so example-apps' Jenkinsfiles (which read env.REGISTRY_URL, see gitops-build-lib) push
#    and bake in an address CRC's node can't resolve to itself. Rewritten to the host's real LAN IP, which
#    fixCrcRegistryTrust() (CRI-O) and setupHost() (the host's own dockerd) both already trust as insecure.
function fixJenkinsBakedUrls() {
  local kubeconfig
  kubeconfig="$(k3d kubeconfig write "${JENKINS_K3D_CLUSTER_NAME}")"
  KUBECONFIG="${kubeconfig}" kubectl -n jenkins exec jenkins-0 -c jenkins -- sh -c \
    "find /var/jenkins_home/jobs -name config.xml -exec sed -i 's#<serverUrl>[^<]*</serverUrl>#<serverUrl>http://scmm.scm-manager.svc.cluster.local/scm</serverUrl>#' {} +
     sed -i 's#localhost:${K3D_REGISTRY_PORT}#$(hostIp):${K3D_REGISTRY_PORT}#g' /var/jenkins_home/config.xml"
  # Jenkins caches job/global config in memory; only edits to disk-backed config picked up by a restart apply.
  KUBECONFIG="${kubeconfig}" kubectl -n jenkins delete pod jenkins-0 --ignore-not-found
  KUBECONFIG="${kubeconfig}" kubectl -n jenkins wait --for=condition=Ready pod/jenkins-0 --timeout=3m

  # The sed above fixes the org folder's SCM Navigator <serverUrl> (used for future branch discovery), but
  # each branch job ALSO has its own checkout URLs (cloneInformation/UserRemoteConfig), baked in from the
  # Navigator at the time CRC originally discovered it (i.e. the external, port-dropping one) -- confirmed
  # live: those stay stale, and checkouts 503, until the next scan regenerates them from the now-fixed
  # Navigator. The org folder only rescans every 4h on its own (PeriodicFolderTrigger); force one now so the
  # fix is immediate rather than waiting.
  local pfLog cookieJar crumbJson field value
  pfLog="$(mktemp)"
  KUBECONFIG="${kubeconfig}" kubectl -n jenkins port-forward svc/jenkins 18080:80 >"${pfLog}" 2>&1 &
  local pfPid=$!
  sleep 3
  cookieJar="$(mktemp)"
  crumbJson="$(curl -s -c "${cookieJar}" -u admin:admin 'http://localhost:18080/crumbIssuer/api/json')"
  field="$(echo "${crumbJson}" | jq -r '.crumbRequestField')"
  value="$(echo "${crumbJson}" | jq -r '.crumb')"
  curl -s -b "${cookieJar}" -u admin:admin -X POST -H "${field}: ${value}" "http://localhost:18080/job/argocd/build" -o /dev/null
  sleep 15
  kill "${pfPid}" >/dev/null 2>&1 || true
  rm -f "${pfLog}" "${cookieJar}"
}

main "$@"
