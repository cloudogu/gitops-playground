# CRDs for Ingress, Monitoring and External Secrets Operator

GOP prepares the CRDs for enabled tools before deploying those tools. These include the Gateway API bundle for Ingress, five Prometheus Operator CRDs for Monitoring, and the CRDs from the rendered ESO chart.

| Setting | Explicit bootstrap behavior | CRD permissions for the GOP identity |
| --- | --- | --- |
| `application.skipCrds: false` (default) | GOP reads each CRD by name. It creates missing CRDs and reuses existing CRDs with their spec, labels and annotations intact. In both cases, GOP waits for `Established=True`. | `get`, plus `create` for missing CRDs |
| `application.skipCrds: true` (`--skip-crds`) | GOP skips the bootstrap, including all CRD checks. The platform must provide the required CRDs before tool deployment. | No CRD permissions required for this bootstrap |

GOP skips the bootstrap for disabled tools. Subsequent runs use the same cluster-scoped CRDs, even with a different namespace prefix. If Kubernetes returns `409 AlreadyExists` because another client created the CRD concurrently, GOP reads it again and waits for it to become established. If a GET request is denied, the bootstrap stops.

## Gateway API admission policies

When Ingress is enabled and `application.skipCrds: false`, GOP first creates any missing Gateway API CRDs and waits for them to become established. Before deploying Traefik, GOP installs or updates the `ValidatingAdmissionPolicy` and then the `ValidatingAdmissionPolicyBinding`, both named `safe-upgrades.gateway.networking.k8s.io`. These resources enforce the Gateway API CRD upgrade rules. GOP reads both phases from the same pinned bundle, using the local copy in air-gapped installations. If either admission resource is missing, GOP stops before applying the pair or deploying Traefik.

This step runs independently of Argo CD operator mode. With `application.skipCrds: true` or Ingress disabled, GOP skips both the Gateway API CRD bootstrap and the admission policy installation. The GOP identity needs cluster-wide `create`, `get`, and `update` permissions for the admission policy and binding in the `admissionregistration.k8s.io` API group.

## Upgrades and compatibility

The bootstrap preserves existing CRDs during tool or chart upgrades. Coordinate any required CRD upgrades with the platform administrators. `Established=True` confirms that the CRD is registered. GOP does not check which API versions the CRD serves for the tool or whether its schema is compatible. A missing version may only become apparent during an Argo CD sync or when the operator processes a resource.

## Argo CD and ESO

In Argo CD operator mode, GOP sets `crds.enabled: false` for the Monitoring chart and `installCRDs: false` for the ESO chart by default. Explicit Helm values can override these defaults and take effect on the first sync. Coordinate cluster-wide changes with the platform administrators. The bootstrap permissions listed above apply only to this GOP step.

Even with `installCRDs: false`, the ESO certificate controller can change the Service reference or the CA bundle on selected existing CRDs that use a conversion webhook. This controller action takes place after the GOP bootstrap and is outside its scope. Sharing a central ESO installation and potential conflicts between installations are the subject of a separate investigation.
