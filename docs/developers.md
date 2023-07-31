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
    * [Provide `gitops-playground.jar` for scripts](#provide-gitops-playgroundjar-for-scripts)
    * Run
      ```shell
      java -classpath $PWD/gitops-playground.jar \
        org.codehaus.groovy.tools.GroovyStarter \
        --main groovy.ui.GroovyMain \
        -classpath "$PWD"/src/main/groovy \
        "$PWD"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy \
        <yourParamsHere>
       ```
* Running the whole `apply.sh` (which in turn calls groovy)
  * Build and run dev Container:
    ```shell
    docker buildx build -t gitops-playground:dev --build-arg ENV=dev  --progress=plain .
    docker run --rm -it -u $(id -u) -v ~/.k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
      --net=host gitops-playground:dev <params>
     ```
  * Locally:
    * [Provide `gitops-playground.jar` for scripts](#provide-gitops-playgroundjar-for-scripts)
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

### Provide `gitops-playground.jar` for scripts

```bash
./mvnw package -DskipTests
ln -s target/gitops-playground-cli-0.1.jar gitops-playground.jar 
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

### FAQ

#### SAM conversion problem

```
org.codehaus.groovy.runtime.typehandling.GroovyCastException: 
Cannot cast object '[...]closure1@27aa43a9' 
with class '[...]closure1' 
to class 'java.util.function.Predicate'
```

Implicit closure-to-SAM conversions will not always happen.
You can configure an explicit list in [resources/proxy-config.json](../src/main/resources/proxy-config.json) and [resources/reflect-config.json](../src/main/resources/reflect-config.json).


# External registry for development

If you need to emulate an "external", private registry with credentials, use the following.

Write this `harbor-values.yaml`:

```yaml
expose:
  type: nodePort
  nodePort:
    ports:
      http:
        # docker login localhost:$nodePort -u admin -p Harbor12345
        # Web UI: http://localhost:nodePort
        # !! When changing here, also change externalURL !!
        nodePort: 30002

  tls:
    enabled: false

externalURL: http://localhost:30002

internalTLS:
  enabled: false

# Needs less resources but forces you to push images on every restart
#persistence:
#enabled: false

chartMuseum:
  enabled: false

clair:
  enabled: false

trivy:
  enabled: false

notary:
  enabled: false
```

Then install it like so:
```bash
helm upgrade -i my-harbor harbor/harbor -f harbor-values.yaml --version 1.12.2 --namespace harbor --create-namespace
```
Once it's up and running either create your own private project or just set the existing `library` to private:
```bash
curl -X PUT -u admin:Harbor12345 'http://localhost:30002/api/v2.0/projects/1'  -H 'Content-Type: application/json' \
--data-raw '{"metadata":{"public":"false", "id":1,"project_id":1}}'
```

Then either import external images like so (requires `skopeao` but no prior pulling or insecure config necessary):
```bash
skopeo copy docker://bitnami/nginx:1.25.1 --dest-creds admin:Harbor12345 --dest-tls-verify=false  docker://localhost:30002/library/nginx:1.25.1
```

Alternatively, you could push existing images from your docker daemon.
However, this takes longer (pull first) and you'll have to make sure to add `localhost:30002` to `insecure-registries` in `/etc/docker/daemon.json` and restart your docker daemon first.

```bash
docker login localhost:30002 -u admin -p Harbor12345
docker tag bitnami/nginx:1.25.1 localhost:30002/library/nginx:1.25.1
docker push localhost:30002/library/nginx:1.25.1
```

To make the registry credentials know to kubernetes, apply the following to *each* namespace where they are needed: 

```bash
kubectl create secret docker-registry regcred \
--docker-server=localhost:30002 \
--docker-username=admin \
--docker-password=Harbor12345
kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "regcred"}]}'
```

This will work for all pods that don't use their own ServiceAccount.
That is, for most helm charts, you'll need to set an individual value.

# Emulate an airgapped environment

Let's set up our local playground to emulate an airgapped env, as some of our customers have.

Note that with approach bellow, the whole k3d cluster is airgapped with one exception: the Jenkins agents can work around this.
To be able to run the `docker` plugin in Jenkins (in a k3d cluster that only provides containerd) we mount the host's 
docker socket into the agents. 
From there it can start containers which are not airgapped.
So this approach is not suitable to test if the builds use any public images.
One solution could be to apply the `iptables` rule mentione bellow to `docker0` (not tested).

The approach discussed here is suitable to check if the cluster tries to load anything from the internet, 
like images or helm charts.

## Setup cluster

```
scripts/init-cluster.sh --bind-localhost=false --cluster-name=airgapped-playground
# This will start the cluster in its own network namespace, so no accessing via localhost from your machine
# Note that at this point the cluster is not yet airgapped

# Get the "nodeport" IP
K3D_NODE=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-airgapped-playground-server-0)
 
# Now init some apps you want to have running (e.g. harbor) before going airgapped
helm upgrade  -i my-harbor harbor/harbor -f harbor-values.yaml --version 1.12.2 --namespace harbor --set externalURL=http://$K3D_NODE:30002 --create-namespace
```

Keep kubectl working when airgapped by setting the local IP of the container inside kubeconfig in `~/.k3d/...`
```bash
sed -i -r 's/0.0.0.0([^0-9]+[0-9]*|\$)/${K3D_NODE}:6443/g' ~/.k3d/kubeconfig-airgapped-playground.yaml
```
You can switching to the airgapped context in your current shell like so:
```shell
export KUBECONFIG=$HOME/.k3d/kubeconfig-airgapped-playground.yaml
```

TODO also replace in `~/.kube/config` for more convenience. 
In there, we need to be more careful, because there are other contexts. This makes it more difficult.

## Provide images needed by playground

First, let's import necessary images into harbor using `skopeo`. 
With `skopeo`, this process is much easier than with `docker` because we don't need to pull the images first.
You can get a list of images from a running playground that is not airgapped.

```bash
# Add more images here, if you like
# We're not adding registry, scmm, jenkins and argocd here, because we have to install them before we go offline (see bellow for details).
IMAGE_PATTERNS=('external-secrets' \
  'vault' \
  'prometheus' \
  'grafana' \
  'sidecar' \
  'nginx')
BASIC_SRC_IMAGES=$(
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*]}{range .spec.containers[*]}{'\n'}{.image}{end}{end}" \
  | grep -Ff <(printf "%s\n" "${IMAGE_PATTERNS[@]}") \
  | sed 's/docker\.io\///g' | sort | uniq)
BASIC_DST_IMAGES=''

# Switch context to airgapped cluster here, e.g.
export KUBECONFIG=$HOME/.k3d/kubeconfig-airgapped-playground.yaml

while IFS= read -r image; do
  local dstImage=$K3D_NODE:30002/library/${image##*/}
  echo pushing image $image to $dstImage
  skopeo copy docker://$image --dest-creds admin:Harbor12345 --dest-tls-verify=false  docker://$dstImage
  BASIC_DST_IMAGES+="${dstImage}\n"
done <<< "$BASIC_SRC_IMAGES"
echo $BASIC_DST_IMAGES
```

Note that we're using harbor here, because `k3d image import -c airgapped-playground $(echo $BASIC_IMAGES)` does not
help because some pods follow the policy of always pulling the images.

Note that even though the images are named `$K3D_NODE:30002/library/...`, these are available via `localhost:30002/library/...` in the k3d cluster.

## Install the playground

Don't disconnect from the internet yet, because 

* k3d needs some images itself, e.g. the `local-path-provisioner` (see Troubleshooting) which are only pulled on demand.
  In this case when the first PVC gets provisioned.
* SCMM needs to download the plugins from the internet
* Helm repo updates need access to the internet
* But also because we would have to replace the images for registry, scmm, jenkins (several images!) and argocd in the
  source code, as there are no parameters to do so.

So, start the installation and once Argo CD is running, go offline.
```bash
docker run -it -u $(id -u) \
    -v ~/.k3d/kubeconfig-airgapped-playground.yaml:/home/.kube/config \
    --net=host gitops-playground:dev --argocd --yes -x \
      --vault=dev --metrics \
      --grafana-image localhost:30002/library/grafana:8.2.1 \
      --grafana-sidecar-image localhost:30002/library/k8s-sidecar:1.14.2 \
      --prometheus-image localhost:30002/library/prometheus:v2.28.1 \
      --prometheus-operator-image localhost:30002/library/prometheus-operator:v0.50.0 \
      --prometheus-config-reloader-image localhost:30002/library/prometheus-config-reloader:v0.50.0 \
      --external-secrets-image localhost:30002/library/external-secrets:v0.6.1 \
      --external-secrets-certcontroller-image localhost:30002/library/external-secrets:v0.6.1 \
      --external-secrets-webhook-image localhost:30002/library/external-secrets:v0.6.1 \
      --vault-image localhost:30002/library/vault:1.12.0 \
      --nginx-image localhost:30002/library/nginx:1.23.3-debian-11-r8
```

In a different shell start this script, that waits for Argo CD and then goes offline.

```bash
sudo id # cache sudo PW
while true; do
    pods=$(kubectl get pods -n argocd -o jsonpath="{range .items[*]}{.status.phase}{'\n'}{end}")
    # Dont stop when there are no pods
    [[ "$(kubectl get pods  -n argocd --output name | wc -l)" -gt 0 ]] && ready="True" || ready="False" 
    while IFS= read -r pod; do
        if [[ "$pod" != "Running" ]]; then
            ready="False"
        fi
    done <<< "$pods"
    if [[ "$ready" == "True" ]]; then
        break
    fi
    echo "$(date '+%Y-%m-%d %H:%M:%S'): Waiting for ArgoCD pods to be ready. Status: $pods"
    sleep 5
done

echo "$(date '+%Y-%m-%d %H:%M:%S'): Argo CD Ready, going offline"
sudo iptables -I FORWARD -j DROP -i $(ip -o -4 addr show | awk -v ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' k3d-airgapped-playground-server-0)" '$4 ~ ip {print $2}')
```

If you want to go online again, use `-D`
```bash
sudo iptables -D FORWARD -j DROP -i $(ip -o -4 addr show | awk -v ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' k3d-airgapped-playground-server-0)" '$4 ~ ip {print $2}')
```

## Troubleshooting

When stuck in `Pending` this might be due to volumes not being provisioned
```bash
k get pod -n kube-system
NAME                                                         READY   STATUS             RESTARTS      AGE
helper-pod-create-pvc-a3d2db89-5662-43c7-a945-22db6f52916d   0/1     ImagePullBackOff   0             72s
```
