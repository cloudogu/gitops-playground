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
RUN apk add curl grep
# When updating, 
# * also update the checksum found at https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
# * also update in init-cluster.sh. vars.tf, ApplicationConfigurator.groovy and apply.sh
# When upgrading to 1.26 we can verify the kubectl signature with cosign!
# https://kubernetes.io/blog/2022/12/12/kubernetes-release-artifact-signing/
ARG K8S_VERSION=1.29.1
ARG KUBECTL_CHECKSUM=69ab3a931e826bf7ac14d38ba7ca637d66a6fcb1ca0e3333a2cafdf15482af9f
# When updating, also update the checksum found at https://github.com/helm/helm/releases
ARG HELM_VERSION=3.14.4
ARG HELM_CHECKSUM=a5844ef2c38ef6ddf3b5a8f7d91e7e0e8ebc39a38bb3fc8013d629c1ef29c259
# bash curl unzip required for Jenkins downloader
RUN apk add --no-cache \
      gnupg \
      outils-sha256 \
      git \
      bash curl unzip 

RUN mkdir -p /dist/usr/local/bin
RUN mkdir -p /dist/home/.config
RUN chmod a=rwx -R /dist/home

ENV HOME=/tmp
WORKDIR /tmp

# Helm
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors --output helm.tar.gz https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz 
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors --output helm.tar.gz.asc https://github.com/helm/helm/releases/download/v${HELM_VERSION}/helm-v${HELM_VERSION}-linux-amd64.tar.gz.asc
RUN tar -xf helm.tar.gz
# Without the two spaces the check fails!
RUN echo "${HELM_CHECKSUM}  helm.tar.gz" | sha256sum -c
RUN set -o pipefail && curl --location --fail --retry 20 --retry-connrefused --retry-all-errors \
  https://raw.githubusercontent.com/helm/helm/main/KEYS | gpg --import
RUN gpg --batch --verify helm.tar.gz.asc helm.tar.gz
RUN mv linux-amd64/helm /dist/usr/local/bin
ENV PATH=$PATH:/dist/usr/local/bin

# Kubectl
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors --output kubectl https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl
RUN echo "${KUBECTL_CHECKSUM}  kubectl" | sha256sum -c
RUN chmod +x /tmp/kubectl
RUN mv /tmp/kubectl /dist/usr/local/bin/kubectl

# External Repos used in GOP
WORKDIR /dist/gitops/repos
RUN git clone --bare https://github.com/cloudogu/spring-petclinic.git 
RUN git clone --bare https://github.com/cloudogu/spring-boot-helm-chart.git
RUN git clone --bare https://github.com/cloudogu/gitops-build-lib.git
RUN git clone --bare https://github.com/cloudogu/ces-build-lib.git

# Download Jenkins Plugin
COPY scripts/jenkins/plugins /jenkins
RUN /jenkins/download-plugins.sh /dist/gitops/jenkins-plugins

COPY src/main/groovy/com/cloudogu/gitops/config/ApplicationConfigurator.groovy /tmp/
COPY scripts/downloadHelmCharts.sh /tmp/
RUN cd /dist/gitops && /tmp/downloadHelmCharts.sh /tmp/ApplicationConfigurator.groovy

WORKDIR /tmp
# Prepare local files for later stages
COPY . /dist/app
# Remove dev stuff
RUN rm -r /dist/app/.mvn
RUN rm /dist/app/mvnw
RUN rm /dist/app/pom.xml
RUN rm /dist/app/compiler.groovy
RUN cd /dist/app/scripts && rm downloadHelmCharts.sh apply-ng.sh
# For dev image
RUN mv /dist/app/src /src-without-graal && rm -r /src-without-graal/main/groovy/com/cloudogu/gitops/graal
# Required to prevent Java exceptions resulting from AccessDeniedException by jgit when running arbitrary user
RUN mkdir -p /dist/root/.config/jgit
RUN touch /dist/root/.config/jgit/config
RUN chmod +r /dist/root/ && chmod g+rw /dist/root/.config/jgit/

# This stage builds a static binary using graal VM. For details see docs/developers.md#GraalVM
FROM graal as native-image
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
RUN gu install native-image

# Set up musl, in order to produce a static image compatible to alpine
ARG RESULT_LIB="/musl"
# TODO verify ASC?
RUN mkdir ${RESULT_LIB} && \
    curl -L -o musl.tar.gz https://more.musl.cc/10.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz && \
    tar -xvzf musl.tar.gz -C ${RESULT_LIB} --strip-components 1 && \
    cp /usr/lib/gcc/x86_64-redhat-linux/8/libstdc++.a ${RESULT_LIB}/lib/
ENV CC=/musl/bin/gcc
RUN curl -L -o zlib.tar.gz https://github.com/madler/zlib/releases/download/v1.2.13/zlib-1.2.13.tar.gz && \
    mkdir zlib && tar -xvzf zlib.tar.gz -C zlib --strip-components 1 && \
    cd zlib && ./configure --static --prefix=/musl && \
    make && make install && \
    cd / && rm -rf /zlib && rm -f /zlib.tar.gz
ENV PATH="$PATH:/musl/bin"

# Provide binaries used by apply-ng, so our runs with native-image-agent dont fail 
# with "java.io.IOException: Cannot run program "kubectl"..." etc.
RUN microdnf install iproute

WORKDIR /app

# Copy only binaries, not jenkins plugins. Avoids having to rebuild native image only plugin changes
COPY --from=downloader /dist/usr/ /usr/
# copy only resources that we need to compile the binary
COPY --from=maven-build /app/gitops-playground.jar /app/

# Create Graal native image config
RUN java -agentlib:native-image-agent=config-output-dir=conf/ -jar gitops-playground.jar || true
# Run again with different params in order to avoid NoSuchMethodException with config file
RUN echo 'features: {}' > config.yaml  && \
    java -agentlib:native-image-agent=config-merge-dir=conf/ -jar gitops-playground.jar \
      --yes --config-file=config.yaml || true \
# Run again with different params in order to avoid NoSuchMethodException with output-config file
RUN java -agentlib:native-image-agent=config-merge-dir=conf/ -jar gitops-playground.jar \
      --yes  --output-config-file || true
RUN native-image -Dgroovy.grape.enable=false \
    -H:+ReportExceptionStackTraces \
    -H:ConfigurationFileDirectories=conf/ \
    -H:IncludeResourceBundles=org.eclipse.jgit.internal.JGitText \
    -H:DynamicProxyConfigurationFiles=conf/proxy-config.json \
    -H:DynamicProxyConfigurationResources=proxy-config.json \
    -H:ReflectionConfigurationFiles=conf/reflect-config.json \
    -H:ReflectionConfigurationResources=reflect-config.json \
    --features=com.cloudogu.gitops.graal.groovy.GroovyApplicationRegistrationFeature,com.cloudogu.gitops.graal.groovy.GroovyDgmClassesRegistrationFeature,com.cloudogu.gitops.graal.jgit.JGitReflectionFeature \
    --static \
    --allow-incomplete-classpath \
    --report-unsupported-elements-at-runtime \
    --diagnostics-mode \
    --initialize-at-run-time=org.codehaus.groovy.control.XStreamUtils,groovy.grape.GrapeIvy,org.codehaus.groovy.vmplugin.v8.Java8\$LookupHolder,org.eclipse.jgit.lib.RepositoryCache,org.eclipse.jgit.internal.storage.file.WindowCache,org.eclipse.jgit.transport.HttpAuthMethod\$Digest,org.eclipse.jgit.lib.GpgSigner,io.micronaut.context.env.exp.RandomPropertyExpressionResolver\$LazyInit \
    --initialize-at-build-time \
    --no-fallback \
    --libc=musl \
    --install-exit-handlers \
    -jar gitops-playground.jar \
    apply-ng



FROM alpine as prod
# copy groovy cli binary from native-image stage
COPY --from=native-image /app/apply-ng app/apply-ng
ENTRYPOINT ["/app/apply-ng"]


FROM eclipse-temurin:${JDK_VERSION}-jre-alpine as dev

# apply-ng.sh is part of the dev image and allows trying changing groovy code inside the image for debugging
COPY scripts/apply-ng.sh /app/scripts/
COPY --from=maven-build /app/gitops-playground.jar /app/
COPY --from=downloader /src-without-graal  /app/src
# Allow initialization in final FROM ${ENV} stage
USER 0
# Avoids ERROR org.eclipse.jgit.util.FS - Cannot save config file 'FileBasedConfig[/app/?/.config/jgit/config]'
RUN adduser --disabled-password --home /home --no-create-home --uid 1000 user

# We're explicitly not calling apply-ng.sh here, so the java process is started as PID 1
# and can handles signals such as ctrl+c
ENTRYPOINT [ "java", \
    "-classpath", "/app/gitops-playground.jar", \
    "org.codehaus.groovy.tools.GroovyStarter", \
    "--main", "groovy.ui.GroovyMain", \
    "--classpath", "/app/src/main/groovy", \
    "/app/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy" ]

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
    JENKINS_PLUGIN_FOLDER=/gitops/jenkins-plugins/ \
    LOCAL_HELM_CHART_FOLDER=/gitops/charts/

WORKDIR /app

# Unzip is needed for downloading docker plugins (install-plugins.sh)
RUN apk update --no-cache && apk upgrade --no-cache && \
  apk add --no-cache \
     bash \
     curl \
     gettext \
     jq \
     git \
    unzip

USER 1000:0

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
