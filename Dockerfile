FROM alpine:3.14.0 as alpine

FROM alpine as downloader
# When updating, 
# * also update the checksum found at https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
# * also update k8s-related versions in vars.tf and init-cluster.sh
ARG K8S_VERSION=1.21.2
ARG KUBECTL_CHECKSUM=55b982527d76934c2f119e70bf0d69831d3af4985f72bb87cd4924b1c7d528da
# When updating, also update the checksum found at https://github.com/helm/helm/releases
ARG HELM_VERSION=3.6.2
ARG HELM_CHECKSUM=f3a4be96b8a3b61b14eec1a35072e1d6e695352e7a08751775abf77861a0bf54
RUN apk add --no-cache \
      gnupg \
      outils-sha256 \
      git

RUN mkdir -p /dist/usr/local/bin
ENV HOME=/dist/home
RUN mkdir -p /dist/home
RUN chmod a=rwx -R ${HOME}

WORKDIR /tmp

# Helm
RUN wget -q -O helm.tar.gz https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz 
RUN wget -q -O helm.tar.gz.asc https://github.com/helm/helm/releases/download/v${HELM_VERSION}/helm-v${HELM_VERSION}-linux-amd64.tar.gz.asc
RUN tar -xf helm.tar.gz
# Without the two spaces the check fails!
RUN echo "${HELM_CHECKSUM}  helm.tar.gz" | sha256sum -c
RUN wget -q https://raw.githubusercontent.com/helm/helm/main/KEYS -O- | gpg --import
RUN gpg --batch --verify helm.tar.gz.asc helm.tar.gz
RUN mv linux-amd64/helm /dist/usr/local/bin

# Kubectl
RUN wget -q -O kubectl.sha256 https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
RUN wget -q -O kubectl https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl
# kubectl binary download does not seem to offer signatures
RUN echo "${KUBECTL_CHECKSUM}  kubectl" | sha256sum -c
RUN chmod +x /tmp/kubectl
RUN mv /tmp/kubectl /dist/usr/local/bin/kubectl

# External Repos used in GOP
WORKDIR /dist/gop/repos
RUN git clone --bare https://github.com/cloudogu/spring-petclinic.git 
RUN git clone --bare https://github.com/cloudogu/spring-boot-helm-chart.git
RUN git clone --bare https://github.com/cloudogu/gitops-build-lib.git
RUN git clone --bare https://github.com/cloudogu/ces-build-lib.git

# Creates /dist/home/.gitconfig
RUN git config --global user.email "hello@cloudogu.com" && \
    git config --global user.name "Cloudogu"


FROM alpine

ENV HOME=/home \
    HELM_CACHE_HOME=/home/.cache/helm \
    HELM_CONFIG_HOME=/home/.config/helm \
    HELM_DATA_HOME=/home/.local/share/helm \
    HELM_PLUGINS=/home/.local/share/helm/plugins \
    HELM_REGISTRY_CONFIG=/home/.config/helm/registry.json \
    HELM_REPOSITORY_CACHE=/home/.cache/helm/repository \
    HELM_REPOSITORY_CONFIG=/home/.config/helm/repositories.yaml \
    SPRING_BOOT_HELM_CHART_REPO=/gop/repos/spring-boot-helm-chart.git \
    SPRING_PETCLINIC_REPO=/gop/repos/spring-petclinic.git \
    GITOPS_BUILD_LIB_REPO=/gop/repos/gitops-build-lib.git \
    CES_BUILD_LIB_REPO=/gop/repos/ces-build-lib.git

WORKDIR /app

ENTRYPOINT ["scripts/apply.sh"]

RUN apk update && apk upgrade && \
    apk add --no-cache \
     bash \
     curl \
     apache2-utils \
     gettext \
     jq \
     git

USER 1000

COPY --from=downloader /dist /

COPY . /app/

ARG VCS_REF
ARG BUILD_DATE
LABEL org.opencontainers.image.title="gitops-playground" \
      org.opencontainers.image.source="https://github.com/cloudogu/gitops-playground" \
      org.opencontainers.image.url="https://github.com/cloudogu/gitops-playground" \
      org.opencontainers.image.documentation="https://github.com/cloudogu/gitops-playground" \
      org.opencontainers.image.vendor="cloudogu" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.description="Reproducible infrastructure to showcase GitOps workflows and evaluate different GitOps Operators" \ 
      org.opencontainers.image.version="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.ref.name="${VCS_REF}" \
      org.opencontainers.image.revision="${VCS_REF}"