## Installation

There a several options for running the GitOps playground

* on a local k3d cluster
  Works best on Linux, but is possible on [Windows and Mac](#windows-or-mac). 
* on a remote k8s cluster
* each with the option
    * to use an external Jenkins, SCM-Manager and registry
      (this can be run in production, e.g. with a [Cloudogu Ecosystem](https://cloudogu.com/en/ecosystem/?mtm_campaign=gitops-playground&mtm_kwd=ces&mtm_source=github&mtm_medium=link)) or
    * to run everything inside the cluster (for demo only)

The diagrams below show an overview of the playground's architecture and three scenarios for running the playground.
For a simpler overview including all optional features such as monitoring and secrets management see intro at the very top.

Note that running Jenkins inside the cluster is meant for demo purposes only. The third graphic shows our production
scenario with the Cloudogu EcoSystem (CES). Here better security and build performance is achieved using ephemeral
Jenkins build agents spawned in the cloud.

### Overview
| Playground on local machine                                                | Production environment with Cloudogu EcoSystem                                       |
|----------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| [![Playground on local machine](docs/images/gitops-playground-local.drawio.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/images/gitops-playground-local.drawio.svg "View full size") | [![A possible production environment](docs/images/gitops-playground-production.drawio.svg)](https://cdn.jsdelivr.net/gh/cloudogu/gitops-playground@main/docs/images/gitops-playground-production.drawio.svg "View full size") |

### Create Cluster

You can apply the GitOps playground to 

* a local k3d cluster (see [docs](docs/k3d.md) or [script](scripts/init-cluster.sh) for more details):
  ```shell
  bash <(curl -s \
    https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh)
  ```
* a remote k8s cluster on Google Kubernetes Engine (e.g. via Terraform, see our [docs](docs/gke.md)),
* or almost any k8s cluster.  
  Note that if you want to deploy Jenkins inside the cluster, you either need Docker as container runtime or set Jenkins up to run its build on an agent that provides Docker.

For the local cluster, you can avoid hitting DockerHub's rate limiting by using a mirror via the `--docker-io-registry-mirror` parameter.

For example:

```bash
bash <(curl -s \
    https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) --docker-io-registry-mirror https://mirror.gcr.io
```

This parameter is passed on the containerd used by k3d. 

In addition, the Jobs run by Jenkins are using the host's Docker daemon.  
To avoid rate limits there, you might have to configure a mirror there as well.
This can be done in the `/etc/docker/daemon.json` or in the config of Docker Desktop.

For example:
```json
{
  "registry-mirrors": ["https://mirror.gcr.io"]
}
```

### Apply playground

You can apply the playground to your cluster using our container image `ghcr.io/cloudogu/gitops-playground`.  
On success, the container prints a little intro on how to get started with the GitOps playground.

There are several options for running the container:

* For local k3d cluster, we recommend running the image as a local container via `docker`
* For remote clusters (e.g. on GKE) you can run the image inside a pod of the target cluster via `kubectl`.

All options offer the same parameters, see [below](#overview-of-all-cli-and-config-options).

#### Apply via Docker (local cluster)

When connecting to k3d it is easiest to apply the playground via a local container in the host network and pass
k3d's kubeconfig.

```shell
CLUSTER_NAME=gitops-playground
docker pull ghcr.io/cloudogu/gitops-playground
docker run --rm -t -u $(id -u) \
  -v ~/.config/k3d/kubeconfig-${CLUSTER_NAME}.yaml:/home/.kube/config \
  --net=host \
  ghcr.io/cloudogu/gitops-playground # additional parameters go here
``` 

Note:
* `docker pull` in advance makes sure you have the newest image, even if you ran this command before.  
  Of course, you could also specify a specific [version of the image](https://github.com/cloudogu/gitops-playground/pkgs/container/gitops-playground/versions).
* Using the host network makes it possible to determine `localhost` and to use k3d's kubeconfig without altering, as it
  access the API server via a port bound to localhost.
* We run as the local user in order to avoid file permission issues with the `kubeconfig-${CLUSTER_NAME}.yaml.`
* If you experience issues and want to access the full log files, use the following command while the container is running:

```bash
docker exec -it \
  $(docker ps -q  --filter ancestor=ghcr.io/cloudogu/gitops-playground) \
  bash -c -- 'tail -f  -n +1 /tmp/playground-log-*'
```

#### Apply via kubectl (remote cluster)

For remote clusters it is easiest to apply the playground via kubectl.
You can find info on how to install kubectl [here](https://v1-25.docs.kubernetes.io/docs/tasks/tools/#kubectl).

```shell
# Create a temporary ServiceAccount and authorize via RBAC.
# This is needed to install CRDs, etc.
kubectl create serviceaccount gitops-playground-job-executer -n default
kubectl create clusterrolebinding gitops-playground-job-executer \
  --clusterrole=cluster-admin \
  --serviceaccount=default:gitops-playground-job-executer

# Then start apply the playground with the following command:
# To access services on remote clusters, add either --remote or --ingress --base-url=$yourdomain
kubectl run gitops-playground -i --tty --restart=Never \
  --overrides='{ "spec": { "serviceAccount": "gitops-playground-job-executer" } }' \
  --image ghcr.io/cloudogu/gitops-playground \
  -- --yes --argocd # additional parameters go here. 

# If everything succeeded, remove the objects
kubectl delete clusterrolebinding/gitops-playground-job-executer \
  sa/gitops-playground-job-executer pods/gitops-playground -n default  
```

In general `docker run` should work here as well. But GKE, for example, uses gcloud and python in their kubeconfig.
Running inside the cluster avoids these kinds of issues.
