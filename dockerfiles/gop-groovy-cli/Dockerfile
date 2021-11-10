FROM ghcr.io/graalvm/graalvm-ce:21.1.0 AS graal

FROM graal as maven-cache
ENV MAVEN_OPTS=-Dmaven.repo.local=/mvn
WORKDIR /app
COPY .mvn/ /app/.mvn/
COPY mvnw /app/ 
COPY pom.xml /app/
RUN ./mvnw dependency:go-offline

FROM graal as native-image
ENV MAVEN_OPTS=-Dmaven.repo.local=/mvn
RUN gu install native-image

COPY --from=maven-cache /mvn/ /mvn/
COPY --from=maven-cache /app/ /app
WORKDIR /app
COPY . /app

# Build native image micronaut
#  ./mvnw package -Dpackaging=native-image

# Build native image without micronaut
RUN ./mvnw package -DskipTests

# Create Graal native image config for largest jar file 
RUN java -agentlib:native-image-agent=config-output-dir=conf/ -jar $(ls -S target/*.jar | head -n 1)

RUN native-image -Dgroovy.grape.enable=false \
    -H:+ReportExceptionStackTraces \
    -H:ConfigurationFileDirectories=conf/ \
    --static \
    --allow-incomplete-classpath   \
    --report-unsupported-elements-at-runtime \
    --initialize-at-run-time=org.codehaus.groovy.control.XStreamUtils,groovy.grape.GrapeIvy \
    --initialize-at-build-time \
    --no-fallback \
    --no-server \
    -jar $(ls -S target/*.jar | head -n 1) \
    app

FROM scratch
COPY --from=native-image /app/app /app
ENTRYPOINT ["/app"]
