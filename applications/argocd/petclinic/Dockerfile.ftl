FROM ${baseImage}

# Set permissions to group 0 for openshift compatibility
# See https://docs.openshift.com/container-platform/4.15/openshift_images/create-images.html#use-uid_create-images
RUN mkdir /app && chown 1000:0 /app \
 && chmod -R g=u /app

COPY target/*.jar /app/petclinic.jar

EXPOSE 8080
USER 1000

ENTRYPOINT [ "java", "-server","-jar", "/app/petclinic.jar" ]
