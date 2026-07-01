# Deploy a local Keycloak as OIDC provider

This setup installs Keycloak into the `keycloak` namespace and imports the local GOP realm from
[`realm-export.json`](./realm-export.json). The realm is meant for local demos only. It contains the clients for Argo
CD,
Jenkins, Vault and Grafana. SCM-Manager does not support OIDC yet.

The imported realm and [`oidc-local.yaml`](./oidc-local.yaml) use the same checked-in demo client secrets. Replace them
before using this outside of a local throwaway cluster.

## Prerequisites

- A GOP cluster with the current `kubectl` context pointing to it.
- Helm 3 installed locally.
- The GOP local ingress setup, usually with `http://localhost` as base URL. This gives the GOP application URLs such as
  `http://argocd.localhost`, `http://jenkins.localhost`, `http://grafana.localhost` and `http://vault.localhost`.
- A Traefik ingress controller for the `keycloak.local.gd` ingress. In a fresh GOP k3d cluster this controller is
  created
  when GOP is applied with `--ingress` or a profile that enables ingress, for example `--profile=full`.
- Keycloak intentionally uses `keycloak.local.gd` instead of `keycloak.localhost`. Pods often resolve any
  `*.localhost` name to their own loopback address before asking CoreDNS, so a CoreDNS rewrite for
  `keycloak.localhost` is not reliable.

Run the following commands from the repository root.

## Reapply GOP from a clean local k3d cluster

If you already have a local GOP instance and want to reapply it from scratch, delete the current k3d cluster first.
This removes GOP, Keycloak, persistent volumes and the generated kubeconfig for that cluster:

```bash
k3d cluster delete gitops-playground
```

Then recreate the cluster with the GOP cluster bootstrap script:

```bash
bash scripts/init-cluster.sh
```

If you do not have this repository checked out or want to use the published script, use:

```bash
bash <(curl -s https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh)
```

After the cluster exists again, continue with the steps below: create the Keycloak realm ConfigMap, install Keycloak,
configure the CoreDNS rewrite and then apply GOP with `oidc-local.yaml`.

## 1. Create the realm ConfigMap

Keycloak imports files from `data/import` during startup when started with `--import-realm`. Store the realm export as a
ConfigMap first:

```bash
kubectl create namespace keycloak --dry-run=client -o yaml | kubectl apply -f -

kubectl -n keycloak create configmap keycloak-realm \
  --from-file=realm-export.json=docs/oidc/realm-export.json \
  --dry-run=client -o yaml | kubectl apply -f -
```

## 2. Install Keycloak and import the realm

The realm export is mounted into Keycloak's import directory and imported by Keycloak itself. Do not use the chart's
`keycloakConfigCli` job for this export. The job can lag behind Keycloak's realm-export format and fail on newer fields.

```bash
helm upgrade --install keycloak oci://registry-1.docker.io/bitnamicharts/keycloak \
  --namespace keycloak \
  --reset-values \
  --set global.security.allowInsecureImages=true \
  --set image.registry=docker.io \
  --set image.repository=bitnamilegacy/keycloak \
  --set postgresql.image.registry=docker.io \
  --set postgresql.image.repository=bitnamilegacy/postgresql \
  --set auth.adminUser=admin \
  --set auth.adminPassword=admin \
  --set production=false \
  --set tls.enabled=false \
  --set proxyHeaders=xforwarded \
  --set hostnameStrict=false \
  --set httpEnabled=true \
  --set extraEnvVars[0].name=KC_HOSTNAME \
  --set extraEnvVars[0].value=keycloak.local.gd \
  --set ingress.enabled=true \
  --set ingress.ingressClassName=traefik \
  --set ingress.hostname=keycloak.local.gd \
  --set ingress.tls=false \
  --set keycloakConfigCli.enabled=false \
  --set extraStartupArgs=--import-realm \
  --set extraVolumes[0].name=realm-import \
  --set extraVolumes[0].configMap.name=keycloak-realm \
  --set extraVolumeMounts[0].name=realm-import \
  --set extraVolumeMounts[0].mountPath=/opt/bitnami/keycloak/data/import/realm-export.json \
  --set extraVolumeMounts[0].subPath=realm-export.json \
  --set extraVolumeMounts[0].readOnly=true
```

Wait until Keycloak is running:

```bash
kubectl -n keycloak rollout status statefulset/keycloak --timeout=10m
kubectl -n keycloak logs statefulset/keycloak --tail=100
```

After the ingress controller is available, Keycloak is reachable at `http://keycloak.local.gd`. The admin console is
available at `http://keycloak.local.gd/admin/` with user `admin` and password `admin`. The imported realm is `gop`.

## 3. Make `keycloak.local.gd` resolvable from pods

The browser and the applications must use the same issuer URL: `http://keycloak.local.gd/realms/gop`. Pods inside the
cluster therefore also need to resolve `keycloak.local.gd`. Prefer a CoreDNS rewrite over fixed `hostAliases`, because
the service IP can change.

Add this line to the CoreDNS `Corefile`, before the `kubernetes` or `forward` plugin:

```text
rewrite name keycloak.local.gd keycloak.keycloak.svc.cluster.local
```

Then restart CoreDNS:

```bash
kubectl -n kube-system edit configmap coredns
kubectl -n kube-system rollout restart deployment/coredns
```

You can verify the issuer from any pod that has `curl`:

```bash
kubectl run oidc-check --rm -it --restart=Never --image=curlimages/curl -- \
  curl -s http://keycloak.local.gd/realms/gop/.well-known/openid-configuration
```

## 4. Apply GOP with the OIDC configuration

When applying or re-applying GOP, include [`credentials.yaml`](./credentials.yaml) and
[`oidc-local.yaml`](./oidc-local.yaml). The credentials file pins the local demo passwords and configures Jenkins'
Prometheus scrape to use the OIDC escape hatch account. With the published container image this means mounting both
files
into the container:

```bash
export CLUSTER_NAME=gitops-playground

docker run --rm -t --pull=always \
  -v ~/.config/k3d/kubeconfig-${CLUSTER_NAME}.yaml:/home/.kube/config \
  -v "$PWD/docs/oidc/credentials.yaml:/tmp/credentials.yaml:ro" \
  -v "$PWD/docs/oidc/oidc-local.yaml:/tmp/oidc-local.yaml:ro" \
  --net=host \
  ghcr.io/cloudogu/gitops-playground \
  --profile=full \
  --config-file=/tmp/credentials.yaml \
  --config-file=/tmp/oidc-local.yaml
```

For local development from this repository, use the same config file directly:

```bash
./mvnw exec:java -Dexec.arguments="--profile=full --config-file=docs/oidc/credentials.yaml --config-file=docs/oidc/oidc-local.yaml"
```

## Troubleshooting

- `Script upload is disabled`: the export still contains Keycloak Authorization Services JavaScript policies. The
  checked-in export intentionally removes Authorization Services from the demo OIDC clients because Argo CD, Jenkins,
  Vault and Grafana only need normal OIDC clients.
- `http://keycloak.local.gd` does not open: check that the Traefik ingress controller is installed and that your machine
  resolves `*.localhost`. If your OS does not resolve `*.localhost`, use the GOP local ingress alternatives described in
  [Deploy Ingress Controller](../Deploy-Ingress-Controller.md#local-ingresses) and adjust the URLs in
  [`realm-export.json`](./realm-export.json) and [`oidc-local.yaml`](./oidc-local.yaml).
- Apps fail OIDC discovery from inside the cluster: check the CoreDNS rewrite and verify the well-known endpoint from a
  pod.
- Client authentication fails: make sure the client secrets in Keycloak match [`oidc-local.yaml`](./oidc-local.yaml).
  The demo export in this repository already matches the file.
- Jenkins fails during startup with
  `No hudson.security.SecurityRealm implementation found for oic`: Jenkins is reading the OIDC JCasC file before the
  OIDC plugin is available. Install `oic-auth` through the Jenkins Helm values so it is present during controller boot;
  installing it later through GOP's post-start plugin upload is too late for JCasC.
