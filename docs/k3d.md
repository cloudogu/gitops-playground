### k3d

#### Prerequisites

To be able to set up the infrastructure you only need a linux machine (tested with Ubuntu 20.04) with docker installed.
k3d is installed by `./scripts/init-cluster.sh` script, all other tools are contained in our docker image
`ghcr.io/cloudogu/gitops-playground`.

#### Create Cluster

Run this script from repo root start a the `gitops-playground` k3d cluster:

`./scripts/init-cluster.sh`

Side node:

In a real-life scenario, it would make sense to run Jenkins agents outside the cluster for security and load reasons,
but in order to simplify the setup for this playground we use this slightly dirty workaround:
Jenkins builds in agent pods that are able to spawn plain docker containers docker host that runs the containers.
That's why we mount `/var/run/docker.sock` into the k3d container (see [init-cluster.sh](../scripts/init-cluster.sh))