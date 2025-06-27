ARG ENV=prod

# Keep in sync with the versions in pom.xml
ARG JDK_VERSION='17'

FROM alpine:3 AS alpine

# Keep in sync with the version in pom.xml
FROM ghcr.io/graalvm/native-image-community:${JDK_VERSION}-muslib-ol8 AS graal
FROM graal AS maven-cache
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
WORKDIR /app
COPY .mvn/ /app/.mvn/
COPY mvnw /app/
COPY pom.xml /app/
RUN ./mvnw dependency:resolve-plugins dependency:go-offline -B

FROM graal AS maven-build
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
COPY --from=maven-cache /mvn/ /mvn/
COPY --from=maven-cache /app/ /app
# Speed up build by not compiling tests
COPY src/main /app/src/main
COPY compiler.groovy /app
COPY .git /app/.git

WORKDIR /app
# Exclude code not needed in productive image
RUN cd /app/src/main/groovy/com/cloudogu/gitops/cli/ \
    && rm GenerateJsonSchema.groovy \
    && rm GitopsPlaygroundCliMainScripted.groovy
# Build native image without micronaut
RUN ./mvnw package -DskipTests
# Use simple name for largest jar file -> Easier reuse in later stages
RUN mv $(ls -S target/*.jar | head -n 1) /app/gitops-playground.jar


FROM alpine AS downloader
RUN apk add curl grep
# When updating,
# * also update the checksum found at https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl.sha256
# * also update in init-cluster.sh. vars.tf, Config.groovy and apply.sh
# When upgrading to 1.26 we can verify the kubectl signature with cosign!
# https://kubernetes.io/blog/2022/12/12/kubernetes-release-artifact-signing/
ARG K8S_VERSION=1.29.8
ARG KUBECTL_CHECKSUM=038454e0d79748aab41668f44ca6e4ac8affd1895a94f592b9739a0ae2a5f06a
# When updating, also upgrade helm image in Config
ARG HELM_VERSION=3.16.4
# bash curl unzip required for Jenkins downloader
RUN apk add --no-cache \
      gnupg \
      outils-sha256 \
      git \
      bash curl unzip zip

RUN mkdir -p /dist/usr/local/bin
RUN mkdir -p /dist/home/.config
RUN chmod a=rwx -R /dist/home

ENV HOME=/tmp
WORKDIR /tmp

# Helm
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors --output helm.tar.gz https://get.helm.sh/helm-v${HELM_VERSION}-linux-amd64.tar.gz
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors --output helm.tar.gz.asc https://github.com/helm/helm/releases/download/v${HELM_VERSION}/helm-v${HELM_VERSION}-linux-amd64.tar.gz.asc
RUN tar -xf helm.tar.gz
RUN set -o pipefail && curl --location --fail --retry 20 --retry-connrefused --retry-all-errors \
  https://raw.githubusercontent.com/helm/helm/main/KEYS | gpg --import --batch --no-default-keyring --keyring /tmp/keyring.gpg
RUN gpgv --keyring /tmp/keyring.gpg helm.tar.gz.asc helm.tar.gz
RUN mv linux-amd64/helm /dist/usr/local/bin
ENV PATH=$PATH:/dist/usr/local/bin

# Kubectl
RUN curl --location --fail --retry 20 --retry-connrefused --retry-all-errors --output kubectl https://dl.k8s.io/release/v${K8S_VERSION}/bin/linux/amd64/kubectl
# Without the two spaces the check fails!
RUN echo "${KUBECTL_CHECKSUM}  kubectl" | sha256sum -c
RUN chmod +x /tmp/kubectl
RUN mv /tmp/kubectl /dist/usr/local/bin/kubectl

# External Repos used in GOP
WORKDIR /dist/gitops/repos
RUN git clone --bare https://github.com/cloudogu/spring-petclinic.git
RUN git clone --bare https://github.com/cloudogu/spring-boot-helm-chart.git
RUN git clone --bare https://github.com/cloudogu/gitops-build-lib.git
RUN git clone --bare https://github.com/cloudogu/ces-build-lib.git

# Avoid "fatal: detected dubious ownership in repository"
# Once we migrate away from using git binary (e.g. in init-scmm.sh), we can remove this
RUN cd /dist && for dir in $(find gitops/repos -type d  -maxdepth 1); do \
        git config --global --add safe.directory /"$dir"; \
    done
RUN cp /tmp/.gitconfig /dist/home/.gitconfig

# Download Jenkins Plugin
COPY scripts/jenkins/plugins /jenkins
RUN /jenkins/download-plugins.sh /dist/gitops/jenkins-plugins

COPY src/main/groovy/com/cloudogu/gitops/config/Config.groovy /tmp/
COPY scripts/downloadHelmCharts.sh /tmp/
RUN cd /dist/gitops && /tmp/downloadHelmCharts.sh /tmp/Config.groovy

WORKDIR /tmp
# Prepare local files for later stages
COPY . /dist/app
# Remove dev stuff
RUN rm -r /dist/app/.git
RUN rm -r /dist/app/.mvn
RUN rm /dist/app/mvnw
RUN rm /dist/app/pom.xml
RUN rm /dist/app/compiler.groovy
RUN rm -r /dist/app/src/test
RUN cd /dist/app/scripts && rm downloadHelmCharts.sh apply-ng.sh
# For dev image
RUN mkdir /dist-dev
# Remove uncessary code and allow changing code in dev mode, less secure, but the intention of the dev image
# Execute bit is required to allow listing of dirs to everyone
RUN mv /dist/app/src /dist-dev/src && \
    chmod a=rwx -R /dist-dev/src && \
    rm -r /dist-dev/src/main/groovy/com/cloudogu/gitops/graal
COPY --from=maven-build /app/gitops-playground.jar /dist-dev/gitops-playground.jar
# Remove compiled GOP code from jar to avoid duplicate in dev image, allowing for scripting.
# Keep generated class Version, to avoid ClassNotFoundException.
RUN zip -d /dist-dev/gitops-playground.jar 'com/cloudogu/gitops/*' -x com/cloudogu/gitops/cli/Version.class

# Required to prevent Java exceptions resulting from AccessDeniedException by jgit when running arbitrary user
RUN mkdir -p /dist/root/.config/jgit
RUN touch /dist/root/.config/jgit/config
RUN chmod +r /dist/root/ && chmod g+rw /dist/root/.config/jgit/

# This stage builds a static binary using graal VM. For details see docs/developers.md#GraalVM
FROM graal AS native-image
ENV MAVEN_OPTS='-Dmaven.repo.local=/mvn'
RUN microdnf install gnupg

# Provide binaries used by apply-ng, so our runs with native-image-agent dont fail
# with "java.io.IOException: Cannot run program "kubectl"..." etc.
RUN microdnf install iproute

WORKDIR /app

# Copy only binaries, not jenkins plugins. Avoids having to rebuild native image only plugin changes
COPY --from=downloader /dist/usr/ /usr/
COPY --from=downloader /dist/app/ /app/
# copy only resources that we need to compile the binary
COPY --from=maven-build /app/gitops-playground.jar /app/

# Create Graal native image config
RUN java -agentlib:native-image-agent=config-output-dir=conf/ -jar gitops-playground.jar || true
# Run again with different params in order to avoid NoSuchMethodException with config file
RUN printf 'registry:\n  active: true\njenkins:\n  active: true\ncontent:\n  examples: true\napplication:\n  "yes": true\nfeatures:\n  argocd:\n    active: true\n    env:\n      - name: mykey\n        value: myValue\n  secrets:\n    vault:\n      mode: "dev"\n  exampleApps:\n    petclinic:\n      baseDomain: "base"' > config.yaml && \
    java -agentlib:native-image-agent=config-merge-dir=conf/ -jar gitops-playground.jar \
      --trace --config-file=config.yaml || true
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
    --features=com.cloudogu.gitops.graal.groovy.GroovyApplicationRegistrationFeature,com.cloudogu.gitops.graal.groovy.GroovyDgmClassesRegistrationFeature,com.cloudogu.gitops.graal.jgit.JGitReflectionFeature,com.cloudogu.gitops.graal.okhttp.OkHttpReflectionFeature \
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



FROM alpine AS prod
# copy groovy cli binary from native-image stage
COPY --from=native-image /app/apply-ng app/apply-ng
ENTRYPOINT ["/app/apply-ng"]


FROM eclipse-temurin:${JDK_VERSION}-jre-alpine AS dev

# apply-ng.sh is part of the dev image and allows trying changing groovy code inside the image for debugging
# Allow changing code in dev mode, less secure, but the intention of the dev image
COPY --chmod=777 scripts/apply-ng.sh /app/scripts/
COPY --from=downloader /dist-dev /app

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
    "/app/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMainScripted.groovy" ]

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
      org.opencontainers.image.licenses="AGPL3.0" \
      org.opencontainers.image.description="Reproducible infrastructure to showcase GitOps workflows and evaluate different GitOps Operators" \
      org.opencontainers.image.version="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.ref.name="${VCS_REF}" \
      org.opencontainers.image.revision="${VCS_REF}"
