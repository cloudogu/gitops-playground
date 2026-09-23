# Development helpers

Development-only helper files are grouped by use case:

- `airgapped/`: prepare and configure a local air-gapped test cluster.
- `network-policies/`: local overrides and examples for NetworkPolicy tests.
- `registries/`: local registry and multi-registry test helpers.
- `secrets/`: Kubernetes Secret examples used by local and integration tests.

These files are intended for development and integration testing only. Product configuration belongs in the regular GOP configuration and profiles.
