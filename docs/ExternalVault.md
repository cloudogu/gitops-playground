# Using an external HashiCorp Vault with External Secrets Operator

GOP can install and configure the External Secrets Operator (ESO) without installing the GOP-managed Vault. In this mode,
ESO reads application or workload secrets from an already existing external HashiCorp Vault and materializes them as
Kubernetes `Secret` resources.

This flow is intended for secrets that are needed **after** GOP has started. It does not cover GOP bootstrap secrets that
are required before ESO is installed and configured.

## Prerequisites

Before starting GOP, make sure that:

- a HashiCorp Vault already exists and is reachable from the Kubernetes cluster;
- the required secret values already exist in Vault;
- the configured KV secrets engine mount and version match the Vault setup;
- every target namespace already exists or is created as part of the GOP content configuration;
- a Kubernetes Secret containing the Vault access token exists in every target namespace that receives a namespaced
  `SecretStore`;
- the Vault token is restricted to the paths that ESO needs to read.

GOP does not create the external Vault, does not write secret values to it and does not copy the Vault token into the
cluster-resources Git repository. The generated `SecretStore` only contains a `tokenSecretRef`. GOP itself never reads
the synchronized application secret values, so those values are not written to GOP logs.

## Configuration

The following example reads `username` and `password` from the Vault secret `gop/customer` and creates the Kubernetes
Secret `customer-credentials` in namespace `customer-app`:

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
          data:
            username: username
            password: password
```

The keys below `data` map Kubernetes Secret keys to properties in the remote Vault secret. In the example above, the
resulting Kubernetes Secret contains the keys `username` and `password`.

GOP generates one namespaced `SecretStore` per target namespace and the configured `ExternalSecret` resources. ESO then
reads the values from Vault and creates the target Kubernetes Secrets.

## Required Vault token Secret

The Vault token is intentionally not part of the GOP configuration. Create the referenced Kubernetes Secret before the
ExternalSecret is reconciled, for example:

```bash
kubectl -n customer-app create secret generic vault-token \
  --from-literal=token='<vault-token>'
```

When multiple namespaces are configured, the referenced token Secret must exist in every namespace because the generated
`SecretStore` is namespaced.

## Local k3d test

Create or select a local k3d cluster first and make sure `kubectl` points to it. For example:

```bash
./scripts/init-cluster.sh
```

For local development, a Vault dev server can then be started outside the k3d cluster. The repository contains a helper that
starts the Docker container, writes a test secret and creates the token Secret in the test namespace:

```bash
./scripts/dev/external-vault/prepare-test-vault.sh
```

The command prints the Vault URL that is reachable from k3d. With the default port it is:

```text
http://host.k3d.internal:8200
```

The helper creates this test data:

```text
Vault path: secret/gop/customer-test
username:   customer-user
password:   customer-password
```

and the Kubernetes authentication Secret:

```text
namespace: external-vault-test
secret:    vault-token
key:       token
```

After preparing the test Vault, run GOP with the dedicated development/integration profile:

```bash
./mvnw exec:java -Dexec.arguments="--profile=full-external-vault"
```

Then run the integration test:

```bash
./mvnw integration-test -Dmicronaut.environments=full-external-vault
```

The integration test verifies that:

- ESO is installed;
- the GOP-managed Vault is not installed;
- the configured `SecretStore` and `ExternalSecret` exist;
- ESO creates `Secret/customer-credentials` from the external Vault values;
- the resulting Kubernetes Secret is owned by the `ExternalSecret`.

Useful manual checks are:

```bash
kubectl -n external-vault-test get secretstore external-customer-vault
kubectl -n external-vault-test get externalsecret customer-credentials
kubectl -n external-vault-test get secret customer-credentials
```

To inspect the synchronized values during local development:

```bash
kubectl -n external-vault-test get secret customer-credentials \
  -o jsonpath='{.data.username}' | base64 -d; echo

kubectl -n external-vault-test get secret customer-credentials \
  -o jsonpath='{.data.password}' | base64 -d; echo
```

Clean up the external test Vault afterwards:

```bash
docker rm -f external-vault
```

The Vault dev server is ephemeral and must not be used for production environments.
