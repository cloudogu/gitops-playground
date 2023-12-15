Argo rollouts with sticky session example
===

This emulates the usecase of a legacy app with local session handling that retains existing sessions on the old version until they're over (time based).

This uses canary releases because blue-green always switches the whole traffic, so it seems impossible to implement sticky sessions with it. 

## Demo in the browser

Screencast:

https://github.com/cloudogu/gitops-playground/assets/1824962/47944db1-4fca-47b1-8cf7-a7bb0a734c29

1. Open http://sticky.rollouts.localhost
2. Open http://argocd.localhost/applications/example-apps-production/rollouts-sticky
3. ~~Open http://argorollouts.localhost~~ (argo rollouts dashboard stays in "loading" state) 
2. In a separate Window change the image in [`rollout.yaml`](http://scmm.localhost/scm/repo/argocd/example-apps/code/sources/main/apps/rollouts-sticky/rollout.yaml/)
  * from: `argoproj/rollouts-demo:blue`
  * to `argoproj/rollouts-demo:green`
  * or the other way round
3. Watch Argo CD's UI and shortly after the new pod has been started, open http://sticky.rollouts.localhost in a private Browser window

-> Private window will be green, the window from 1. will remain blue for a couple of seconds.

## Demo on the command line

Less visual, more technical details

Screencast:

https://github.com/cloudogu/gitops-playground/assets/1824962/1d688017-25b4-4f2d-aa35-abb059cecfeb

Open terminals:

```shell
# Terminal 1: Stateless HTTP calls -> always get latest version
while ; do echo -n "$(date '+%Y-%m-%d %H:%M:%S') Stateless: " ; \
curl sticky.rollouts.localhost/color; echo; sleep 0.5; done

# Terminal 2: HTTP calls with cookie -> gets old version as long as possible
cookie_file=$(mktemp /tmp/cookies.XXXXXX)
echo Cookie file: $cookie_file
while ; do echo -n "$(date '+%Y-%m-%d %H:%M:%S') With session cookie: " ; \
curl -c "$cookie_file" -b "$cookie_file"  sticky.rollouts.localhost/color; echo; sleep 0.5; done

# Terminal 3: show rollouts (different versions)
while true; do
  echo "Every 1,0s: kubectl get pod $(date +%H:%M:%S)\n"

  kubectl get pod -n example-apps-production -o=jsonpath="{range .items[*]}{.metadata.name}{\"\t\"}{.status.phase}{\"\t\"}{.metadata.creationTimestamp}{\"\t\"}{.spec.containers[*].image}{\"\n\"}{end}" |grep sticky | awk -F'\t' 'BEGIN {printf "%-40s %-20s %-25s %-40s\n", "POD NAME", "STATUS", "CREATION TIMESTAMP", "IMAGE"} {printf "%-40s %-20s %-25s %-40s\n", $1, $2, $3, $4}'
  
  sleep 1
  clear
done

#Terminal 4: Toggle blue/green version, start a rollout
TMP_REPO=$(mktemp -d)
git clone -q http://admin:admin@scmm.localhost/scm/repo/argocd/example-apps $TMP_REPO && cd "$_"

sed -i 's/argoproj\/rollouts-demo:green/argoproj\/rollouts-demo:temp/g; s/argoproj\/rollouts-demo:blue/argoproj\/rollouts-demo:green/g; s/argoproj\/rollouts-demo:temp/argoproj\/rollouts-demo:blue/g' \
  apps/rollouts-sticky/rollout.yaml

git add apps/rollouts-sticky/rollout.yaml >/dev/null 2>&1

git commit -m "Set version $(cat apps/rollouts-sticky/rollout.yaml | awk '/image:/ {print $2}' | awk -F ':' '{print $NF}')" --author="Cloudogu <hello@cloudogu.com>"
git push -q
date '+%Y-%m-%d %H:%M:%S'
cd - && rm -rf $TMP_REPO
```

Watch how terminal 2 stays blue/green longer than the other one.

## Further reading
Example bases on these docs
* [Argo rollouts: getting-started/nginx](https://argoproj.github.io/argo-rollouts/getting-started/nginx/)   
  examples [here](https://github.com/argoproj/argo-rollouts/tree/master/docs/getting-started/nginx) 
* [Argo rollouts:  traffic-management/nginx](https://argoproj.github.io/argo-rollouts/features/traffic-management/nginx/)
* [Ingress-Nginx : Session affinity](https://kubernetes.github.io/ingress-nginx/examples/affinity/cookie/)

More docs:
* [Ingress-Nginx: Canary](https://kubernetes.github.io/ingress-nginx/examples/canary/)
* [Ingress-Nginx: Canary Annotations](https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/annotations/#canary)


## More image tags for rollouts-demo

Possible image tags (`regctl tag ls argoproj/rollouts-demo`)
```
bad-blue
bad-green
bad-orange
bad-purple
bad-red
bad-yellow
blue
green
latest
orange
purple
red
slow-blue
slow-green
slow-orange
slow-purple
slow-red
slow-yellow
v1-blue
v1-green
v1-orange
v1-purple
v1-red
v1-yellow
yellow
```