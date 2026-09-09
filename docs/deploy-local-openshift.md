# GOP Deployment from Local Docker Image to Local OpenShift (CRC)

This guide provides a step-by-step walkthrough for deploying the local Docker image `local/gop:latest` to a local
OpenShift environment (CodeReady Containers / OpenShift Local) using the internal OpenShift Image Registry.

---

## 1. Prerequisites Check

- **OpenShift Local (CRC):** Running (`crc status`)
- **OpenShift CLI (`oc`):** Installed and operational
- **Docker / Podman:** Running with the local image `local/gop:latest`

### Recommend

Start openshift with more cpu and memory!

### CRC should use different default ports, because K3d has to use 80/443.

```bash
    crc config set ingress-http-port 8880
    crc config set ingress-https-port 8843
```

### Start with more resources, because CRC Argocd needs more cpu and memory.

```bash
crc start --cpus 6 --memory 16384 --disk-size 80
```

Verify local image:

```bash
docker images | grep gop
```

---

## 2. Step 1: Enable the Internal OpenShift Image Registry & Expose Route

In OpenShift Local (CRC), the internal Image Registry is often not exposed via an external route by default. This must
be enabled once with administrator privileges.

### 2.1 Log in as `kubeadmin`

Retrieve the password via CRC (if not already known):

```bash
crc console --credentials
```

Log in with admin rights:

```bash
oc login -u kubeadmin -p <kubeadmin-password> https://api.crc.testing:6443
```

### 2.2 Configure Image Registry Operator

For local test clusters (CRC), set the storage to `emptyDir` and the operator to `Managed`:

```bash
oc patch configs.imageregistry.operator.openshift.io/cluster --type merge -p '{"spec":{"managementState":"Managed","storage":{"emptyDir":{}}}}'
```

### 2.3 Expose the Default Route for External Access

```bash
oc patch configs.imageregistry.operator.openshift.io/cluster --type merge -p '{"spec":{"defaultRoute":true}}'
```

### 2.4 Verify Registry Route

```bash
oc get route default-route -n openshift-image-registry
```

The registry host URL typically resolves to: `default-route-openshift-image-registry.apps-crc.testing`.

---

## 3. Step 2: Create Project / Namespace for GOP

Create a new project in the OpenShift cluster (can be executed as `developer` or `kubeadmin`):

```bash
# Optional: Switch to developer user
oc login -u developer -p developer https://api.crc.testing:6443

# Create new project
oc new-project gop
```

---

## 4. Step 3: Authenticate Docker with OpenShift Registry

To allow Docker to push the local image into the cluster, authenticate using the current OpenShift session token:

```bash
docker login -u $(oc whoami) -p $(oc whoami -t) default-route-openshift-image-registry.apps-crc.testing
```

> **Note on SSL/TLS Certificate Errors (x509: certificate signed by unknown authority):**  
> Add the registry domain to `insecure-registries` in Docker Desktop under **Settings** → **Docker Engine**:
> ```json
> {
>   "insecure-registries": [
>     "default-route-openshift-image-registry.apps-crc.testing"
>   ]
> }
> ```
> Then click *Apply & restart*.

---

## 5. Step 4: Tag & Push Local Image to OpenShift

Tag the local image with the registry URL and target project (`gop`), then push:

```bash
# Tag image
docker tag local/gop:latest default-route-openshift-image-registry.apps-crc.testing/gop/gop:latest

# Push image to OpenShift Registry
docker push default-route-openshift-image-registry.apps-crc.testing/gop/gop:latest
```

### Verify ImageStream in Cluster

After pushing, OpenShift automatically creates an `ImageStream`:

```bash
oc get is -n gop
oc describe is gop -n gop
```

---

## 6. Step 5: Configure ServiceAccount, RBAC & OpenShift SCCs

GOP operates as an orchestrator job that provisions tools (SCM-Manager, Argo CD, Vault, etc.) across various namespaces.
By default, OpenShift blocks containers using root groups (`fsGroup: 0`) under the `restricted-v2` SCC. We therefore set
up the permissions and pre-create the required tool namespaces with the `anyuid` SCC.

### 5.1 Create ServiceAccount & ClusterRoleBinding for GOP

Manifest definition is located
in [scripts/local-openshift/manifest/gop-rbac.yaml](../scripts/local-openshift/manifest/gop-rbac.yaml):

```bash
# Apply manifest (as kubeadmin)
oc apply -f scripts/local-openshift/manifest/gop-rbac.yaml
```

### 5.2 Assign OpenShift SCC `anyuid` to GOP ServiceAccount

Allows the GOP installer pod itself to start:

```bash
oc adm policy add-scc-to-user anyuid -z gop-sa -n gop
```

### 5.3 Pre-create Tool Namespaces & Assign `anyuid` SCC

Ensures that tools deployed by GOP (e.g. SCM-Manager with `fsGroup: 0`) can start without security constraint errors:

```bash
# 1. Pre-create namespaces for initial tools
oc create namespace scm-manager || true
oc create namespace argocd || true
oc create namespace vault || true

# 2. Grant anyuid SCC to all ServiceAccounts in these namespaces
oc adm policy add-scc-to-group anyuid system:serviceaccounts:scm-manager
oc adm policy add-scc-to-group anyuid system:serviceaccounts:argocd
oc adm policy add-scc-to-group anyuid system:serviceaccounts:vault
```

> **Note for additional tools (e.g. Monitoring / Prometheus):**  
> When activating additional components later, simply execute the same commands for the new namespace:  
> `oc create namespace monitoring || true`  
> `oc adm policy add-scc-to-group anyuid system:serviceaccounts:monitoring`

### 5.4 Clean Up Previous Failed Jobs (if any)

```bash
oc delete job -l app.kubernetes.io/name=gop-helm -n gop || true
oc delete job gop-installer-job -n gop || true
```

---

## 7. Step 6: Run GOP in OpenShift Cluster

Two execution options are available:

### Option A: Installation via Helm (Path A with `gop-values.yaml`)

Configuration file is located
at [scripts/local-openshift/helm/gop-values.yaml](../scripts/local-openshift/helm/gop-values.yaml):

```bash
helm upgrade -i gop oci://ghcr.io/cloudogu/gop-helm -n gop -f scripts/local-openshift/helm/gop-values.yaml
```

Stream live installer logs:

```bash
oc logs -f -l app.kubernetes.io/name=gop-helm -n gop
```

---

### Option B: Direct Manifest via OpenShift Job (Path B with `gop-job.yaml`)

Job manifest is located
at [scripts/local-openshift/manifest/gop-job.yaml](../scripts/local-openshift/manifest/gop-job.yaml):

```bash
# Clean up prior installer job (if present)
oc delete job gop-installer-job -n gop || true

# Apply manifest and start job
oc apply -f scripts/local-openshift/manifest/gop-job.yaml
```

Stream live installer logs:

```bash
oc logs -f job/gop-installer-job -n gop
```

---

## 8. Step 7: Access Installed Tools & Routes

Once the GOP installer job finishes with status `Completed`, the deployed tools are accessible via OpenShift Routes.

### 8.1 List All Created Routes

```bash
oc get routes -A
```

### 8.2 Default URLs with `baseUrl: http://apps-crc.testing`

* **SCM-Manager:** `http://scmm.apps-crc.testing`
* **Argo CD:** `http://argocd.apps-crc.testing`
* **Vault:** `http://vault.apps-crc.testing`
* **Grafana / Metrics:** `http://grafana.apps-crc.testing`

**Default Credentials:**

* **Username:** `admin`
* **Password:** `admin` (or the configured value in `gop-values.yaml`)

---

## 9. Troubleshooting & Common Commands

- **Rerun GOP Job (Helm):**
  ```bash
  oc delete job -l app.kubernetes.io/name=gop-helm -n gop
  helm upgrade -i gop oci://ghcr.io/cloudogu/gop-helm -n gop -f scripts/local-openshift/helm/gop-values.yaml
  ```
- **Rerun GOP Job (Manifest):**
  ```bash
  oc delete job gop-installer-job -n gop
  oc apply -f scripts/local-openshift/manifest/gop-job.yaml
  ```
- **Update Image after Local Code Changes:**
  ```bash
  docker tag local/gop:latest default-route-openshift-image-registry.apps-crc.testing/gop/gop:latest
  docker push default-route-openshift-image-registry.apps-crc.testing/gop/gop:latest
  ```
