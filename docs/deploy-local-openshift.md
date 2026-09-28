# Local OpenShift (CRC) + Jenkins Sandbox

Runs the GitOps playground's `full` profile split across two local clusters:

- **CRC (OpenShift Local)** hosts Argo CD, Vault, Monitoring, the container Registry, and the `gop`
  orchestrator itself.
- **A dedicated k3d cluster** (`gitops-playground-jenkins`) hosts Jenkins and SCM-Manager, using the same
  docker-socket-mount architecture `make cluster` already uses for Jenkins (`scripts/init-cluster.sh`,
  unchanged).

Jenkins can't run inside CRC: its build agents need the **host's** `/var/run/docker.sock`
(`argocd/cluster-resources/apps/jenkins/templates/values.ftl.yaml`), which a CRC node neither has nor (under
its restricted SCC) would allow mounting. SCM-Manager *could* run in CRC, but doesn't here: an OpenShift
Route with cert-manager-issued TLS in front of it produced SSL handshake failures, and it has no OpenShift
dependency of its own — a plain k3d ingress (the same, already-working path Jenkins uses) sidesteps that
entirely.

Both are deployed via GOP's "external tool" support (`--jenkins-url`/`--jenkins-username`/`--jenkins-password`
and the equivalent `--scmm-url`/`--scmm-username`/`--scmm-password`) — the CRC-side run doesn't install either,
it just points the content step (SCM repo + Jenkins job creation) and Argo CD/Monitoring links at the
k3d-hosted instances. This is a scenario GOP is explicitly built for: `ScmManagerTenantConfig` carries a
`urlForJenkins` field mirroring `JenkinsSchema`'s `urlForScm`, i.e. "SCM-Manager and Jenkins both external to
the platform cluster" isn't a workaround bent into shape, it's a designed-for configuration.

---

## Prerequisites

- CRC installed and initialized once (`crc setup`).
- `oc`, `k3d`, `helm`, `jq`, and Docker running on the Ubuntu host.
- Depending on your CRC version/networking mode, CRC's routes may be proxied through the host's own loopback
  (`crc ip` prints `127.0.0.1`, and `apps-crc.testing` resolves via a static block CRC adds to `/etc/hosts`
  itself, not via a separately routable VM IP or `crc setup`'s DNS integration). `openshift.sh` accounts for
  this by using the host's real IP wherever cross-cluster reachability is needed — see "What `openshift-up`
  does" below.
- At least ~16GB RAM free (`checkHostResources` in `openshift.sh` warns if there isn't) — CRC alone is
  configured for 12GB now that Jenkins runs outside it.

---

## One-time host setup

Jenkins builds push images through the host's own Docker daemon (it's the daemon behind the mounted socket),
to CRC's internal registry, exposed via an OpenShift Route. The route's self-signed certificate isn't trusted
by that daemon by default, so it needs to be added to `insecure-registries`. This is the **only** systemwide
change this setup makes, and it's explicit and reversible:

```bash
make openshift-setup-host
```

Shows the exact `/etc/docker/daemon.json` diff and asks for confirmation before touching anything or
restarting docker. Revert with:

```bash
make openshift-cleanup-host
```

Nothing else is changed at the host level — no `/etc/hosts` edits. Cross-cluster hostname resolution is
handled via [nip.io](https://nip.io) (CRC → Jenkins/SCM-Manager, both k3d-hosted) and a CoreDNS customization
inside the Jenkins k3d cluster itself (Jenkins → the CRC routes it still needs, e.g. Argo CD/Vault), both
scoped to these two clusters — see `configureJenkinsClusterDns()` in `scripts/local-openshift/openshift.sh`.

---

## Lifecycle

```bash
make openshift-up        # start CRC + the Jenkins k3d cluster, deploy GOP across both
make openshift-down      # stop both, keep all data
make openshift-reset     # recreate the Jenkins k3d cluster + redeploy GOP, keep the CRC VM -- fast test cycle
make openshift-destroy   # delete the CRC VM and the Jenkins k3d cluster entirely
```

Use `openshift-reset` for the everyday edit/test loop (fast: only the small k3d cluster and both `gop`
deployments are recreated). Use `openshift-destroy` occasionally to verify the whole environment is still
reproducible from scratch — it deletes CRC's ~80 GiB VM image too, so `openshift-up` afterwards takes a while.

All of this is implemented in `scripts/local-openshift/openshift.sh`; the Makefile targets just call it.

---

## What `openshift-up` does

1. **Check host RAM**, then **start CRC** (`crc start --cpus 4 --memory 12288 --disk-size 80`), left on CRC's
   own default ports (80/443). CRC's *internal* URL generation (the OAuth issuer, and — confirmed the hard
   way — the internal registry's token "realm") hardcodes 80/443 and ignores `crc config set ingress-*-port`,
   so remapping CRC's ports breaks `oc login`, image pushes, and anything else that follows those
   self-referential URLs. `openshift.sh` uses the `crc-admin` kubeconfig context instead of `oc login`
   (certificate auth, set up by `crc start` itself), always passed explicitly so it can never accidentally
   target whatever cluster your kubeconfig's current-context happens to point at.
2. **Expose the internal registry** via a Route (`oc patch configs.imageregistry.operator.openshift.io/cluster
   ...`). If this CRC instance was used for something else before, it may have a stale PVC-based storage
   config left over — the patch explicitly clears it (`"pvc":null`), otherwise the image-registry operator
   gets stuck "Progressing" with both emptyDir and PVC storage configured at once.
3. **Build and push the `gop` image** to that registry route (`make image`'s build, then tag/push) — the
   platform deployment in step 5 needs it there before its job can start.
4. **Create the Jenkins k3d cluster**, bound to the host's real (non-loopback) IP, on ports 8880/8843
   instead of the default 80/443 — since CRC's router turned out to need its *own* defaults (see step 1),
   k3d is the side that moves to avoid the clash. Also installs a plain `ingress-nginx` (`installMinimalIngressController()`):
   GOP's own ingress controller (`features.ingress`, Traefik) only ever deploys via an Argo CD `Application`,
   and this cluster deliberately has no Argo CD, so that manifest would never get applied.
5. **Deploy Jenkins, SCM-Manager, and a Docker registry** (`scripts/local-openshift/helm/gop-jenkins-values.yaml`)
   into that cluster. Reachable at `http://jenkins.<host-ip>.nip.io:8880` and `http://scmm.<host-ip>.nip.io:8880`
   (nip.io resolves those hostnames to `<host-ip>` publicly, so no DNS setup is needed for CRC to reach them).
   The registry (`registry.active: true`) is what example-app pipelines (e.g. petclinic-helm) push built images
   to at `localhost:30000` — `scripts/init-cluster.sh` always forwards host port 30000 to it.
6. **Deploy the platform** (`scripts/local-openshift/helm/gop-values.yaml`) into CRC via
   `oc apply -f scripts/local-openshift/manifest/gop-rbac.yaml` (ServiceAccount + `anyuid` SCC, needed because
   OpenShift's restricted SCC blocks the `fsGroup: 0` several tools use) followed by
   `helm upgrade -i gop oci://ghcr.io/cloudogu/gop-helm`, with `--jenkins-url`/`--scmm-url` pointing at step
   5's Jenkins/SCM-Manager.
7. **Fix URLs GOP baked in assuming a single cluster** (`fixJenkinsBakedUrls()`): both of GOP's defaults assume
   whatever consumes Jenkins/the registry lives in the same cluster as Jenkins/the registry themselves — not
   true in this split setup:
   - Step 6 runs from CRC, so it can only reach SCM-Manager via its external nip.io route, and that's the
     `<serverUrl>` it bakes into each Jenkins multibranch job (and the org folder's SCM Navigator). SCM-Manager's
     own git-clone-link response for that external URL drops the port, breaking checkouts — see the
     troubleshooting entry below. Since Jenkins and SCM-Manager both run in the same k3d cluster, this step
     rewrites every `<serverUrl>` on disk (branch jobs AND the org folder's Navigator) to SCM-Manager's
     in-cluster Service address (`scmm.scm-manager.svc.cluster.local`, port 80, no k3d/host config touched).
     Existing branch jobs' own checkout URLs were generated from the *old* Navigator at discovery time, so
     fixing the Navigator alone doesn't retroactively fix them — this step also triggers "Scan Organization
     Folder Now" via the Jenkins API so they get regenerated immediately, instead of waiting for the next
     scheduled 4h rescan.
   - GOP always sets Jenkins' `REGISTRY_URL` global property to `localhost:<port>` for its internal registry —
     correct only when the registry's consumer (here: Argo CD's example-app Deployments, on CRC) is the same
     "localhost" as the registry. This step rewrites it to the host's real LAN IP instead, matching what
     `fixCrcRegistryTrust()` (below) and `setupHost()` both already trust as insecure/HTTP.
   - Both edits are to on-disk config only; Jenkins caches it in memory, so this step restarts Jenkins to pick
     them up before triggering the rescan.

Also part of `openshift-up` (runs right after step 1, before step 2): **`fixCrcRegistryTrust()`** — CRI-O on
the CRC node refuses the k3d registry's host-IP address over plain HTTP unless told it's insecure, and (unlike
a single-pull check) only picks up a *new* trust entry after a reload — this step writes a
`registries.conf.d` entry for `<host-ip>:30000` and reloads CRI-O so CRC-hosted pods (e.g. the deployed
example apps) can actually pull images the k3d-hosted Jenkins built and pushed.

## Access the deployed tools

```bash
oc --context=crc-admin get routes -A
```

* **Argo CD:** `http://argocd.apps-crc.testing`
* **Vault:** `http://vault.apps-crc.testing`
* **Grafana / Metrics:** `http://grafana.apps-crc.testing`
* **Jenkins:** `http://jenkins.<host-ip>.nip.io:8880` (find `<host-ip>` with `ip route get 1.1.1.1`)
* **SCM-Manager:** `http://scmm.<host-ip>.nip.io:8880`

The `apps-crc.testing` URLs need no port (CRC runs on its defaults); the k3d-hosted ones (Jenkins,
SCM-Manager) do, since k3d is the side that moved off the defaults to avoid clashing with CRC's router on
the same host.

Default credentials: `admin` / `admin` (or whatever's configured in `scripts/local-openshift/helm/gop-*-values.yaml`).

---

## Troubleshooting

- **Rerun a failed GOP job:**
  ```bash
  oc --context=crc-admin delete job -l app.kubernetes.io/name=gop-helm -n gop
  make openshift-up   # safe to re-run; steps are idempotent
  ```
- **`oc login -u kubeadmin -p ...` fails with "connection refused":** use `oc --context=crc-admin ...`
  instead, don't `oc login` at all (see step 1 above).
- **image-registry operator stuck "Progressing" with a storage error mentioning both EmptyDir and PVC:** a
  previous, differently-configured use of this CRC instance left a PVC-based storage config in place.
  `exposeRegistryRoute()` in `openshift.sh` clears it (`"pvc":null`); if you still see this, re-run
  `make openshift-up` or patch it manually as shown there.
- **GOP job fails with `java.net.UnknownHostException: <host>.apps-crc.testing`, even though
  `kubectl run ... -- getent hosts <host>.apps-crc.testing` resolves it fine:** this is a musl libc bug (the
  GOP image is Alpine-based) combined with CoreDNS's `template` plugin — if the AAAA query for a hostname
  returns NXDOMAIN right after the A query returned a real answer, musl's `getaddrinfo` discards the earlier
  successful A result for the whole lookup. Fix is an explicit `rcode NOERROR` (NODATA, not NXDOMAIN) AAAA
  block in `configureJenkinsClusterDns()` in `openshift.sh` — already applied; if you add more CRC hostnames
  to `CRC_ROUTE_HOSTNAMES`, they automatically get both the `A` and `AAAA` template blocks.
- **SCM-Manager's `PUT /api/v2/config` fails with `405 Method Not Allowed`
  (`ScmManagerSetup.setSetupConfigs`):** an upstream API-compatibility regression in SCM-Manager chart 3.12.1
  (bumped in commit `9a6a9283`, currently the schema's pinned default). Both values files pin
  `scm.scmManager.helm.version: "3.11.10"` as a workaround; remove it once that's fixed upstream.
- **`javax.net.ssl.SSLHandshakeException: (bad_certificate) ... Empty issuer DN not allowed`:** seen when
  SCM-Manager was still CRC-hosted, behind an OpenShift Route with a cert-manager-issued cert — the reason
  it moved to the Jenkins k3d cluster (see the top of this doc). If you deliberately move it back to CRC and
  hit this again, it's likely stale/mismatched cert-manager state from repeated `scm-manager` namespace
  delete/recreate cycles rather than a fresh-install problem; deleting the namespace once more before
  redeploying is the fastest way to confirm.
- **Update the image after local code changes:** `openshift-up`/`openshift-reset` already rebuild and push it
  every time (`buildAndPushGopImage()`); to do it by hand:
  ```bash
  docker login -u $(oc --context=crc-admin whoami) -p $(oc --context=crc-admin whoami -t) \
    default-route-openshift-image-registry.apps-crc.testing
  docker tag local/gop:latest default-route-openshift-image-registry.apps-crc.testing/gop/gop:latest
  docker push default-route-openshift-image-registry.apps-crc.testing/gop/gop:latest
  ```
  Requires `make openshift-setup-host` to have trusted that route's self-signed certificate first.
- **Jenkins build's Docker stage fails with `Get "http://localhost:30000/v2/": EOF` on `docker push
  localhost:30000/...`:** two independent causes, both already fixed here:
  1. GOP's `registry` component defaults to inactive outside `--profile=full` — fixed by
     `registry: { active: true }` in `gop-jenkins-values.yaml`. If you still see this, check `kubectl -n
     registry get pods` in the Jenkins k3d cluster's kubeconfig.
  2. `scripts/init-cluster.sh` binds the registry's NodePort to the SAME host address as ingress
     (`--bind-ingress-host`, here the host's real LAN IP, needed so CRC can reach Jenkins/SCM-Manager) — but
     Jenkins agents push via the **host's** mounted `docker.sock`, so `localhost:30000` (hardcoded in
     gitops-build-lib/ces-build-lib) is resolved by the host's own dockerd against `127.0.0.1`, which was
     never bound to the registry at all. `createJenkinsCluster()` now runs `k3d cluster edit ... --port-add
     127.0.0.1:30000:30000@loadbalancer` right after cluster creation to fix this. If `localhost:30000` still
     doesn't respond, something else on the host may be squatting on that exact port — check with `ss -ltnp |
     grep 30000` (this bit us once: IntelliJ's embedded-Chromium helper, `cef_server`, had claimed it).
- **Jenkins build fails checkout with a 503 on `http://scmm.<host-ip>.nip.io:8880/scm/repo/...`** (either the
  actual build checkout, or the earlier "Checking out git ... to read Jenkinsfile" / branch-indexing step):
  SCM-Manager's git-clone-link response (`_links.protocol[0].href`) drops the port when queried via that
  external nip.io URL — reproducible independent of `baseUrl`/`forceBaseUrl` config and of ingress-nginx's
  `X-Forwarded-Port` header, and only avoidable by not going through that external route at all.
  `fixJenkinsBakedUrls()` (step 7 above, already wired into `openshift-up`) rewrites every `<serverUrl>`
  (branch jobs *and* the org folder's SCM Navigator) to SCM-Manager's in-cluster Service address. The
  Navigator fix alone doesn't retroactively fix already-created branch jobs' own checkout URLs, though — they
  were generated from the Navigator at discovery time — so this step also forces a "Scan Organization Folder
  Now" via the Jenkins API afterward to regenerate them immediately. If you still hit this, either re-run
  `make openshift-up` (idempotent), or manually re-trigger it: `curl -u admin:admin -X POST
  "http://<jenkins-url>/job/argocd/build"` (needs a CSRF crumb first, see `fixJenkinsBakedUrls()`).
- **A CRC-deployed example app (e.g. `example-apps-staging/spring-petclinic-helm-springboot`) stays stuck
  `ContainerCreating`, pulling `<host-ip>:30000/spring-petclinic-helm:...`:** the image is reachable over the
  network (confirmed: a test pod's plain `curl`/network check to that address succeeds), but CRI-O — CRC's
  container runtime — refuses non-localhost registries over plain HTTP unless told they're insecure, same
  restriction Docker has (hence `setupHost()`'s `insecure-registries` entries). `fixCrcRegistryTrust()`
  (already wired into `openshift-up`) writes a `registries.conf.d` entry on the CRC node and reloads CRI-O.
  If you still hit this after a fresh `openshift-up`, confirm the entry actually matches the k3d registry's
  *current* address (see the VPN note below) — `oc debug node/crc -- chroot /host cat
  /etc/containers/registries.conf.d/999-k3d-registry.conf`.
- **Any of the above `<host-ip>`-based fixes point at the wrong address, especially after connecting a VPN**:
  `hostIp()` (`ip route get 1.1.1.1`) returns whatever the *current* default route is — if a VPN is connected
  when `make openshift-up`/`openshift-setup-host`/any host-ip-dependent step runs, it can return the VPN
  tunnel's IP instead of the real LAN IP the k3d cluster is actually bound to, silently baking in a wrong
  address everywhere (confirmed live: this happened mid-session and had to be manually corrected in
  `/etc/docker/daemon.json`, the CRC `registries.conf.d` entry, and Jenkins' `REGISTRY_URL`). **Disconnect any
  VPN before running `openshift-up`/`openshift-setup-host`.** If you already ran one of these with a VPN
  active, re-run it after disconnecting — stale wrong entries left behind (e.g. an extra `insecure-registries`
  entry) are harmless clutter, not a correctness problem, as long as the *correct* entry also gets added.
- **A Jenkins build can't reach Argo CD/Vault/another CRC-hosted tool:** those hostnames (`*.apps-crc.testing`)
  only resolve on the Ubuntu host, not inside the k3d cluster's own pod network. Add the missing hostname to
  `CRC_ROUTE_HOSTNAMES` in `scripts/local-openshift/openshift.sh` and re-run `make openshift-reset`.
- **Manifest-based install (`scripts/local-openshift/manifest/gop-job.yaml`):** kept as a reference for a
  plain `oc apply` install without Helm, but it can't express `jenkins.active=false` via CLI flags alone (no
  `--no-jenkins` flag) — use the Helm path (`make openshift-up`) for this split-cluster setup.
