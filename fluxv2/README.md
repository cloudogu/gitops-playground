# Flux

Repo to manage Flux v2.

## Upgrade Flux to newer version

The following Flux CLI command will upgrade Flux in the cluster and update the repo.

```shell
  USER=
  PW=
SCMMANAGER=http...

flux bootstrap git \
--url=http://$SCMMANAGER/scm/repo/fluxv2/gitops \
--allow-insecure-http=true \
--branch=main \
--path=./clusters/gitops-playground \
--token-auth \
--username=$USER \
--password=$APW \
--interval=10s
```