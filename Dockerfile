ARG ENV=prod

# Keep in sync with the versions in pom.xml
ARG JDK_VERSION='17'
# Set by the micronaut BOM, see pom.xml
ARG GRAAL_VERSION='22.3.0'

FROM alpine:3.17 as alpine

# Keep in sync with the version in pom.xml
FROM ghcr.io/graalvm/graalvm-ce:ol8-java${JDK_VERSION}-${GRAAL_VERSION} AS graal

FROM graal as maven-cache
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
WORKDIR /app
COPY .mvn/ /app/.mvn/
COPY mvnw /app/
COPY pom.xml /app/
RUN ./mvnw dependency:resolve-plugins dependency:go-offline -B 

FROM graal as maven-build
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
COPY --from=maven-cache /mvn/ /mvn/
COPY --from=maven-cache /app/ /app
# Speed up build by not compiling tests
COPY src/main /app/src/main
COPY compiler.groovy /app

WORKDIR /app
# Build native image without micronaut
RUN ./mvnw package -DskipTests
# Use simple name for largest jar file -> Easier reuse in later stages
RUN mv $(ls -S target/*.jar | head -n 1) /app/gitops-playground.jar


FROM alpine as downloader
# When updating, 
# * also update the checksum found at https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
# * also update in init-cluster.sh. vars.tf, ApplicationConfigurator.groovy and apply.sh
# When upgrading to 1.26 we can verify the kubectl signature with cosign!
# https://kubernetes.io/blog/2022/12/12/kubernetes-release-artifact-signing/
ARG K8S_VERSION=1.25.5
ARG KUBECTL_CHECKSUM=6a660cd44db3d4bfe1563f6689cbe2ffb28ee4baf3532e04fff2d7b909081c29
# When updating, also update the checksum found at https://github.com/helm/helm/releases
ARG HELM_VERSION=3.10.3
ARG HELM_CHECKSUM=950439759ece902157cf915b209b8d694e6f675eaab5099fb7894f30eeaee9a2
# bash curl unzip required for Jenkins downloader
RUN apk add --no-cache \
      gnupg \
      outils-sha256 \
      git \
      bash curl unzip 

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
RUN wget -q -O kubectl https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl
RUN echo "${KUBECTL_CHECKSUM}  kubectl" | sha256sum -c
RUN chmod +x /tmp/kubectl
RUN mv /tmp/kubectl /dist/usr/local/bin/kubectl

# External Repos used in GOP
WORKDIR /dist/gitops/repos
RUN git clone --bare https://github.com/cloudogu/spring-petclinic.git 
RUN git clone --bare https://github.com/cloudogu/spring-boot-helm-chart.git
RUN git clone --bare https://github.com/cloudogu/gitops-build-lib.git
RUN git clone --bare https://github.com/cloudogu/ces-build-lib.git

# Creates /dist/home/.gitconfig
RUN git config --global user.email "hello@cloudogu.com" && \
    git config --global user.name "Cloudogu"

# Download Jenkins Plugin
COPY scripts/jenkins/plugins /jenkins
RUN /jenkins/download-plugins.sh /dist/gitops/jenkins-plugins

# Prepare local files for later stages
COPY . /dist/app
# Remove dev stuff
RUN rm -r /dist/app/src
RUN rm -r /dist/app/.mvn
RUN rm /dist/app/mvnw
RUN rm /dist/app/pom.xml
RUN rm /dist/app/compiler.groovy

FROM graal as native-image
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
RUN gu install native-image

# Set up musl, in order to produce a static image compatible to alpine
# See 
# https://github.com/oracle/graal/issues/2824 and 
# https://github.com/oracle/graal/blob/vm-ce-22.2.0.1/docs/reference-manual/native-image/guides/build-static-and-mostly-static-executable.md
ARG RESULT_LIB="/musl"
RUN mkdir ${RESULT_LIB} && \
    curl -L -o musl.tar.gz https://more.musl.cc/10.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz && \
    tar -xvzf musl.tar.gz -C ${RESULT_LIB} --strip-components 1 && \
    cp /usr/lib/gcc/x86_64-redhat-linux/8/libstdc++.a ${RESULT_LIB}/lib/
ENV CC=/musl/bin/gcc
RUN curl -L -o zlib.tar.gz https://zlib.net/zlib-1.2.13.tar.gz && \
    mkdir zlib && tar -xvzf zlib.tar.gz -C zlib --strip-components 1 && \
    cd zlib && ./configure --static --prefix=/musl && \
    make && make install && \
    cd / && rm -rf /zlib && rm -f /zlib.tar.gz
ENV PATH="$PATH:/musl/bin"

# Provide binaries used by apply-ng, so our runs with native-image-agent dont fail 
# with "java.io.IOException: Cannot run program "kubectl"..." etc.
RUN microdnf install iproute
# Copy only binaries, not jenkins plugins. Avoids having to rebuild native image only plugin changes
COPY --from=downloader /dist/usr/ /usr/

# copy only resources that we need to compile the binary
COPY --from=maven-build /app/gitops-playground.jar /app/

WORKDIR /app

# Create Graal native image config
RUN java -agentlib:native-image-agent=config-output-dir=conf/ -jar gitops-playground.jar || true
# Run again with different params in order to avoid further ClassNotFoundExceptions
RUN java -agentlib:native-image-agent=config-merge-dir=conf/ -jar gitops-playground.jar \
      --yes --jenkins-url=a --scmm-url=a \
      --jenkins-username=a --jenkins-password=a --scmm-username=a--scmm-password=a --password=a \
      --registry-url=a --registry-path=a --remote --argocd --debug --trace \
    || true

RUN native-image -Dgroovy.grape.enable=false \
    -H:+ReportExceptionStackTraces \
    -H:ConfigurationFileDirectories=conf/ \
    --static \
    --allow-incomplete-classpath \
    --report-unsupported-elements-at-runtime \
    --diagnostics-mode \
    --initialize-at-run-time=org.codehaus.groovy.control.XStreamUtils,groovy.grape.GrapeIvy,org.codehaus.groovy.vmplugin.v8.Java8\$LookupHolder \
    --initialize-at-build-time \
    --no-fallback \
    --libc=musl \
    -jar gitops-playground.jar \
    apply-ng

FROM alpine as prod
# copy groovy cli binary from native-image stage
COPY --from=native-image /app/apply-ng app/apply-ng


FROM eclipse-temurin:${JDK_VERSION}-jre-alpine as dev

COPY scripts/apply-ng.sh /app/scripts/
COPY --from=maven-build /app/gitops-playground.jar /app/
COPY src /app/src
# Allow initialization in final FROM ${ENV} stage
USER 0


# Pick final image according to build-arg
FROM ${ENV}
ENV HOME=/home \
    HELM_CACHE_HOME=/home/.cache/helm \
    HELM_CONFIG_HOME=/home/.config/helm \
    HELM_DATA_HOME=/home/.local/share/helm \
    HELM_PLUGINS=/home/.local/share/helm/plugins \
    HELM_REGISTRY_CONFIG=/home/.config/helm/registry.json \
    HELM_REPOSITORY_CACHE=/home/.cache/helm/repository \
    HELM_REPOSITORY_CONFIG=/home/.config/helm/repositories.yaml \
    SPRING_BOOT_HELM_CHART_REPO=/gitops/repos/spring-boot-helm-chart.git \
    SPRING_PETCLINIC_REPO=/gitops/repos/spring-petclinic.git \
    GITOPS_BUILD_LIB_REPO=/gitops/repos/gitops-build-lib.git \
    CES_BUILD_LIB_REPO=/gitops/repos/ces-build-lib.git \
    JENKINS_PLUGIN_FOLDER=/gitops/jenkins-plugins/

WORKDIR /app

ENTRYPOINT ["scripts/apply.sh"]

# Unzip is needed for downloading docker plugins (install-plugins.sh)
RUN apk update --no-cache && apk upgrade --no-cache && \
  apk add --no-cache \
     bash \
     curl \
     apache2-utils \
     gettext \
     jq \
     git \
    unzip

USER 1000

COPY --from=downloader /dist /

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