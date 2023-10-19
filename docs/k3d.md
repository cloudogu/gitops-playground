# k3d

## Create Cluster

To be able to set up the infrastructure, you only need a linux machine, Mac or Windows with WSL, with docker and curl
installed.
The k3d cluster is started like so:

```shell
bash <(curl -s https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh)
```

If you plan to interact with your cluster directly (not only via GitOps), we recommend
installing `kubectl` (see [here](https://kubernetes.io/docs/tasks/tools/#kubectl)). 

## Parameters

### --cluster-name
`--cluster-name` - default: `gitops-playground`

### --bind-localhost
`--bind-localhost=false` - does not bind to localhost. This only makes sense on Linux, as on Windows and Mac the  `host` network from Docker's perspective is not the localhost you can access from your browser. 
When setting this to `true`, the URLs of the application will not be reachable via localhost but via the IP address of the k3d `server-0`
docker container. Avoids port conflicts but is less convenient. We use this for our internal integration test in 
[Jenkins](../Jenkinsfile), for example.
For humans [`--bind-ingress-port`](#--bind-ingress-port) makes more sense.

Even with `--bind-localhost=true`, there still is one port that has to be bound to localhost: the registry port. 
For registries other than localhost or local ip addresses, docker will use HTTPS, leading to errors on `docker push` in the example application's Jenkins Jobs.
Note that if you use this option and the registry's default port 30000 is already bound on localhost 
(e.g. when starting more than one instance of the playground) you can overwrite it with `--bind-registry-port`.
You can also bin the registry port will be bound to an arbitrary free port on localhost using `--bind-registry-port=0`, **which we don't recommend**.
Note that this port changes on every restart of the k3d container, rendering the registry inside the playground's jenkins inaccessible. 

This port has to be passed on when creating the playground via the `--internal-registry-port` parameter. For example: 

In order to find out the IP address to access the services in the playground, the following docker command will do

```shell
$ docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${CLUSTER_NAME}-server-0
172.24.0.2
# In this example you could reach Jenkins on http://172.24.0.2:9090
```

An easier alternative might be to use local ingresses with the `--bind-ingress-port` parameer.

### --bind-ingress-port

Binds the ingress controller to this localhost port.
Sets `--bind-localhost=false`.
Defaults to empty, i.e. no port is bound.

This feature can be used for local ingresses which are the only way to run the playground on Windows and Mac, 
reduce the risk of port conflicts and might be more convenient than using port numbers.

See [README](../README.md) "Running on Windows or Mac" and "Local ingresses". 

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
