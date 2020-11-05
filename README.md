# k8s-gitops-playground

Reproducible infrastructure to showcase GitOps workflows

## Install k3s

You can use your own k3s cluster, or use the script provided.
Run this script from root with:

`./scripts/init-cluster.sh`

If you use your own cluster, note that jenkins relies on the `--docker` mode to be enabled.

In a real-life scenario, it would make sense to run Jenkins agents outside the cluster for security and load reasons, 
but in order to simplify the setup for this playground we use this slightly dirty workaround: 
Jenkins builds on the master and uses the docker agent that also runs the k8s pods. That's why we need the k3s' 
`--docker` mode. 

## Apply apps to cluster

[`scripts/apply.sh`](scripts/apply.sh)

The scripts also prints a little intro on how to get started with a GitOps deployment.

## Remove apps from cluster

`scripts/destroy.sh`

## Login

### Jenkins

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - `scmadmin/scmadmin`
Change in `jenkins-credentials.yaml` if necessary.

### SCM-Manager

Find scm-manager on http://localhost:9091

Login with `scmadmin/scmadmin`
