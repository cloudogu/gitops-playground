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