Architecture Decision Records
====

Bases on [this template](https://adr.github.io/madr/examples.html).

## Table of contents

<!-- Update with ` doctoc --notitle docs/architecture-decision-records.md --maxlevel 2`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->

- [Using jsonschema-generator to generate a schema for config file](#using-jsonschema-generator-to-generate-a-schema-for-config-file)
- [Using Retrofit as API client for SCM-Manager](#using-retrofit-as-api-client-for-scm-manager)
- [Using Templating Mechanism for Generating Repositories](#using-templating-mechanism-for-generating-repositories)
- [Deploying Cluster Resources with Argo CD using inline YAML](#deploying-cluster-resources-with-argo-cd-using-inline-yaml)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->


## Using jsonschema-generator to generate a schema for config file

`com.github.victools:jsonschema-generator`

### Context and Problem Statement

We want to provide the possibility to configure the problem with a configuration file 
rather than command line parameters.
This configuration file should be easy to use and hard to misuse.
Thus, we want to provide IDE autocompletion and error messages for typos.
We use a json schema for both: IntelliJ support json schemas for JSON and YAML files.
Additionally, we can use the same schema to validate the config file.

### Considered Options

There are several option for schema generation:

* by hand
* https://github.com/saasquatch/json-schema-inferrer
* https://github.com/victools/jsonschema-generator
* https://github.com/FasterXML/jackson-module-jsonSchema
* https://github.com/fxg42/groovyschema

### Decision Outcome

#### by hand
Creating the schema handwritten is cumbersome and prone to errors.
Especially considering common options that are relevant for most/all values (e.g. `additionalProperties`).

#### saasquatch/json-schema-inferrer

It is officially linked at https://json-schema.org/implementations.html#schema-generators.
However, deriving the schema from data is prone to error. 
Especially if we support union types such as `null|string`.

#### victools/jsonschema-generator

It is also officially linked at https://json-schema.org/implementations.html#schema-generators.
It generates a schema by declaring that schema using classes.
Furthermore, it integrates with Jackson, which we already include in the project.
Therefore, we can use the defined schema to provide a type configuration in future work.

#### FasterXML/jackson-module-jsonSchema

Being a Jackson module, it directly integrates with Jackson.
However, there are no plans to support Jackson 3.

#### fxg42/groovyschema

It is a tool written in Groovy. 
However, defining the schema is very close to writing it by hand as it only offers an
API on top of an untyped map representing the JSON schema.
Furthermore, latest commits date back 9 years.

#### Outcome

We decided to use `victools/jsonschema-generator` as it integrates well with libraries we already include,
opens the possibility to use it for future work and is officially linked at json-schema.org.


## Using Retrofit as API client for SCM-Manager

### Context and Problem Statement

We want to use the SCM-Manager REST-API to delete users and repositories
for cleaning up the playground.
In the future, we want to use the same client to create resources when moving away from bash.

There is no official API client for SCM-Manager anymore.
There is a OpenAPI document for the API.

### Considered Options

* Using a [client generator](https://github.com/OpenAPITools/openapi-generator/tree/master)
* Using hand rolled API client based on an HTTP client
* Using Retrofit

### Decision Outcome

The client generator for groovy does not support basic authentication needed for interfacing with SCM-Manager.
The java generator needs various dependencies that we would need to introduce into the project.
Both have mediocre support for specifying a base url at runtime.

Hand rolling an API based on HTTP requires a lot of effort not only initially, but for every resource added to the client.

Retrofit offers a declarative approach to define API clients.
Furthermore, it has first-class support for specifying a base url.
Retrofit uses reflection to generate the client. 
As a result, we need to configure GraalVM appropriately.

We decided to use Retrofit due to its small footprint and because we already integrated OkHttp.
Additionally, creating an API endpoint in Retrofit requires little effort.

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
* [Micronaut-compatible Library](https://micronaut-projects.github.io/micronaut-views/latest/guide/#templates)

Micronaut aims to be compatible with groovy and graal. 

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
