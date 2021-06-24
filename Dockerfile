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

FROM alpine
RUN apk update && \
   apk add --no-cache \
     bash=5.1.0-r0 \
     curl=7.77.0-r1 \
     apache2-utils=2.4.48-r0 \
     gettext=0.20.2-r2 \
     jq=1.6-r1 \
     git=2.30.2-r0

ENV HOME=/home
RUN chmod a=rwx -R ${HOME}

RUN git config --global user.email "job@gop.com" && \
    git config --global user.name "gop-job"

COPY --from=downloader /usr/local/bin  /usr/local/bin

WORKDIR /gop/repos
RUN git clone --bare https://github.com/cloudogu/spring-boot-helm-chart.git && \
    git clone --bare https://github.com/cloudogu/spring-petclinic.git && \
    git clone --bare https://github.com/cloudogu/gitops-build-lib.git && \
    git clone --bare https://github.com/cloudogu/ces-build-lib.git
ENV SPRING_BOOT_HELN_CHART_REPO /gop/repos/spring-boot-helm-chart.git
ENV SPRING_PETCLINIC_REPO /gop/repos/spring-petclinic.git
ENV GITOPS_BUILD_LIB_REPO /gop/repos/gitops-build-lib.git
ENV CES_BUILD_LIB_REPO /gop/repos/ces-build-lib.git

ENV HELM_CACHE_HOME="/home/.cache/helm" \
    HELM_CONFIG_HOME="/home/.config/helm" \
    HELM_DATA_HOME="/home/.local/share/helm" \
    HELM_PLUGINS="/home/.local/share/helm/plugins" \
    HELM_REGISTRY_CONFIG="/home/.config/helm/registry.json" \
    HELM_REPOSITORY_CACHE="/home/.cache/helm/repository" \
    HELM_REPOSITORY_CONFIG="/home/.config/helm/repositories.yaml"

WORKDIR /gop
COPY . /gop/

USER 1000

ENTRYPOINT ["scripts/apply.sh", "-y", "-x", "-c"]

ARG VCS_REF
ARG BUILD_DATE
LABEL org.opencontainers.image.title="k8s-gitops-playground" \
      org.opencontainers.image.source="https://github.com/cloudogu/k8s-gitops-playground" \
      org.opencontainers.image.url="https://github.com/cloudogu/k8s-gitops-playground" \
      org.opencontainers.image.documentation="https://github.com/cloudogu/k8s-gitops-playground" \
      org.opencontainers.image.vendor="cloudogu" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.description="Reproducible infrastructure to showcase GitOps workflows and evaluate different GitOps Operators" \ 
      org.opencontainers.image.version="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.ref.name="${VCS_REF}" \
      org.opencontainers.image.revision="${VCS_REF}"