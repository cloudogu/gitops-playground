Architecture Decision Records
====

Bases on [this template](https://adr.github.io/madr/examples.html).

## Deploying Cluster Resources with Argo CD using inline YAML

### Context and Problem Statement

There are multiple options for deploying cluster resources as Helm charts with Argo CD.

Having the `values.yaml` as a first-class file (as opposed to inline YAML in the `Application`) has advantages, e.g. 
* it's easier to handle than inline YAML, e.g. for local testing without Argo CD.
* It would also suit our repo structure better (`argocd` folder -> `Application` YAML; `apps` folder -> `values.yaml`).

### Considered Options

* Umbrella Charts: Likely [no support for using credentials](https://github.com/argoproj/argo-cd/issues/7104#issuecomment-995366406).  
  In addition, no support for [Charts from Git](https://github.com/helm/helm/issues/9461). For the latter, there [is a helm plugin](https://github.com/aslafy-z/helm-git),
  but [installing Helm plugins into Argo CD](https://github.com/argoproj/argo-cd/blob/v2.6.7/docs/user-guide/helm.md#helm-plugins)
  would make things too complex for our taste. Also using 3rd-party-plugins is always a risk, in terms of security and maintenance.
* Multi-source `Application`s: These are the solution we have been waiting for, but as of argo CD 2.7 they're still in beta.
  We experienced some limitations with multi-source apps in the UI and therefore refrain from using multi source repos in production at this point.
* `values.yaml` inlined into Argo CD `Application` is the only alternative

### Decision Outcome

We decided  to use Argo CD `Application`s with inlined `values.yaml` because it's the only other options. 
We hope to change to multi-source `Applications` once they are generally available.