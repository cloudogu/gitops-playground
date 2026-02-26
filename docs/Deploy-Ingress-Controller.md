# Ingress Controller

In the default installation the GitOps-Playground comes without an Ingress-Controller.  

We use Traefik as default Ingress-Controller.
It can be enabled via the configfile or parameter `--ingress`.

In order to make use of the ingress controller, it is recommended to use it in conjunction with [`--base-url`](#deploy-ingresses), which will create `Ingress` objects for all components of the GitOps playground.

The ingress controller is based on the helm chart [`ingress`](https://traefik.github.io/charts/).

Additional parameters from this chart's values.yaml file can be added to the installation through the gitops-playground [configuration file](#configuration-file).

Example:
```yaml
features:
  ingress:
    active: true
    helm:
      values:
        controller:
          replicaCount: 4
```
In this Example we override the default `controller.replicaCount` (GOP's default is 2).

This config file is merged with precedence over the defaults set by 
* [the GOP](argocd/cluster-resources/apps/ingress/templates/ingress-helm-values.ftl.yaml) and
* [the charts itself](https://github.com/traefik/traefik-helm-chart/blob/master/traefik/values.yaml).

# Deploy Ingresses

It is possible to deploy `Ingress` objects for all components. You can either

* set a common base url (`--base-url=https://example.com`) or
* individual URLS: <- falsch!
```
--argocd-url https://argocd.example.com 
--grafana-url https://grafana.example.com 
--vault-url https://vault.example.com 
--mail-url https://mail.example.com 
--petclinic-base-domain petclinic.example.com 
--nginx-base-domain nginx.example.com
```
* or both, where the individual URLs take precedence.

Note: 
* `jenkins-url` and `scmm-url` are for external services and do not lead to ingresses, but you can set them via `--base-url` for now.
* In order to make use of the `Ingress` you need an ingress controller.
  If your cluster does not provide one, the Playground can deploy one for you, via the [`--ingress` parameter](#deploy-ingress-controller).
* For this to work, you need to set an `*.example.com` DNS record to the externalIP of the ingress controller.

Alternatively, [hyphen-separated ingresses](#subdomains-vs-hyphen-separated-ingresses) can be created,
like http://argocd-example.com

## Subdomains vs hyphen-separated ingresses

* By default, the ingresses are built as subdomains of `--base-url`.  
* You can change this behavior using the parameter `--url-separator-hyphen`.  
* With this, hyphens are used instead of dots to separate application name from base URL.
* Examples: 
  * `--base-url=https://xyz.example.org`: `argocd.xyz.example.org` (default)  
  * `--base-url=https://xyz.example.org`: `argocd-xyz.example.org` (`--url-separator-hyphen`)
* This is useful when you have a wildcard certificate for the TLD, but use a subdomain as base URL.  
  Here, browsers accept the validity only for the first level of subdomains.

## Local ingresses

The ingresses can also be used when running the playground on your local machine:

* Ingresses might be easier to remember than arbitrary port numbers and look better in demos 
* With ingresses, we can execute our [local clusters](docs/k3d.md) in higher isolation or multiple playgrounds concurrently
* Ingresses are required [for running on Windows/Mac](#windows-or-mac).

To use them locally, 
* init your cluster (`init-cluster.sh`).
* apply your playground with the following parameters  
  * `--base-url=http://localhost` 
    * this is possible on Windows (tested on 11), Mac (tested on Ventura) or when using Linux with [systemd-resolved](https://www.freedesktop.org/software/systemd/man/systemd-resolved.service.html) (default in Ubuntu, not Debian)  
      As an alternative, you could add all `*.localhost` entries to your `hosts` file.  
      Use `kubectl get ingress -A` to get a full list 
    * Then, you can reach argocd on `http://argocd.localhost`, for example
  * `--base-url=http://local.gd` (or `127.0.0.1.nip.io`, `127.0.0.1.sslip.io`, or others)
    * This should work for all other machines that have access to the internet without further config 
    * Then, you can reach argocd on `http://argocd.local.gd`, for example
* Note that when using port 80, the URLs are shorter, but you run into issues because port 80 is regarded as a privileged port.
  Java applications seem not to be able to reach `localhost:80` or even `127.0.0.1:80` (`NoRouteToHostException`)
* You can change the port using `init-cluster.sh --bind-ingress-port=8080`.  
  When you do, make sure to append the same port when applying the playground: `--base-url=http://localhost:8080`
* If your setup requires you to bind to a specific interface, you can just pass it with e.g. `--bind-ingress-port=127.0.0.1:80`
