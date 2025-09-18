# Content Loader Documentation

This documentation shows the Content Loader feature and its usage. The Content Loader offers the ability of hooking into the GOP installation to deliver your own content.

Example for a GOP (GitOps Playground) content repository:

- Sample [configuration file](https://ecosystem.cloudogu.com/scm/repo/gop/content/code/sources/main/gop-config.yaml/).
- [Directory structure](https://ecosystem.cloudogu.com/scm/repo/gop/content/code/sources) as an example of a folder-based content repository.

# Purpose of the Content Loader

The content loader feature makes your application cloud ready. It gives you the ability to deploy and operate your own application in cloud environments with the GOP. \
It provides the flexibility to deliver more applications using GOP. This customization applies to the Internal Developer Platform (IDP), for example, other ops tools such as monitoring. \
It also applies to end-user applications, for example, replacing the example Petclinic content with real-world applications.

# What does the term “content” mean? 

- Currently, the GOP (version > 0.11.0) consists of sample applications and exercises and their dependencies, in addition to the actual IDP (ArgoCD, Prometheus, etc.).
  - ➡️ “Ready-to-use” provision of GitOps pipelines
- We refer to this as “content”.
- When rolling out GOP the `--content-examples` parameter leads to sample applications being  pushed to Git.
- These applications include:
  - Code (e.g., repo `argocd/petclinic-helm`)
  - Configuration (Argo CD `Application` and YAML resources in the GitOps repo `argocd/example-tenant`)
  - Basic configuration of the tenant (Argo CD `AppProject` and `Application` of Applications in the repo `argocd/argocd`)
  - Some of them contain Jenkins files that describe how to build and push images and start the GitOps process.
  - Dependencies, e.g., `3rd-party-dependencies/gitops-build-lib` and `3rd-party-dependencies/spring-boot-helm-chart`
  - Jenkins job that clones the repos, builds images, and triggers the GitOps process
- After installing the GOP, the sample applications are built by Jenkins and deployed by ArgoCD via GitOps.
- The content loader feature provides the possibility to deliver your own custom content, i.e. real-world applications instead of demos.

# Content Loader Concepts

- The content deployed by GOP can be completely defined via configuration.
- The content is defined in Git repositories, known as content repos.
- There are different types of content repos: `MIRROR`, `COPY`, and `FOLDER_BASED` ([see below](# Different Types of Content Repos)).
- For these types of Content Repos, the `overrideMode` determines how to handle previously existing files in the repo: `INIT`, `UPGRADE`, `RESET` ([see below](# The overrideMode))
- Templating with [Freemarker](https://freemarker.apache.org/) is available in the content files ([see below](# Templating)).
- Multiple content repos can be specified in the `content.repos` field.
  - See the [sample configuration file](https://ecosystem.cloudogu.com/scm/repo/gop/content/code/sources/gop-config.yaml).
- These are merged by the GOP in the defined order in a directory structure.
- This allows you to overwrite files from all repos created by GOP.
  - One use case for this is, for example, a base repository that specifies the basic structure of all GOP instances in a cloud environment and more specialized repositories that contain specific applications.
  - Another use case is to keep the configuration (YAML) in one repo and the code in another in order to deploy multiple examples with the same code. \
    Current examples are `petclinic-plain` and `petclinic-helm`.
- This also allows you to control the configuration of Argo CD and, for example, define different tenants.
- Different content repositories can be created for end-user applications (including their dependencies such as Helm charts or build libraries) as well as IDP applications,  such as monitoring tools.
- To accommodate these different tasks, each repository can be parameterized differently.
- ArgoCD `AppProjects` and `Applications` can be defined in the content.
- Existing repositories, e.g., `argocd/argocd`, can be extended by content (“merge” or git clone + push).
- Jenkins: Automatic generation of Jenkins jobs based on the content.
  - For each SCM Manager namespace found in the content and
  - that contains a `Jenkinsfile`.
- The example content can be activated via the `content.examples` field.
- You have the option to change this via `content.repos`.
- Kubernetes namespaces, e.g., for sample applications (currently `example-tenant-staging`), can be specified via a separate `content.namespaces` field.
  - The namespaces listed therein are deployed by the GOP via GitOps.
  - In each namespace, the configured ImagePullSecrets are automatically generated and RBAC resources and `NetworkPolicies` are set up, which enable Prometheus to access the metrics.
  - This also allows the GOP to create `ProjectRequests` instead of `Namespaces` under OpenShift.
  - The list may contain more namespaces than are used in the content.
  - The namespaces allow templating, e.g., `‘${config.application.namePrefix}example-tenant-staging’, ‘${config.application.namePrefix}example-tenant-production’`

## Different Types of Content Repos

There are different types of content repos: `MIRROR`, `COPY`, and `FOLDER_BASED`.
- `MIRROR` (default): The entire content repo is mirrored to the target repo if it does not yet exist (see overrideMode).
- `COPY`: Only the files (no Git history) are copied to the target repository and committed.
- `FOLDER_BASED`: Using the folder structure in the content repository, multiple repositories can be created and initialized or expanded in the target.

**Global Properties**

- `url` (required field)
- `ref` - Git Reference, that is cloned in Content Repo (branch, tag, commit). \
  Default:
  - `COPY` / `FOLDER_BASED`: Default branch of Repo.
  - `MIRROR`: All branches und tags of Repo
- `overrideMode` (`INIT`, `UPGRADE`, `RESET`) defines how to handle pre-existing files in the repository ([see below](# The overrideMode).
- `username`
- `password`
- `createJenkinsJob` - If `true` and Jenkins is active in GOP, and there is a `Jenkinsfile` in one of the content repositories or the specified `refs`, a Jenkins job is created for the associated SCM Manager namespace.

### Different Types of Content Repos in Detail

#### `MIRROR`

A content repo is mirrored completely (or only a `ref`) to the target repo (including Git history). Caution: Force push is used here! By default, however, only on new repos. If existing repos are also to be written, `overrideMode: RESET` must be set.
Note: The default branch of the source repo is not explicitly set.
If the source repo has a default branch != `main`, it is not applied.

**Properties**

- `target` (required field) target repo, e.g. `namespace/name`
- `targetRef` - Git reference in `target` to which it is pushed(branch or tag).
  - If `ref `is a tag,` targetRef` is also treated as a tag.
  - Exception:` targetRef` is a full ref such as` refs/heads/my-branch` or `refs/tags/my-tag`.
  - If `targetRef` is empty, the source ref is used by default.


#### `COPY`

Only the files (no Git history) are copied and committed to the target repo.

**Properties**

- `target` (required) Target repo, e.g. `namespace/name`
- `targetRef` - Git reference in `target` to which is pushed (branch or tag). \
  - If ref is a tag, targetRef is also treated as a tag. \
  - Exception:` targetRef` is a complete `ref `such as `refs/heads/my-branch` or `refs/tags/my-tag`. \
  - If` targetRef` is empty, the source `ref `is used by default.
- `path `- Folder within the content repo from which to copy
- `templating `- If `true`, all `.ftl` files are rendered by [Freemarker](https://freemarker.apache.org/) before being pushed to the target ([see below](# Templating)).


#### `FOLDER_BASED`
- Using the folder structure in the content repository, multiple repositories can be created in the target and initialized or expanded using `COPY`.
- Specifically: The top two directory levels of the repository determine the target repositories in the GOP.
- Example: The contents of the `example-tenant/petclinic-plain` folder are pushed to the `gitops` repository in the `example-tenant` namespace.

![content-hook-folderbased.png](Images/8Mc_Image_1.png)

This allows, for example, additional Argo CD applications to be added and even your own tenants to be deployed.

**Properties**

- `target` (required)
- `path` - source folder in the content repository used for copying<
- `templating` - If `true`, all `.ftl` files are rendered by [Freemarker](https://freemarker.apache.org/) before being pushed to the target ([see below](# Templating)).

# The overrideMode

For these types of Content Repos, the `overrideMode` determines how to handle previously existing files in the repo: `INIT`, `UPGRADE`, `RESET`.
- `INIT` (default): Only push if the repository does not exist
- `UPGRADE`: Delete all files after cloning the source – files that are not in the content will be deleted.
- `RESET`: Clone and copy – existing files are overwritten, files that are not in the content are retained. \

**Note** \
With `MIRROR`, `RESET` does not reset the entire repository. Specific effect: Branches that exist in the target but not in the source are retained.

**Important** \
If existing repositories of the GOP are to be extended, e.g., `cluster-resources`, the `overrideMode` must be set to `UPGRADE`.

# Templating
When `templating `is enabled, all files ending in `.ftl` are rendered using [Freemarker](https://freemarker.apache.org/) during GOP installation and the result is created under the same name without the `.ftl` extension.
The entire configuration of the GOP is available as` config` in the templates.
In addition, the people who write the content have the option of defining their own variables (`content.variables`).
This makes it possible to write parameterizable content that can be used for many instances.
In [Freemarker](https://freemarker.apache.org/), you can use static methods from GOP and JDK. An [example from the GOP code](https://github.com/cloudogu/gitops-playground/blob/0.11.0/applications/cluster-resources/monitoring/prometheus-stack-helm-values.ftl.yaml#L111):

```yaml
<#assign DockerImageParser=statics['com.cloudogu.gitops.utils.DockerImageParser']>
# ...
  <#if config.features.monitoring.helm.prometheusOperatorImage?has_content>
  <#assign operatorImageObject = DockerImageParser.parse(config.features.monitoring.helm.prometheusOperatorImage)>
image:
  registry  : ${operatorImageObject.registry}
  repository: ${operatorImageObject.repository}
  tag       : ${operatorImageObject.tag}
  </#if>
```

# Example-Use Cases

## [Mirror the entire repository on every call](https://ecosystem.cloudogu.com/scm/repo/gop/content/code/sources/main/#komplettes-repo-bei-jedem-aufruf-spiegeln)
```yaml
    - url: 'https://github.com/cloudogu/spring-boot-helm-chart'
      target: '3rd-party/spring-boot-helm'
      overrideMode: RESET
```


## Create additional tenant in Argo CD
```yaml
    - url: 'https://example.com/scm/repo/gop/content'
      username: 'abc'
      password: 'ey...' # zB API Token von SCM-Manager
      templating: true
      type: FOLDER_BASED
      overrideMode: UPGRADE
```

In this repo, the folder structure is as follows: [argocd/argocd.](https://ecosystem.cloudogu.com/scm/repo/gop/content/code/sources/argocd/argocd)

## Mirror/copy repo and add specific files
For example, to create a `Dockerfile` and `Jenkinsfile` and then create a Jenkins job. This example shows the `MIRROR` use case. As an alternative you can add type `COPY` in the first repo (petclinic). Reminder: no type means MIRROR (default).
```yaml
    - url: https://github.com/cloudogu/spring-petclinic
      target: argocd/petclinic-plain
      ref: feature/gitops_ready
      targetRef: main
      overrideMode: UPGRADE
      createJenkinsJob: true
    - url: 'https://example.com/scm/repo/gop/content'
      username: 'abc'
      password: 'ey...' # zB API Token von SCM-Manager
      templating: true
      type: FOLDER_BASED
      overrideMode: UPGRADE
```

