FROM alpine:3.13.5 as alpine

FROM alpine as downloader
ARG K8S_VERSION=1.21.0
ARG HELM_VERSION=3.5.4
# TODO using wget will simplify commands and not require curl install
RUN apk add --no-cache \
      curl=7.76.1-r0 \
      gnupg=2.2.27-r0 \
      outils-sha256=0.9-r0

RUN curl --silent --show-error --fail --location --output /tmp/helm.tar.gz.asc https://github.com/helm/helm/releases/download/v${HELM_VERSION}/helm-v${HELM_VERSION}-linux-amd64.tar.gz.asc
RUN curl --silent --show-error --fail --location --output /tmp/helm.tar.gz.sha256 https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz.sha256
RUN curl --silent --show-error --fail --location --output /tmp/helm.tar.gz https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz

RUN tar -xf /tmp/helm.tar.gz
RUN cd /tmp && echo "$(cat /tmp/helm.tar.gz.sha256)  helm.tar.gz" | sha256sum -c

RUN curl --silent --show-error --fail --location https://raw.githubusercontent.com/helm/helm/main/KEYS | gpg --import
RUN gpg --batch --verify /tmp/helm.tar.gz.asc /tmp/helm.tar.gz

RUN mv linux-amd64/helm /usr/local/bin

RUN curl --silent --show-error --fail --location --output /tmp/kubectl.sha256 https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
RUN curl --silent --show-error --fail --location --output /tmp/kubectl https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl
# kubectl binary download does not seem to offer signatures
RUN cd /tmp && echo "$(cat /tmp/kubectl.sha256)  kubectl" | sha256sum -c
RUN mv /tmp/kubectl /usr/local/bin/kubectl
RUN chmod +x /usr/local/bin/kubectl 
# TODO add scripts (dont forget .dockerignore)

FROM alpine
# htpasswd, envsubst
RUN apk update && \
   apk add --no-cache \
     bash=5.1.0-r0 \
     curl=7.76.1-r0 \
     apache2-utils=2.4.46-r3 \
     gettext=0.20.2-r2 \
     jq=1.6-r1
COPY --from=downloader /usr/local/bin  /usr/local/bin
USER 1000
