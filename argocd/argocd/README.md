# Argo CD

Repo for managing Argo CD via GitOps. This repository contains the following folders:
* `applications`: Argo applications. One for each team pointing at their own repository and some general applications for managing the three folders of this repository
* `argocd`: Self managing Argo installation and configuration
* `projects`: One Argo project for each team for clean organization and to distribute access rights

## Upgrade Argo CD to newer version
1. Look [here](https://artifacthub.io/packages/helm/argo/argocd#changelog) if there are necessary actions when upgrading to the new version
2. Change the version in `Chart.yaml`
3. run `helm dep update argocd` from the root of the repo
4. Push the modified `Chart.yaml`, `Chart.lock` and any changes from step 1, if there are any
5. Argo now upgrades itself

## What to do if argo breaks itself
If you make a commit, which breaks something from argo, and it fails to manage itself back to a healthy state with a 
new commit, than you have to fix argo with helm from your local computer.
```bash
# first fix the error

# then check the diff, the file is very long. It is normal, that all the labels which
# state, that argo manages the resources, will be deleted. Argo will put them back later.
# Just make sure, that nothing else ist being destroyed.
helm template -n argocd argocd . | kubectl diff -n argocd -f - > diff.yaml
 
# then commit the fix to git, so that argo has the same manifests, when it works again after helm upgrade

# then run the following command, to apply EVERYTHING. If you want to be safer, than just run everything
# up to the pipe (|) and apply manually just the resources, which you really want to patch (e.g. just a secret
# or a config map):
helm template -n argocd argocd . | kubectl apply -n argocd -f - 
```