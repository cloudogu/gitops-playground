# ============================================================================
# BUILD ARGUMENTS
# ============================================================================
# Keep in sync with the versions in pom.xml
ARG JDK_VERSION='17'

# ============================================================================
# STAGE 1: Maven Dependency Cache
# ============================================================================
# Purpose: Download and cache Maven dependencies separately to optimize rebuilds
# This stage only re-runs when pom.xml changes
FROM eclipse-temurin:${JDK_VERSION}-jdk-alpine AS maven-cache
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
WORKDIR /app

# Copy only dependency-related files first (for better layer caching)
COPY .mvn/ /app/.mvn/
COPY mvnw /app/
COPY pom.xml /app/

# Download all dependencies and plugins
RUN ./mvnw dependency:resolve-plugins dependency:go-offline -B

# ============================================================================
# STAGE 2: Maven Build
# ============================================================================
# Purpose: Compile and package the application into a JAR file
FROM eclipse-temurin:${JDK_VERSION}-jdk-alpine AS maven-build
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'

# Copy cached dependencies from previous stage
COPY --from=maven-cache /mvn/ /mvn/
COPY --from=maven-cache /app/ /app

COPY src/main /app/src/main
COPY compiler.groovy /app
COPY .git /app/.git

WORKDIR /app

RUN ./mvnw -B package -DskipTests
RUN mv $(ls -S target/*.jar | head -n 1) /app/gitops-playground.jar

# ============================================================================
# STAGE 3: Downloader
# ============================================================================
# Purpose: Download and prepare all external dependencies and tools
# - Kubectl CLI
# - Helm CLI
# - Git repositories
# - Jenkins plugins
# - Helm charts
FROM alpine:3 AS downloader

# Install required packages for downloading and verification
RUN apk add curl grep

# -----------------------------------------------------------------------------
# 3.1: Version Configuration
# -----------------------------------------------------------------------------
# When updating kubectl:
# * Update checksum from https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
# * Update in init-cluster.sh, vars.tf, Config.groovy and apply.sh
# When upgrading to 1.26+ we can verify kubectl signature with cosign!
# https://kubernetes.io/blog/2022/12/12/kubernetes-release-artifact-signing/
ARG K8S_VERSION=1.35.4
ARG KUBECTL_CHECKSUM=b529430df69a688fd61b64ad2299edb5fd71cb58be2a4779dba624c7d3510efd

# When updating Helm, also upgrade helm image in Config.groovy
ARG HELM_VERSION=4.1.4

# Install additional tools required for downloads
# bash curl unzip required for Jenkins downloader
RUN apk add --no-cache \
      gnupg \
      outils-sha256 \
      git \
      bash curl unzip zip

# -----------------------------------------------------------------------------
# 3.2: Prepare Directory Structure
# -----------------------------------------------------------------------------
RUN mkdir -p /dist/usr/local/bin
RUN mkdir -p /dist/home/.config
RUN chmod a=rwx -R /dist/home
RUN chmod 755 /dist/home/.config

ENV HOME=/tmp
WORKDIR /tmp

# -----------------------------------------------------------------------------
# 3.3: Download and Install Helm
# -----------------------------------------------------------------------------
# Download Helm tarball and signature
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors \
    --output helm.tar.gz \
    https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz

RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors \
    --output helm.tar.gz.asc \
    https://github.com/helm/helm/releases/download/v${HELM_VERSION}/helm-v${HELM_VERSION}-linux-amd64.tar.gz.asc

# Extract Helm
RUN tar -xf helm.tar.gz

# Verify Helm signature with GPG
RUN set -o pipefail && curl --location --fail --retry 20 --retry-connrefused --retry-all-errors \
    https://raw.githubusercontent.com/helm/helm/main/KEYS | \
    gpg --import --batch --no-default-keyring --keyring /tmp/keyring.gpg

RUN gpgv --keyring /tmp/keyring.gpg helm.tar.gz.asc helm.tar.gz

# Install Helm
RUN mv linux-amd64/helm /dist/usr/local/bin
ENV PATH=$PATH:/dist/usr/local/bin

# -----------------------------------------------------------------------------
# 3.4: Download and Install Kubectl
# -----------------------------------------------------------------------------
# Download kubectl binary
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors \
    --output kubectl \
    https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl

# Verify kubectl checksum (note: two spaces required!)
RUN echo "${KUBECTL_CHECKSUM}  kubectl" | sha256sum -c

# Install kubectl
RUN chmod +x /tmp/kubectl
RUN mv /tmp/kubectl /dist/usr/local/bin/kubectl

# -----------------------------------------------------------------------------
# 3.5: Clone External Git Repositories
# -----------------------------------------------------------------------------
# These repos are used in GitOps Playground examples and demos
WORKDIR /dist/gitops/repos

RUN git clone --bare https://github.com/cloudogu/gitops-build-lib.git
RUN git clone --bare https://github.com/cloudogu/ces-build-lib.git

# Configure git safe.directory to avoid "dubious ownership" warnings
# TODO: Remove once we migrate away from using git binary (e.g. in init-scmm.sh)
RUN cd /dist && for dir in $(find gitops/repos -type d  -maxdepth 1); do \
        git config --global --add safe.directory /"$dir"; \
    done
RUN cp /tmp/.gitconfig /dist/home/.gitconfig

# -----------------------------------------------------------------------------
# 3.6: Download Jenkins Plugins
# -----------------------------------------------------------------------------
COPY scripts/jenkins/plugins /jenkins
RUN /jenkins/download-plugins.sh /dist/gitops/jenkins-plugins

# -----------------------------------------------------------------------------
# 3.7: Download Helm Charts
# -----------------------------------------------------------------------------
COPY src/main/groovy/com/cloudogu/gitops/config/Config.groovy /tmp/
COPY scripts/downloadHelmCharts.sh /tmp/
RUN cd /dist/gitops && /tmp/downloadHelmCharts.sh /tmp/Config.groovy

# -----------------------------------------------------------------------------
# 3.8: Prepare Application Files
# -----------------------------------------------------------------------------
WORKDIR /tmp

# Copy runtime scripts, templates and cluster resources (not entire project to avoid bloat)
COPY scripts /dist/app/scripts
COPY argocd /dist/app/argocd
COPY templates /dist/app/templates

# Remove development scripts not needed in runtime
RUN cd /dist/app/scripts && rm -f downloadHelmCharts.sh

# Copy compiled JAR from maven-build stage
COPY --from=maven-build /app/gitops-playground.jar /dist/app/gitops-playground.jar

# -----------------------------------------------------------------------------
# 3.9: Configure JGit for Arbitrary User IDs
# -----------------------------------------------------------------------------
# Required to prevent Java exceptions from AccessDeniedException by jgit
# when running as arbitrary user (OpenShift compatibility)
RUN mkdir -p /dist/root/.config/jgit
RUN touch /dist/root/.config/jgit/config
RUN chmod +r /dist/root/ && chmod g+rw /dist/root/.config/jgit/

# ============================================================================
# STAGE 4: Runtime Image (Final)
# ============================================================================
# Purpose: Production-ready runtime image with compiled JAR only
# - JRE base (smaller than JDK)
# - No source code (security & size optimization)
# - Only compiled JAR with runtime dependencies
FROM eclipse-temurin:${JDK_VERSION}-jre-alpine AS runtime

# -----------------------------------------------------------------------------
# 4.1: Environment Variables
# -----------------------------------------------------------------------------
# Helm configuration
ENV HOME=/home \
    HELM_CACHE_HOME=/home/.cache/helm \
    HELM_CONFIG_HOME=/home/.config/helm \
    HELM_DATA_HOME=/home/.local/share/helm \
    HELM_PLUGINS=/home/.local/share/helm/plugins \
    HELM_REGISTRY_CONFIG=/home/.config/helm/registry.json \
    HELM_REPOSITORY_CACHE=/home/.cache/helm/repository \
    HELM_REPOSITORY_CONFIG=/home/.config/helm/repositories.yaml

# Application paths for pre-bundled resources
ENV GITOPS_BUILD_LIB_REPO=/gitops/repos/gitops-build-lib.git \
    CES_BUILD_LIB_REPO=/gitops/repos/ces-build-lib.git \
    JENKINS_PLUGIN_FOLDER=/gitops/jenkins-plugins/ \
    LOCAL_HELM_CHART_FOLDER=/gitops/charts/

WORKDIR /app

# -----------------------------------------------------------------------------
# 4.2: Install Runtime Dependencies
# -----------------------------------------------------------------------------
# unzip is needed for Jenkins plugin installation (install-plugins.sh)
RUN apk update --no-cache && apk upgrade --no-cache && \
    apk add --no-cache \
        bash \
        curl \
        gettext \
        jq \
        git \
        unzip

# -----------------------------------------------------------------------------
# 4.3: Copy Distribution Files
# -----------------------------------------------------------------------------
# Copy all prepared files from downloader stage:
# - Binaries (kubectl, helm)
# - Git repositories
# - Helm charts
# - Jenkins plugins
# - Application JAR and scripts
COPY --from=downloader /dist /

# -----------------------------------------------------------------------------
# 4.4: Create Non-Root User
# -----------------------------------------------------------------------------
# Temporarily use root to create user
USER 0
RUN adduser --disabled-password --home /home --no-create-home --uid 1000 user

# -----------------------------------------------------------------------------
# 4.5: Set User and Entrypoint
# -----------------------------------------------------------------------------
# Run as non-root user (OpenShift compatibility)
USER 1000:0

# Run production JAR directly
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/gitops-playground.jar"]

# -----------------------------------------------------------------------------
# 4.6: Image Metadata (OCI Labels)
# -----------------------------------------------------------------------------
ARG VCS_REF
ARG BUILD_DATE

LABEL org.opencontainers.image.title="gitops-playground" \
      org.opencontainers.image.source="https://github.com/cloudogu/gitops-playground" \
      org.opencontainers.image.url="https://github.com/cloudogu/gitops-playground" \
      org.opencontainers.image.documentation="https://github.com/cloudogu/gitops-playground" \
      org.opencontainers.image.vendor="cloudogu" \
      org.opencontainers.image.licenses="AGPL3.0" \
      org.opencontainers.image.description="Reproducible infrastructure to showcase GitOps workflows and evaluate different GitOps Operators" \
      org.opencontainers.image.version="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.ref.name="${VCS_REF}" \
      org.opencontainers.image.revision="${VCS_REF}"