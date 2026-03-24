# Developers

This document collects some information about things developers of the gop should know or
problems they might face when they try to run and test their changes.
It provides workarounds or solutions for the given issues.

## Disclaimer

The versions listed in this README may not always reflect the most current release. 
Please be aware that newer versions may exist. 
The versions are also specified in the `Config.groovy` file, so it is recommended to consult that file for the latest version information.


## Table of contents

<!-- Update with `doctoc --notitle docs/developers.md --maxlevel 4`. See https://github.com/thlorenz/doctoc -->
<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->

- [Prerequisites](#prerequisites)
- [Testing](#testing)
  - [Unit-Tests](#unit-tests)
  - [Integration-Tests](#integration-tests)
- [Jenkins plugin installation issues](#jenkins-plugin-installation-issues)
  - [Solution](#solution)
  - [Updating all plugins](#updating-all-plugins)
- [Local development](#local-development)
- [Testing URL separator hyphens](#testing-url-separator-hyphens)
- [External registry for development](#external-registry-for-development)
- [Testing two registries](#testing-two-registries)
  - [Basic test](#basic-test)
  - [Proper test](#proper-test)
- [Testing Network Policies locally](#testing-network-policies-locally)
- [Emulate an airgapped environment](#emulate-an-airgapped-environment)
  - [Setup cluster](#setup-cluster)
  - [Install the playground](#install-the-playground)
  - [Notifications / E-Mail](#notifications--e-mail)
  - [Troubleshooting](#troubleshooting)
  - [Using ingresses locally](#using-ingresses-locally)
- [Generate schema.json](#generate-schemajson)
- [Releasing](#releasing)
- [Installing ArgoCD Operator](#installing-argocd-operator)
  - [Prerequisites:](#prerequisites)
  - [Installation Script](#installation-script)
  - [Install ingress manually](#install-ingress-manually)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## Prerequisites

- Java 17
- Groovy
- Maven
- Docker
- [k3d](https://k3d.io/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Helm](https://helm.sh/docs/intro/install/)
- [skopeo](https://github.com/containers/skopeo)
- [Golang](https://go.dev/doc/install) (only if you plan to use argo-cd operator)
- [yq](https://mikefarah.gitbook.io/yq/) (useful for debugging purposes)

To check if you have all necessary tools installed, run the following command. If you don't see any error messages, you are good to go:
```bash
java -version && mvn -version && docker version && k3d version && kubectl version && helm version
```


## Testing

1. There are integration tests implemented by Junit. Classes marked with 'IT' and the end.
2. Long running tests are marked with 'LongIT'.
3. Main Branch executes both, feature-branches only IT.

### Unit-Tests

```bash
mvn clean test
```

### Integration-Tests

```bash
# build the gop tests
./mvnw clean test-compile

# first create a fresh new cluster to test on:
./scripts/init-cluster

# then deploy the gop with a predefined profile:
./mvnw exec:java -Dexec.arguments="--profile=<PROFILE>"

# finally run the test
./mvnw integration-test -Dmicronaut.environments=<PROFILE>
```

where <PROFILES> can be one of:
- full
- full-prefix

- content-examples
- operator-full
- operator-mandants

Note: 'operator-*' profiles requires you to install the argo-cd operator in a fresh cluster _before_ deploying the gop.
This can be done by running:
```bash
./scripts/local/install-argocd-operator.sh
```

## Jenkins plugin installation issues

We have had some issues with jenkins plugins in the past due to the installation of the latest versions.
Trying to overcome this issue we pinned all plugins within `scripts/jenkins/plugins/plugins.txt`.
These pinned plugins get downloaded within the docker build and saved into a folder as `.hpi` files. Later on
when configuring jenkins, we upload all the plugin files with the given version.

Turns out it does not completely circumvent this issue. In some cases jenkins updates these plugins automagically (as it seems) when installing the pinned version fails at first or being installed when resolving dependencies.
This again may lead to a broken jenkins, where some of the automatically updated plugins have changes within their dependencies. These dependencies than again are not updated but pinned and may cause issues.

Since solving this issue may require some additional deep dive into bash scripts we like to get rid of in the future, we decided to give some hints how to easily solve the issue (and keep the plugins list up to date :]) instead of fixing it with tremendous effort.

### Solution

* Determine the plugins that cause the issue
  * inspecting the logs of the jenkins-pod
  * jenkins-ui (http://localhost:9090/manage)

![Jenkins-UI with broken plugins](images/example-plugin-install-fail.png)

* Fix conflicts by updating the plugins with compatible versions
  * Update all plugin versions via jenkins-ui (http://localhost:9090/pluginManager/) and restart

![Jenkins-UI update plugins](images/update-all-plugins.png)

* Verify the plugin installation
  * Check if jenkins starts up correctly and builds all example pipelines successfully
  * verify installation of all plugins via jenkins-ui (http://localhost:9090/script) executing the following command

![Jenkins-UI plugin list](images/get-plugin-list.png)

```groovy
Jenkins.instance.pluginManager.activePlugins.sort().each {
  println "${it.shortName}:${it.version}"
}
```

* Share and publish your plugin updates
  * Make sure you have updated `plugins.txt` with working versions of the plugins
  * commit and push changes to your feature-branch and submit a pr

Note that `plugins.txt` contains the whole dependency tree, including transitive plugin dependencies.
The bare minimum of plugins that are needed is this:

```shell
docker-workflow # Used in example builds
git # Used in example builds
junit # Used in example builds
pipeline-utility-steps # Used in example builds, by gitops-build-lib
pipeline-stage-view # Only necessary for better visualization of the builds
prometheus # Necessary to fill Jenkins dashboard in Grafana
scm-manager # Used in example builds
workflow-aggregator # Pipelines plugin, used in example builds
```

Note that, when running locally we also need `kubernetes` and `configuration-as-code` but these are contained in [our 
jenkins helm image](https://github.com/cloudogu/jenkins-helm-image/blob/5.8.1-1/Dockerfile) (extracted from the 
[corresponding helm chart version](https://github.com/jenkinsci/helm-charts/blob/jenkins-5.8.1/charts/jenkins/values.yaml)).


### Updating all plugins 
To get a minimal list of plugins, start an empty jenkins that uses [the base image of our image](https://github.com/cloudogu/jenkins-helm-image/blob/main/Dockerfile):

```shell
docker run --rm -v $RANDOM-tmp-jenkins:/var/jenkins_home  jenkins/jenkins:2.479.2-jdk17
```
We need a volume to persist the plugins when jenkins restarts.  
(These can be cleaned up afterwards like so: `docker volume ls -q | grep jenkins | xargs -I {} docker volume rm {}`).

Then
* manually install the bare minimum of plugins mentioned above
* extract the plugins using the groovy console as mentioned above
* Write the output into `plugins.txt`

We should automate this!

## Local development

* Run locally
  * Run from IDE (allows for easy debugging), works e.g. with IntelliJ IDEA
    Note: If you encounter `error=2, No such file or directory`,
    it might be necessary to explicitly set your `PATH` in Run Configuration's Environment Section.
  * From shell:  
    Run
      ```shell
     ./mvnw package -DskipTests
     ./mvnw exec:java -Dexec.arguments="<gopParamsHere>"
       ```
* Running inside the container:
  * Build and run dev Container:
    ```shell
    docker build -t gitops-playground:dev --build-arg ENV=dev --progress=plain --pull .
    docker run --rm -it -u $(id -u) -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
      --net=host gitops-playground:dev #params
     ```
  * Hint: You can speed up the process by installing the Jenkins plugins from your filesystem, instead of from the internet.  
    To do so, download the plugins into a folder, then set this folder vie env var:  
    `JENKINS_PLUGIN_FOLDER=$(pwd) java -classpath .. # See above`.  
    A working combination of plugins be extracted from the image:
      ```bash
      id=$(docker create --pull=always ghcr.io/cloudogu/gitops-playground:main)
      docker cp $id:/gitops/jenkins-plugins .
      docker rm -v $id
      ```

## Testing URL separator hyphens
```bash
docker run --rm -t  -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    -v $(pwd)/gitops-playground.yaml:/config/gitops-playground.yaml \
    --net=host \
   gitops-playground:dev --yes --argocd --base-url=http://localhost  --ingress --mail --monitoring --vault=dev --url-separator-hyphen

# Create localhost entries with hyphens
echo 127.0.0.1 $(kubectl get ingress -A  -o jsonpath='{.items[*].spec.rules[*].host}') | sudo tee -a /etc/hosts

# Produce clickable links:
kubectl get --all-namespaces ingress -o json 2> /dev/null | jq -r '.items[] | .spec.rules[] | .host as $host | .http.paths[] | ( "http://" + $host + .path )' | sort | grep -v ^/
```

## External registry for development

If you need to emulate an "external", private registry with credentials, then install it like so:
```bash
helm repo add harbor https://helm.goharbor.io
helm upgrade -i my-harbor harbor/harbor -f ./scripts/dev/external-registry-values.yaml --version 1.14.2 --namespace harbor --create-namespace
```

Once it's up and running either create your own private project or just set the existing `library` to private:
```bash
curl -X PUT -u admin:Harbor12345 'http://localhost:30002/api/v2.0/projects/1'  -H 'Content-Type: application/json' \
--data-raw '{"metadata":{"public":"false", "id":1,"project_id":1}}'
```

Then either import external images like so (requires `skopeo` but no prior pulling or insecure config necessary):
```bash
skopeo copy docker://bitnamilegacy/nginx:1.25.1 --dest-creds admin:Harbor12345 --dest-tls-verify=false  docker://localhost:30002/library/nginx:1.25.1
```

Alternatively, you could push existing images from your docker daemon.
However, this takes longer (pull first) and you'll have to make sure to add `localhost:30002` to `insecure-registries` in `/etc/docker/daemon.json` and restart your docker daemon first.

```bash
docker login localhost:30002 -u admin -p Harbor12345
docker tag bitnamilegacy/nginx:1.25.1 localhost:30002/library/nginx:1.25.1
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

## Testing two registries

### Basic test
* Start playground once,
* then again with these parameters:  
  `--registry-url=localhost:30000 --registry-proxy-url=localhost:30000 --registry-proxy-username=Proxy --registry-proxy-password=Proxy12345`
* The petclinic pipelines should still run

### Proper test

**Some hints before getting started**

* Follow these steps in order
* Important: Harbor has to be set up after initializing the cluster, but before installing GOP.  
  Otherwise GOP deploys its own registry, leading to port conflicts:  
  `Service "harbor" is invalid: spec.ports[0].nodePort: Invalid value: 30000: provided port is already allocated`
* By default, `docker run` relies on the `gitops-playground:dev` image.  

**Setup**

To set-up harbor with two projects, you can use the target "prepare-two-registries".
```shell
make prepare-two-registries
```

Afer that, deploy GOP with the generated config file:
```bash
# Create a docker container or use an available image from a registry
# docker build -t gop:dev .
GOP_IMAGE=ghcr.io/cloudogu/gitops-playground
PATH_TWO_REGISTRIES=./scripts/local/two-registries.yaml #Adjust to path above

docker run --rm -t -u $(id -u) \
   -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
   -v ${PATH_TWO_REGISTRIES}:/home/two-registries.yaml \
    --net=host \
    ${GOP_IMAGE} -x \
    --yes --argocd --ingress --base-url=http://localhost \
    --vault=dev --monitoring --mail --cert-manager \
    --create-image-pull-secrets \
    --registry-url=localhost:30000 \
    --registry-path=registry \
    --registry-username=Registry \
    --registry-password=Registry12345 \
    --registry-proxy-url=localhost:30000 \
    --registry-proxy-username=Proxy \
    --registry-proxy-password=Proxy12345 \
    --registry-username-read-only=RegistryRead \
    --registry-password-read-only=RegistryRead12345 \
    --mail-image=localhost:30000/proxy/mailhog:latest \
    --vault-image=localhost:30000/proxy/vault:latest \
    --config-file=/home/two-registries.yaml
    
    # Or with config file --config-file=/config/gitops-playground.yaml
```

## Testing Network Policies locally

The first increment of our `--netpols` feature is intended to be used on openshift and with an external Cloudogu Ecosystem.

That's why we need to initialize our local cluster with some netpols for everything to work.
* The `<prefix>-jenkins` ,  `<prefix>-scm-manager` and `<prefix>-registry` namespace needs to be accesible from outside the cluster (so GOP apply via `docker run` has access)
* Emulate OpenShift default netPols: allow network communication inside namespaces and access by ingress controller 

After the cluster is initialized and before GOP is applied, do the following:

```bash
# Prefix handling:
# if used, change prefix to your configured prefix and then
# hyphen "-" is neccessary for this workaorund.
# if no prefix is used, delete everthing after prefix=
prefix=<prefix>-
# When using harbor, do the same for namespace harbor


for ns in ${prefix}jenkins  ${prefix}registry  ${prefix}scm-manager ${prefix}example-apps-production ${prefix}example-apps-staging ${prefix}monitoring ${prefix}secrets; do
  k create ns $ns -oyaml --dry-run=client | k apply -f-
  k apply --namespace "$ns" -f- <<EOF
kind: NetworkPolicy
apiVersion: networking.k8s.io/v1
metadata:
  name: allow-from-ingress-controller
spec:
  podSelector: {}  
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: ${prefix}traefik
        - podSelector:
            matchLabels:
              app.kubernetes.io/component: controller
              app.kubernetes.io/instance: traefik
              app.kubernetes.io/name: traefik
---
kind: NetworkPolicy
apiVersion: networking.k8s.io/v1
metadata:
  name: allow-from-same-namespace
  annotations:
    description: Allow connections inside the same namespace
spec:
  podSelector: {}
  ingress:
    - from:
        - podSelector: {}
EOF
done
# Some NS need to be accessible from docker image
for ns in ${prefix}jenkins ${prefix}registry ${prefix}scm-manager; do
  k apply --namespace "$ns" -f- <<EOF
kind: NetworkPolicy
apiVersion: networking.k8s.io/v1
metadata:
  name: allow-all-ingress
spec:
  podSelector: {}
  ingress:
  - {}
EOF
done
```

## Emulate an airgapped environment

Let's set up our local playground to emulate an airgapped env, as some of our customers have.

Note that with approach bellow, the whole k3d cluster is airgapped with one exception: the Jenkins agents can work around this.
To be able to run the `docker` plugin in Jenkins (in a k3d cluster that only provides containerd) we mount the host's
docker socket into the agents.
From there it can start containers which are not airgapped.
So this approach is not suitable to test if the builds use any public images.
One solution could be to apply the `iptables` rule mentioned bellow to `docker0` (not tested).

The approach discussed here is suitable to check if the cluster tries to load anything from the internet,
like images or helm charts.

### Setup cluster

First of all, create a new default cluster with:
```bash
./scripts/init-cluster.sh
```

Then deploy the GOP with your desired settings, for example:
```bash
./mvnw exec:java -Dexec.arguments="--profile=full"
```

Now you can prepare the airgapped cluster, this step will remove the default 'gitops-playground' cluster
```bash
make prepare-airgapped-cluster
```

### Install the playground

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
    -v ~/.config/k3d/kubeconfig-airgapped-playground.yaml:/home/.kube/config \
    --net=host gitops-playground:dev --argocd --yes -x \
      --vault=dev --metrics \
      --grafana-image localhost:30002/library/grafana:12.3.0 \
      --grafana-sidecar-image localhost:30002/library/k8s-sidecar:2.1.2 \
      --prometheus-image localhost:30002/library/prometheus:v3.8.0 \
      --prometheus-operator-image localhost:30002/library/prometheus-operator:v0.87.1 \
      --prometheus-config-reloader-image localhost:30002/library/prometheus-config-reloader:v0.87.1 \
      --external-secrets-image localhost:30002/library/external-secrets:v0.6.1 \
      --external-secrets-certcontroller-image localhost:30002/library/external-secrets:v0.6.1 \
      --external-secrets-webhook-image localhost:30002/library/external-secrets:v0.6.1 \
      --vault-image localhost:30002/library/vault:1.12.0 \
      --ingress-image localhost:30002/library/traefik:3.6.7
```


### Notifications / E-Mail

Notifications are implemented via Mail.  
Either internal MailHog or an external mail server can be used.

To test with an external mail server, set up the configuration as follows:

```
--argocd --monitoring \
--smtp-address <smtp.server.address> --smtp-port <port> --smtp-user <login-username> --smtp-password 'your-secret' \
--grafana-email-to recipient@example.com --argocd-email-to-user recipient@example.com --argocd-email-to-admin recipient@example.com --argocd-email-from sender@example.com --grafana-email-from sender@example.com 
```

For testing, an email can be sent via the Grafana UI.  
Go to Alerting > Notifications, here at contact Points click on the right side at provisioned email contact on "View contact point"   
Here you can check if the configuration is implemented correctly and fire up a Testmail.

For testing Argo CD, just uncomment some of the defaultTriggers in it's values.yaml and it will send a lot of emails.

### Troubleshooting

When stuck in `Pending` this might be due to volumes not being provisioned
```bash
k get pod -n kube-system
NAME                                                         READY   STATUS             RESTARTS      AGE
helper-pod-create-pvc-a3d2db89-5662-43c7-a945-22db6f52916d   0/1     ImagePullBackOff   0             72s
```

### Using ingresses locally

For testing (or because it's more convenient than remembering node ports) ingresses can be used.
For that, k3d provides its own ingress controller traefik.

```bash
docker run --rm -it -u $(id -u) \
  -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
  --net=host \
  gitops-playground:dev --argocd --monitoring --vault=dev -x --yes \
  --argocd-url argocd.localhost --grafana-url grafana.localhost --vault-url vault.localhost \
  --mail-url mail.localhost --petclinic-base-domain petclinic.localhost \
  --nginx-base-domain nginx.localhost
```

Once Jenkins and Argo CD are through with their initial steps you can conveniently get all ingresses via

```shell
$ kubectl get ingress -A
NAMESPACE                 NAME                            CLASS     HOSTS                                                          ADDRESS                                                PORTS   AGE
argocd                    argocd-server                   traefik   argocd.localhost                                 192.168.178.42,2001:e1:1234:1234:1234:1234:1234:1234   80      14m
# ...
```

Where opening for example http://argocd.localhost in your browser should work.

The `base-domain` parameters lead to URLs in the following schema:  
`<stage>.<app-name>.<parameter>`, e.g.  
`staging.nginx-helm.nginx.localhost`

## Generate schema.json


Run `GenerateJsonSchema.groovy` from your IDE.

Or run build and run via maven and java:

````shell
mvn package -DskipTests
java -classpath target/gitops-playground-cli-0.1.jar org.codehaus.groovy.tools.GroovyStarter --main groovy.ui.GroovyMain \
  --classpath src/main/groovy src/main/groovy/com/cloudogu/gitops/cli/GenerateJsonSchema.groovy
````

Or build and run the via docker:

```shell
docker build -t gitops-playground:dev --build-arg ENV=dev  --progress=plain .
docker run --rm --entrypoint java gitops-playground:dev -classpath /app/gitops-playground.jar \
 org.codehaus.groovy.tools.GroovyStarter --main groovy.ui.GroovyMain \
 --classpath /app/src/main/groovy /app/src/main/groovy/com/cloudogu/gitops/cli/GenerateJsonSchema.groovy - \
 > docs/configuration.schema.json
```

## Releasing

On `main` branch:

````shell
TAG=0.5.0

git checkout main \
&& git pull \
&& git tag -s $TAG -m $TAG \
&& git push --follow-tags \
&& xdg-open https://ecosystem.cloudogu.com/jenkins/job/cloudogu-github/job/gitops-playground/job/main/build?delay=0sec
````

For now, please start a Jenkins Build of `main` manually.  
We might introduce tag builds in our Jenkins organization at a later stage.

A GitHub release containing all merged PRs since the last release is create automatically via a [GitHub action](../.github/workflows/create-release.yml)

## Installing ArgoCD Operator

This guide provides instructions for developers to install the ArgoCD Operator locally.

### Prerequisites:

Ensure you have the following installed on your system:

- Git: For cloning the repository. 
- golang: Version >= 1.24

### Installation Script

Copy the following script, paste it into your Terminal and execute it.

```shell
git clone https://github.com/argoproj-labs/argocd-operator && \
cd argocd-operator && \
git checkout release-0.16 && \
make deploy IMG=quay.io/argoprojlabs/argocd-operator:v0.15.0
```

### Install ingress manually

The ArgoCD installed via Operator is namespace isolated and therefor can not deploy an ingress-controller, because of global scoped configurations.
GOP has to be startet with ``` --insecure ``` because of we do not use https locally.
We have to install the ingress-controller manually:


```shell
helm upgrade --install traefik traefik/traefik --version 4.12.1 --namespace traefik --create-namespace 
```

If the helm repos are not present or up-to-date:

```shell
helm repo add traefik https://traefik.github.io/charts
helm repo update
helm install traefik traefik/traefik --version 39.0.0
```
