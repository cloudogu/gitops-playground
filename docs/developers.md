# Developers

This document collects some information about things developers of the gop should know or
problems they might face when they try to run and test their changes.
It provides workarounds or solutions for the given issues.

## Testing

There is an end to end testing script inside the `./scripts` folder. It scans for builds and starts them, waits until their finished or fail and returns the result.

### Usage

You can use it by executing `groovy ./scripts/e2e.groovy --url http://localhost:9090 --user admin --password admin`

### Options

- `help` - Print this help text and exit
- `url` - The Jenkins-URL to connect to
- `user`- The Jenkins-User for login
- `password` - Jenkins-Password for login
- `fail` - Exit on first build failure
- `interval` - Interval for waits while scanning for builds
- `debug` - Set log level to debug

## Jenkins plugin installation issues

We have had some issues with jenkins plugins in the past due to the installation of the latest versions.
Trying to overcome this issue we pinned all plugins within `scripts/jenkins/plugins/plugins.txt`.
These pinned plugins get downloaded within the docker build and saved into a folder as `.hpi` files. Later on
when configuring jenkins, we upload all the plugin files with the given version.

Turns out it does not completely circumvent this issue. In some cases jenkins updates these plugins automagically (as it seems) when installing the pinned version fails at first.
This again may lead to a broken jenkins, where some of the automatically updated plugins have changes within their dependencies. These dependencies than again are not updated but pinned and may cause issues.

Since solving this issue may require some additional deep dive into bash scripts we like to get rid of in the future, we decided to give some hints how to easily solve the issue (and keep the plugins list up to date :]) instead of fixing it with tremendous effort.

### Solution

* Determine the plugins that cause the issue
  * inspecting the logs of the jenkins-pod
  * jenkins-ui (http://localhost:9090/manage)

![Jenkins-UI with broken plugins](example-plugin-install-fail.png)

* Fix conflicts by updating the plugins with compatible versions
  * Update all plugin versions via jenkins-ui (http://localhost:9090/pluginManager/) and restart

![Jenkins-UI update plugins](update-all-plugins.png)

* Verify the plugin installation
  * Check if jenkins starts up correctly and builds all example pipelines successfully
  * verify installation of all plugins via jenkins-ui (http://localhost:9090/script) executing the following command

![Jenkins-UI plugin list](get-plugin-list.png)

```groovy
Jenkins.instance.pluginManager.plugins.collect().sort().each {
  println "${it.shortName}:${it.version}"
}
```

* Share and publish your plugin updates
  * Make sure you have updated `plugins.txt` with working versions of the plugins
  * commit and push changes to your feature-branch and submit a pr

## Local development

* Run only groovy scripts - allows for simple debugging
  * Run from IDE, works e.g. with IntelliJ IDEA 
    Note: If you encounter `error=2, No such file or directory`,
    it might be necessary to explicitly set your `PATH` in Run Configuration's Environment Section.
  * From shell:
    * [Provide dependencies](#providing-dependencies)
    * Run
      ```shell
       groovy  -classpath src/main/groovy src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy
       ```
* Running the whole `apply.sh` (which in turn calls groovy)
  * Build and run dev Container:
    ```shell
    docker buildx build -t gitops-playground:dev --build-arg ENV=dev  --progress=plain .
    docker run --rm -it -u $(id -u) -v ~/.k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
      --net=host gitops-playground:dev <params>
     ```
  * Locally:
    * Provide `gitops-playground.jar` for `apply-ng.sh`:
      ```bash
      ./mvnw package -DskipTests
      ln -s target/gitops-playground-cli-0.1.jar gitops-playground.jar 
       ```
    * Just run `scripts/apply.sh <params>`.  
      Hint: You can speed up the process by installing the Jenkins plugins from your filesystem, instead of from the internet.  
      To do so, download the plugins into a folder, then set this folder vie env var:  
      `JENKINS_PLUGIN_FOLDER=$(pwd) scripts/apply.sh <params>`.  
      A working combination of plugins be extracted from the image:  
      ```bash
      id=$(docker create ghcr.io/cloudogu/gitops-playground)
      docker cp $id:/gitops/jenkins-plugins .
      docker rm -v $id
      ```

## Development image

An image containing groovy and the JDK for developing inside a container or cluster is provided for all image version
with a `-dev` suffix.

e.g.
* ghcr.io/cloudogu/gitops-playground:dev
* ghcr.io/cloudogu/gitops-playground:latest-dev
* ghcr.io/cloudogu/gitops-playground:d67ec33-dev

It can be built like so:

```shell
docker buildx build -t gitops-playground:dev --build-arg ENV=dev  --progress=plain  .  
```
Hint: uses buildkit for much faster builds, skipping the static image stuff not needed for dev.
With Docker version >= 23 you can also use `docker build`, because buildkit is the new default builder.

## Implicit + explicit dependencies

The GitOps Playground comprises a lot of software components. The versions of some of them are pinned within this 
repository so need to be upgraded regularly.

* Kubernetes [in Terraform](../terraform/vars.tf) and locally [k3d](../scripts/init-cluster.sh),
* [k3d](../scripts/init-cluster.sh), [Upgrade to v5 WIP](https://github.com/cloudogu/gitops-playground/tree/feature/k3d-version5) 
* [Groovy libs](../pom.xml) + [Maven](../.mvn/wrapper/maven-wrapper.properties)
* Installed components
  * Jenkins 
    * Helm Chart 
    * Plugins
    * Pod `tmp-docker-gid-grepper`
    * `dockerClientVersion`
    * Init container `create-agent-working-dir`
    * Agent Image
  * SCM-Manager Helm Chart + Plugins
  * Docker Registry Helm Chart
  * GitOps Operators
    * ArgoCD Helm Chart
    * Flux v2 Helm Charts
  * Grafana + Prometheus [Helm Charts](../src/main/groovy/com/cloudogu/gitops/ApplicationConfigurator.groovy)
  * Vault + ExternalSerets Operator [Helm Charts](../src/main/groovy/com/cloudogu/gitops/ApplicationConfigurator.groovy)
* Applications
  * GitOps-build-lib + `buildImages`
  * ces-build-lib
  * Spring PetClinic
  * NGINX Helm Chart
* Dockerfile
  * Alpine
  * GraalVM
  * JDK
  * Groovy
  * musl & zlib
  * Packages installed using apk, gu, microdnf

## Upgrade Flux

```shell
  PW=admin
flux bootstrap git \
  --url=http://localhost:9091/scm/repo/fluxv2/gitops \
  --allow-insecure-http=true \
  --branch=main \
  --path=./clusters/gitops-playground \
  --token-auth \
  --username=admin \
  --password=${PW} \
  --interval=10s
# Once we have webhooks setup we no longer need this short interval
```

Then 
* replace contents in `fluxv2/clusters/gitops-playground/flux-system` by the one in http://localhost:9091/scm/repo/fluxv2/gitops
* In `gotk-sync.yaml` 
  * update url to http://scmm-scm-manager.default.svc.cluster.local/scm/repo/fluxv2/gitops
  * Set `--interval=10s` in kustomization

In case of error
```shell
flux uninstall
```


## GraalVM

The playground started up as a collection of ever-growing shell scripts. Once we realized that the playground is here to stay, we started looking into alternatives to keep our code base in a maintainable state.

Our requirements:
* a scriptable language, so we could easily explore new features at customers (see [Dev image](#development-image)), 
* the possibility of generating a native binary, in order to get a more lightweight (in terms of vulnerabilities) resulting image.

As the team at the time had a strong Java background and was already profound in groovy, e.g. from `Jenkinsfiles`, we decided to use groovy. 
We added Micronaut, because it promised good support for CLI, groovy and GraalVM for creating a static image.
It turned out that Micronaut did not support GraalVM native images for groovy. In order to get this to work some more 
hacking was necessary. See [`graal` package](../src/main/groovy/com/cloudogu/gitops/graal) and also the `native-image` stage in [`Dockerfile`](../Dockerfile).

### Graal package

In order to make Groovy's dynamic magic work in a Graal native image, we use some classes from the [clockwork-project](https://github.com/croz-ltd/klokwrk-project) (see this [package](../src/main/groovy/com/cloudogu/gitops/graal/groovy)). Theses are picked during `native-image` compilation via Annotations.
The native image compilation is done during `docker build`. See `Dockerfile`.

### Dockerfile

The `native-image` stage takes the `playground.jar` and packs it into a statically executable binary.

Some things are a bit special for the playground:
* We compile groovy code, which requires some parameters (e.g. `initialize-at-run-time`)
* We want to run the static image on alpine, so we need to compile it against libmusl instead of glibc.
  * For that we need to download and compile musl and its dependency zlib 😬 (as stated in [this issue](https://github.com/oracle/graal/issues/2824))
  * See also [Graal docs](https://github.com/oracle/graal/blob/vm-ce-22.2.0.1/docs/reference-manual/native-image/guides/build-static-and-mostly-static-executable.md)
* We run the playground jar with the native-image-agent attached a couple of times (see bellow)
* We use the JGit library, which is not exactly compatible with GraalVM (see bellow)

### Create Graal native image config

The `RUN java -agentlib:native-image-agent` instructions in `Dockerfile` execute the `playground.jar` with the agent attached.
These runs create static image config files for some dynamic reflection things. 
These files are later picked up by the `native-image`.
This is done to reduce the chance of `ClassNotFoundException`s, `MethodNotFoundException`s, etc. at runtime.

In the future we could further improve this by running unit test with the graal agent to get even more execution paths.

However, this leads to some mysterious error `Class initialization of com.oracle.truffle.js.scriptengine.GraalJSEngineFactory failed.` 🤷‍♂️
Also, a lot of failing test with `FileNotFoundException` (due to `user.dir`?).
If more Exceptions should turn up in the future we might follow up on this.
Then, we might want to add an env var that actually calls JGit (instead of the mock) in order to execute JGit code with 
the agent attached.
```shell
./mvnw test "-DargLine=-agentlib:native-image-agent=config-output-dir=conf" --fail-never
```
At the moment this does not seem to be necessary, though.

### JGit

JGit seems to cause [a lot](https://bugs.eclipse.org/bugs/show_bug.cgi?id=546175) [of](https://github.com/quarkusio/quarkus/issues/21372) [trouble](https://github.com/miguelaferreira/issue-micronaut-graalvm-jgit) with GraalVM.  
Unfortunately for the playground, JGit is a good choice: The only(?) actively developed native Java library for git. 
In the long run, we want to get rid of the shell-outs and the `git` binary in the playground image in order to reduce
attack surface and complexity. So we need JGit.

So - how do we get JGit to work with GraalVM?

Fortunately, Quarkus provides [an extension to make JGit work with GraalVM](https://github.com/quarkiverse/quarkus-jgit/tree/3.0.0).
Unfortunately, the playground uses Micronaut and can't just add this extension as a dependency.
That's why we picked some classes into the Graal package (see this [package](../src/main/groovy/com/cloudogu/gitops/graal/jgit)).
Those are picked up by `native-image` binary in `Dockerfile`.
In addition, we had to add some more parameters (`initialize-at-run-time` and `-H:IncludeResourceBundles`) to `native-image`.

For the moment this works and hopefully some day JGit will have support for GraalVM built-in. 
Until then, there is a chance, that each upgrade of JGit causes new issues. If so, check if the code of the Quarkus 
extension provides solutions. 🤞 Good luck 🍀. 