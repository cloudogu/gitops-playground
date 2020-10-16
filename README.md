# gitops-example-cluster
Reproducible infrastructure to showcase GitOps workflows

## Jenkins

Admin user: Same as SCM-Manager - scmadmin / scmadmin.
Change in jenkins-credentials.yaml if necessary.

```bash 
kubectly apply -f jenkins/jenkins-credentials.yaml
kubectly apply -f jenkins/jenkins-pvcs.yaml

helm repo add jenkins https://charts.jenkins.io
helm upgrade --install jenkins --values jenkins/values.yaml jenkins/jenkins
```

Find jenkins on http://localhost:9090

## SCM-Manager

### Copy chart to k3s static folder

```
sudo cp scm-manager/scm-manager-2.7.1.tgz  /var/lib/rancher/k3s/server/static/charts/
```

### Copy manifests to k3s manifests folder

```
sudo cp scm-manager/*.yaml /var/lib/rancher/k3s/server/manifests/
```

### Start

Find scm-manager on http://localhost:9091

Login with scmadmin/scmadmin