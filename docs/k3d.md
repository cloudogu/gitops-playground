# k3d

## Create Cluster

To be able to set up the infrastructure you only need a linux machine (tested with Ubuntu 20.04) with docker and curl
installed.
The k3d cluster is started like so:

```shell
bash <(curl -s https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh)
```
## Parameters

* `--cluster-name` - default: `gitops-playground`
* `--bind-localhost=false` - does not bind to localhost. That is, the URLs of the application will not be reachable via
  localhost but via the IP address of the k3d docker container. Avoids port conflicts but is less convenient.
  Note:
  * When applying the playground, remember to use the `--cluster-bind-address` parameter with your address. 
    See main README for details.
  * right now, builds inside the playground using `docker push` are failing, when started with this parameter. 
  See #53.  

## Implementation details

The script basically starts a k3d cluster with a command such as this:

```shell
k3d cluster create gitops-playground \
 --k3s-server-arg=--kube-apiserver-arg=service-node-port-range=8010-32767 \
 -v '/var/run/docker.sock:/var/run/docker.sock@server[0]' \
 -v '/etc/group:/etc/group@server[0]' \
 -v '/tmp:/tmp@server[0]' \
 --image=rancher/k3s:v1.21.2-k3s1 \
 --network=host
```

* Allows for binding to ports < 30000
* Mounts the docker socket for Jenkins agents (see bellow)
* Mounts local /etc/group in order to find the docker group ID to allow access to the socket
* Mounts /tmp for caching Jenkins builds (speeds up builds)
* Pins the k8s version (reproducible behavior)
* Runs k3d in host network namespace (convenience) -> URLs are bound to localhost

A note on mounting the docker socket:

In a real-life scenario, it would make sense to run Jenkins agents outside the cluster for security and load reasons,
but in order to simplify the setup for this playground we use this slightly dirty workaround:
Jenkins builds in agent pods that are able to spawn plain docker containers docker host that runs the containers.
That's why we mount `/var/run/docker.sock` into the k3d container (see [init-cluster.sh](../scripts/init-cluster.sh))
