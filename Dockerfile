FROM alpine:3.13.5 as alpine

FROM alpine as downloader
# When updating, also update the checksum found at https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
ARG K8S_VERSION=1.21.0
ARG KUBECTL_CHECKSUM=9f74f2fa7ee32ad07e17211725992248470310ca1988214518806b39b1dad9f0
# When updating, also update the checksum found at https://github.com/helm/helm/releases
ARG HELM_VERSION=3.5.4
ARG HELM_CHECKSUM=a8ddb4e30435b5fd45308ecce5eaad676d64a5de9c89660b56face3fe990b318
RUN apk add --no-cache \
      gnupg=2.2.27-r0 \
      outils-sha256=0.9-r0

WORKDIR /tmp
RUN wget -q -O helm.tar.gz https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz 
RUN wget -q -O helm.tar.gz.asc https://github.com/helm/helm/releases/download/v${HELM_VERSION}/helm-v${HELM_VERSION}-linux-amd64.tar.gz.asc

RUN tar -xf helm.tar.gz
# Without the two spaces the check fails!
RUN echo "${HELM_CHECKSUM}  helm.tar.gz" | sha256sum -c

RUN wget -q https://raw.githubusercontent.com/helm/helm/main/KEYS -O- | gpg --import
RUN gpg --batch --verify helm.tar.gz.asc helm.tar.gz

RUN mv linux-amd64/helm /usr/local/bin
RUN wget -q -O kubectl.sha256 https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
RUN wget -q -O kubectl https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl
# kubectl binary download does not seem to offer signatures
RUN echo "${KUBECTL_CHECKSUM}  kubectl" | sha256sum -c
RUN mv /tmp/kubectl /usr/local/bin/kubectl
RUN chmod +x /usr/local/bin/kubectl 
# TODO add scripts (dont forget .dockerignore) and apply.sh as entrypoint

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
