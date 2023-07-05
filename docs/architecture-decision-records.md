Architecture Decision Records
====

Bases on [this template](https://adr.github.io/madr/examples.html).

## Using Templating Mechanism for Generating Repositories 

### Context and Problem Statement

We need to configure various values depending on the environment and command line arguments.
In the past, we used the following mechanisms:

* `String.replace` on files
* Building YAML in Groovy and appending to existing YAML file
* Building a `Map`, then transforming into YAML and writing to file

Having various mechanisms spread across the codebase is difficult to find.
Furthermore, having baseline files, which will be modified out of band, can trick the reader 
into thinking that file's content is final.

### Considered Options

We want to use templating to alleviate these problems.
We need to template YAML files as well as Jenkinsfiles.

* Groovy Templating
* Micronaut-compatible Library

### Decision Outcome

#### Groovy Templating

Groovy Templating has a very small footprint and does not rely on a third-party library.
However, it relies on dynamic code execution and is therefore incompatible with GraalVM.

#### Micronaut-compatible Library

Although we do not want to use Micronauts View functionality, we expect micronaut
to work well with GraalVM.

**Thymeleaf** is a large templating engine for Java.
We decided against it as it seemed cumbersome to configure.

**Apache Velocity**'s templating syntax uses a simple hash symbol to identify templating language constructs.
We decided against it as it might conflict with the template's content (e.g. comment in Jenkinsfile or YAML).

**Apache Freemarker** is relatively easy to configure and does not have conflicting templating language constructs.
Furthermore, it offers the tag `<#noparse>` to disable parsing content as a template.
Using this directive, we do not need to escape other symbols (e.g. `$`) that would be picked up from the
templating engine.

We decided to use **Apache Freemarker**


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
