# Using an external HashiCorp Vault with External Secrets Operator

GitOps Playground (GOP) can install External Secrets Operator (ESO) and configure it to use an **existing external** HashiCorp Vault without deploying the GOP-managed Vault. GOP generates GitOps manifests for namespaced `SecretStore` and `ExternalSecret` resources (`external-secrets.io/v1`). Argo CD applies these resources. ESO authenticates to Vault, reads the requested values, and creates or updates Kubernetes Secrets.

This integration provides **runtime secrets** needed after GOP has started. It does not provision GOP bootstrap credentials. GOP does not manage the external Vault, its authentication backends, roles, policies, or AppRole SecretIDs.

## Prerequisites

- The ESO controller can reach Vault, and the URL, KV mount, and KV version match the Vault configuration.
- The required secrets and Vault policies already exist. A non-privileged Vault token restricted to the necessary KV paths is sufficient; a root token is not required.
- The target namespaces exist by the time ESO synchronizes the secrets, or are created through `content.namespaces`.
- Kubernetes resources and Vault roles required by the selected authentication method exist **before synchronization** (see below).
- ESO is installed and the required `external-secrets.io/v1` CRDs are available. In environments without permission to manage CRDs, GOP can run with `application.skipCrds: true`; the platform must install the CRDs beforehand.

If `features.secrets.externalSecrets.secrets` is configured, `externalSecrets.active: true` is required. **Exactly one** authentication method (`tokenSecretRef`, `appRole`, or `kubernetes`) must be selected per external Vault connection. GOP applies the selected authentication configuration to all namespaced SecretStores it generates for that connection. ESO can still be installed without Vault authentication if no external secrets are configured.

## Configuring SecretStore and ExternalSecret

The following example creates the Kubernetes Secret `customer-credentials` in namespace `customer-app`. It uses an existing Vault token reference. For AppRole or Kubernetes Auth, replace **only the `auth` block** with one of the alternatives below.

```yaml
content:
  namespaces:
    - customer-app

features:
  secrets:
    externalSecrets:
      active: true
      vault:
        storeName: external-customer-vault
        server: https://vault.example.org
        path: secret
        version: v2
        auth:
          tokenSecretRef:
            name: vault-token
            key: token
      secrets:
        - name: customer-credentials
          namespace: customer-app
          remoteKey: gop/customer
          refreshInterval: 15m
          # Kubernetes Secret key: Vault property
          data:
            username: username
            password: password
```

`remoteKey` identifies the secret path **relative to the KV mount**. With KV v2 and `path: secret`, `gop/customer` corresponds to the Vault API path `secret/data/gop/customer`. Keys on the left side of `data` become Kubernetes Secret keys; values on the right identify properties in Vault.

`refreshInterval` is optional. If omitted, GOP does not render the field and ESO uses its default (one hour). ESO refreshes the Kubernetes Secret when values change in Vault; GOP does not need to be run again.

### Vault Enterprise namespaces (optional)

If the external Vault uses Vault Enterprise namespaces, set `features.secrets.externalSecrets.vault.namespace` to the subnamespace containing the KV secrets engine and the authentication backend:

```yaml
features:
  secrets:
    externalSecrets:
      vault:
        namespace: customer/team
```

GOP then renders `spec.provider.vault.namespace: customer/team` in each namespaced `SecretStore`. This is a **Vault namespace**, not the Kubernetes namespace of the `SecretStore` or target Secret. When the setting is omitted or empty, GOP omits the field and existing non-Enterprise Vault configurations behave as before.

GOP assumes that the Vault authentication method and the KV secrets engine are configured in the same Vault Enterprise namespace. A namespace can contain multiple secrets, which ESO can synchronize through separate `ExternalSecret` resources, provided the Vault policies allow access to their paths. The customer is responsible for provisioning the Vault namespace, authentication method, roles, and policies; GOP only references the existing configuration.

The local Vault development container does not support Vault Enterprise namespaces, so this option is covered by configuration and rendering tests rather than a local Vault end-to-end test.

### Option 1: Token authentication (`tokenSecretRef`)

```yaml
auth:
  tokenSecretRef:
    name: vault-token
    key: token
```

ESO reads the existing Vault token from Kubernetes Secret `customer-app/vault-token` (key `token`). GOP neither creates this Secret nor stores the token value in Git. The reference must resolve to an existing Secret in **each target namespace**.

### Option 2: AppRole authentication (`appRole`)

```yaml
auth:
  appRole:
    path: approle
    roleId: "<vault-role-id>"
    secretRef:
      name: vault-approle
      key: secret-id
```

ESO reads the SecretID from the existing namespaced Kubernetes Secret `vault-approle`, authenticates to Vault using the RoleID and SecretID, and receives a time-limited Vault token. The **SecretID must not appear in GOP YAML, generated manifests, Git, or logs**. A RoleID is not a substitute for a SecretID; depending on operator policy, RoleIDs may also need to be treated as sensitive. The default authentication mount for `path` is `approle`.

The Vault operator must create the AppRole and its Vault policy. In the local test setup, the Vault `default` policy is deliberately disabled. To allow ESO to validate its issued token, the local policy permits `auth/token/lookup-self` in addition to reading the KV secret:

```hcl
path "secret/data/gop/customer-test" {
  capabilities = ["read"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}
```

AppRole still requires an **existing Kubernetes Secret** containing the SecretID in each target namespace. Creating and rotating SecretIDs is outside the scope of the GOP integration.

### Option 3: Kubernetes authentication (`kubernetes`)

```yaml
auth:
  kubernetes:
    mountPath: kubernetes
    role: gop-eso
    serviceAccountRef:
      name: eso-vault-auth
      audiences:
        - vault
```

ESO uses the Kubernetes TokenRequest API to request a short-lived token for the **existing** ServiceAccount `customer-app/eso-vault-auth`. Vault verifies this identity via the Kubernetes TokenReview API and, if authentication succeeds, issues a Vault token with the policies assigned to Vault role `gop-eso`. No pre-provisioned static Vault token or AppRole SecretID is needed.

- `mountPath` defaults to `kubernetes`; `role` and `serviceAccountRef.name` are required by GOP.
- `audiences` is optional in GOP. If provided, it must match the token audience expected by Vault. The value `vault` is an example, **not a GOP-wide default**. Starting with Vault 1.21, the Kubernetes Auth role must specify an audience.
- The Vault role must be restricted to the allowed Kubernetes ServiceAccount names and namespaces. For multiple target namespaces, its bindings must cover all intended identities.
- The Vault operator must configure TokenReview access, including connectivity to the Kubernetes API server and TLS trust. A separate token reviewer requires the `system:auth-delegator` permission (typically granted through a ClusterRoleBinding by the platform administrator). It is **not** the same identity as `eso-vault-auth`.

#### Least privilege for the TokenRequest API

Depending on its chart RBAC configuration, the ESO controller may otherwise be allowed to request tokens for many ServiceAccounts. For Kubernetes Auth, restrict that permission:

```yaml
features:
  secrets:
    externalSecrets:
      helm:
        values:
          rbac:
            serviceAccountTokenCreate: false
```

This disables the chart's broad `create` permission for `serviceaccounts/token`. ESO instead needs a dedicated Kubernetes `Role` and `RoleBinding` in each target namespace. Example for an ESO controller running as ServiceAccount `secrets/external-secrets`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: eso-vault-token-request
  namespace: customer-app
rules:
  - apiGroups: [""]
    resources: ["serviceaccounts/token"]
    resourceNames: ["eso-vault-auth"]
    verbs: ["create"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: eso-vault-token-request
  namespace: customer-app
subjects:
  - kind: ServiceAccount
    name: external-secrets
    namespace: secrets
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: eso-vault-token-request
```

**GOP does not automatically create these ServiceAccounts, Roles, or RoleBindings in the customer cluster.** The operator must provision them; the local preparation script creates them only for k3d tests. The customer's ESO controller permissions must also be reviewed as a whole: the existence of a restrictive `Role` does not rule out additional privileges granted by a `ClusterRoleBinding`.

## Vault servers using a private CA

If the external Vault server uses an HTTPS certificate issued by an internal CA that ESO does not trust by default, GOP can reference an existing namespaced ConfigMap or Secret containing the PEM-encoded CA certificate:

```yaml
vault:
  server: https://vault.example.org
  caProvider:
    type: ConfigMap
    name: external-vault-ca
    key: ca.crt
```

`caProvider` supports `ConfigMap` and `Secret`. The referenced resource must exist in each target namespace; GOP does not create it. `caBundle` is not currently supported. The local HTTP test profiles do not require a CA provider. The Kubernetes API server CA used by Vault for TokenReview is a **separate trust relationship** from the external Vault server CA used by ESO.

## Namespaced SecretStores and the Argo CD operator

GOP generates one namespaced `SecretStore` per target namespace and the associated `ExternalSecret` resources. It does not create a `ClusterSecretStore`. Each `ExternalSecret` references a store in its own namespace, keeping secret and authentication references namespace-scoped. This is particularly important with namespace-scoped Argo CD managed by the operator.

For multiple namespaces, GOP renders the same configured Vault authentication method into each store. Token and AppRole Secret references, or Kubernetes ServiceAccounts, must therefore exist **in each target namespace**. Selecting different Vault roles per target namespace is outside the scope of this extension.

The Argo CD operator must be installed **before GOP** for `operator-*` profiles. Namespaced Argo CD needs permission to manage `SecretStore` and `ExternalSecret` resources and the corresponding resource inclusions. For customer deployments:

- GOP does not receive cluster-admin rights and must not manage Vault authentication backends, Vault policies, token reviewers, or cluster-wide RBAC.
- With `application.skipCrds: true`, the platform administrator must install all required ESO CRDs (and any other necessary CRDs) **in advance**.
- The ESO controller is **not** automatically namespace-scoped just because Argo CD is managed by the operator; ESO's permissions must be assessed separately.
- The local `operator-*` profiles use the k3d test infrastructure. They do not fully reproduce customer OpenShift SCCs, NetworkPolicies, or restricted project privileges.

## Local k3d test

A running k3d cluster with the correct `kubectl` context is required. The scripts launch an **ephemeral** Vault development container outside the cluster and store sample values at `secret/gop/customer-test`. The expected test values are defined in [`external-vault-test-fixture.properties`](../src/test/resources/external-vault-test-fixture.properties). By default, Vault is accessible from k3d at `http://host.k3d.internal:8200`.

| Authentication method | Vault/Kubernetes preparation | GOP profile | Argo CD operator profile |
| --- | --- | --- | --- |
| Token | `prepare-test-vault.sh` | `full-external-vault` | `operator-full-external-vault` |
| AppRole | `prepare-test-vault-approle.sh` | `full-external-vault-approle` | `operator-full-external-vault-approle` |
| Kubernetes | `prepare-test-vault-kubernetes.sh` | `full-external-vault-kubernetes` | `operator-full-external-vault-kubernetes` |

**Caution:** Each preparation script restarts the test Vault. Existing Vault data, including AppRole and Kubernetes Auth configuration, is lost. Run one test profile at a time against the freshly prepared Vault.

### Preparation and GOP deployment

For token authentication:

```bash
./scripts/dev/external-vault/prepare-test-vault.sh
```

For AppRole instead:

```bash
./scripts/dev/external-vault/prepare-test-vault-approle.sh
```

For Kubernetes Auth instead (the k3d cluster must already be running):

```bash
./scripts/dev/external-vault/prepare-test-vault-kubernetes.sh
```

The Kubernetes Auth script creates the authentication ServiceAccount, token reviewer, restricted TokenRequest `Role`/`RoleBinding`, and a `ClusterRoleBinding` for `system:auth-delegator`. It connects the external Vault container to the k3d network, configures the Vault role, and verifies Kubernetes login **before** GOP is started. The reviewer token is supplied to Vault only temporarily with a requested lifetime of four hours; prepare the environment again when it expires. Do not copy these development permissions to a customer cluster without review.

For example, deploy GOP in the regular mode:

```bash
./mvnw -B clean compile exec:java \
  -Dexec.arguments="--profile=full-external-vault-kubernetes"
```

For operator profiles, install the Argo CD operator first (`make install-operator`; see [developer documentation](Developers.md)), then run for example:

```bash
./mvnw -B clean compile exec:java \
  -Dexec.arguments="--profile=operator-full-external-vault-kubernetes"
```

The `compile` phase also copies profile resources to the class directory. Run a fresh build after changing a profile.

### Running integration tests

**Ensure the tests actually execute.** Running `failsafe:integration-test` directly without `test-compile` can report `No tests to run` together with `BUILD SUCCESS`.

Example for Kubernetes Auth in regular mode:

```bash
./mvnw -B test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ExternalVaultProfileTestIT \
  -Dmicronaut.environments=full-external-vault-kubernetes \
  -DfailIfNoTests=true
```

In operator mode, also run the operator checks:

```bash
./mvnw -B test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ExternalVaultProfileTestIT,ArgoCDOperatorProfileTestIT \
  -Dmicronaut.environments=operator-full-external-vault-kubernetes \
  -DfailIfNoTests=true
```

For token or AppRole, substitute the corresponding profile from the table. The tests validate the selected authentication method in the deployed `SecretStore`, the `Ready` conditions of `SecretStore` and `ExternalSecret`, the resulting Kubernetes Secret's values and owner references, and the required authentication/RBAC prerequisites. The tests do not create Vault roles or Kubernetes permissions themselves.

### Troubleshooting and cleanup

```bash
kubectl -n external-vault-test get secretstore external-customer-vault
kubectl -n external-vault-test get externalsecret customer-credentials
kubectl -n external-vault-test get secret customer-credentials
kubectl -n secrets logs deployment/external-secrets --since=10m --tail=100
```

Expect `SecretStore: Ready=True` (`Valid`), `ExternalSecret: Ready=True` (`SecretSynced`), and Kubernetes Secret `customer-credentials`. `InvalidProviderConfig` often indicates an authentication or Vault policy problem. For a `403` on `auth/token/lookup-self`, check the Vault policy. For `no endpoints available for service external-secrets-webhook`, check the webhook status. Do not include SecretIDs, Vault tokens, or decoded secret values in ordinary diagnostic logs.

Remove the ephemeral test Vault after testing:

```bash
docker rm -f external-vault
```

The local development Vault is **not suitable for production use**.

## Further reading

- [ESO: HashiCorp Vault provider](https://external-secrets.io/latest/provider/hashicorp-vault/)
- [ESO: API specification](https://external-secrets.io/latest/api/spec/)
- [ESO: Security best practices](https://external-secrets.io/latest/guides/security-best-practices/)
