# k8s-gitops-playground
Reproducible infrastructure to showcase GitOps workflows

## Jenkins

```bash
sudo ln -s $(pwd)/jenkins/jenkins-helm-chart.yaml /var/lib/rancher/k3s/server/manifests/
sudo ln -s $(pwd)/jenkins/jenkins-credentials.yaml /var/lib/rancher/k3s/server/manifests/
sudo ln -s $(pwd)/jenkins/jenkins-pvcs.yaml /var/lib/rancher/k3s/server/manifests/
```

Remove if necessary

```bash
sudo sh -c 'rm /var/lib/rancher/k3s/server/manifests/jenkins-*.yaml' 
k delete helmchart jenkins
k delete addon -n kube-system jenkins-credentials jenkins-pvcs jenkins-helm-chart
```

Find jenkins on http://localhost:9090

Admin user: Same as SCM-Manager - scmadmin / scmadmin.
Change in jenkins-credentials.yaml if necessary.


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
