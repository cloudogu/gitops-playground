# Running on Windows or Mac

* In general: We cannot use the `host` network, so it's easiest to access via [ingress controller](#deploy-ingress-controller) and [ingresses](#local-ingresses).
* `--base-url=http://localhost --ingress` should work on both Windows and Mac.
* In case of problems resolving e.g. `jenkins.localhost`, you could try using `--base-url=http://local.gd` or similar, as described in [local ingresses](#local-ingresses).

## Mac and Windows WSL

On macOS and when using the Windows Subsystem Linux on Windows (WSL), you can just run our [TL;DR command](#tldr) after installing Docker.

For Windows, we recommend using [Windows Subsystem for Linux version 2](https://learn.microsoft.com/en-us/windows/wsl/install#install-wsl-command) (WSL2) with a [native installation of Docker Engine](https://docs.docker.com/engine/install/), because it's easier to set up and less prone to errors.

For macOS, please increase the Memory limit in Docker Desktop (for your DockerVM) to be > 10 GB.
Recommendation: 16GB.

```bash
bash <(curl -s \
  https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh) \
  && docker run --rm -t --pull=always -u $(id -u) \
    -v ~/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config \
    --net=host \
    ghcr.io/cloudogu/gitops-playground --yes --argocd --ingress --base-url=http://localhost
# If you want to try all features, you might want to add these params: --mail --monitoring --vault=dev
```

When you encounter errors with port 80 you might want to use e.g. 
* `init-cluster.sh) --bind-ingress-port=8080` and 
* `--base-url=http://localhost:8080` instead.

## Windows Docker Desktop

* As mentioned in the previous section, we recommend using WSL2 with a native Docker Engine.
* If you must, you can also run using Docker Desktop from native Windows console (see bellow)
* However, there seems to be a problem when the Jenkins Jobs running the playground access docker, e.g.   
```
$ docker run -t -d -u 0:133 -v ... -e ******** bitnamilegacy/kubectl:1.25.4 cat
docker top e69b92070acf3c1d242f4341eb1fa225cc40b98733b0335f7237a01b4425aff3 -eo pid,comm
process apparently never started in /tmp/gitops-playground-jenkins-agent/workspace/xample-apps_petclinic-plain_main/.configRepoTempDir@tmp/durable-7f109066
(running Jenkins temporarily with -Dorg.jenkinsci.plugins.durabletask.BourneShellScript.LAUNCH_DIAGNOSTICS=true might make the problem clearer)
Cannot contact default-1bg7f: java.nio.file.NoSuchFileException: /tmp/gitops-playground-jenkins-agent/workspace/xample-apps_petclinic-plain_main/.configRepoTempDir@tmp/durable-7f109066/output.txt
```
* In Docker Desktop, it's recommended to use WSL2 as backend. 
* Using the Hyper-V backend should also work, but we experienced random `CrashLoopBackoff`s of running pods due to liveness probe timeouts.  
  Same as for macOS, increasing the Memory limit in Docker Desktop (for your DockerVM) to be > 10 GB might help.  
  Recommendation: 16GB.

Here is how you can start the playground from a Windows-native PowerShell console:

* [Install k3d](https://k3d.io/#installation), see [init-cluster.sh](scripts/init-cluster.sh) for `K3D_VERSION`, e.g. using `winget`
```powershell
winget install k3d --version x.y.z
```
* Create k3d cluster.
  See `K3S_VERSION` in [init-cluster.sh](scripts/init-cluster.sh) for `$image`, then execute  
```powershell
$ingress_port = "80"
$registry_port = "30000"
$image = "rancher/k3s:v1.25.5-k3s2"
# Note that ou can query the image version used by playground like so: 
# (Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/cloudogu/gitops-playground/main/scripts/init-cluster.sh').Content -split "`r?`n" | Select-String -Pattern 'K8S_VERSION=|K3S_VERSION='

k3d cluster create gitops-playground `
    --k3s-arg=--kube-apiserver-arg=service-node-port-range=8010-65535@server:0 `
    -p ${ingress_port}:80@server:0:direct `
    -v /var/run/docker.sock:/var/run/docker.sock@server:0 `
    --image=${image} `
    -p ${registry_port}:30000@server:0:direct

# Write $HOME/.config/k3d/kubeconfig-gitops-playground.yaml
k3d kubeconfig write gitops-playground
```
* Note that
  * You can ignore the warning about docker.sock
  * We're mounting the docker socket, so it can be used by the Jenkins Agents for the docker-plugin.
  * Windows seems not to provide a group id for the docker socket. So the Jenkins Agents run as root user.
  * If you prefer running with an unprivileged user, consider running on WSL2, Mac or Linux
  * You could also add `-v gitops-playground-build-cache:/tmp@server:0 ` to persist the Cache of the Jenkins agent between restarts of k3d containers.
* Apply playground:  
  Note that when using a `$registry_port` other than `30000` append the command `--internal-registry-port=$registry_port` bellow
  
```powershell
docker run --rm -t --pull=always `
    -v $HOME/.config/k3d/kubeconfig-gitops-playground.yaml:/home/.kube/config `
    --net=host `
    ghcr.io/cloudogu/gitops-playground --yes --argocd --ingress --base-url=http://localhost:$ingress_port # more params go here
```
