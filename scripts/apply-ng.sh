#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

# Entry point for the new generation of our apply script, written in groovy  
function main() {
    
  if [[ -f "$PLAYGROUND_DIR/apply-ng" ]]; then
      "$PLAYGROUND_DIR"/apply-ng "$@"
  else 
      echo "apply-ng binary not found, calling groovy scripts"
      groovy --classpath "$PLAYGROUND_DIR"/src/main/groovy \
        "$PLAYGROUND_DIR"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy "$@" 
  fi
}

function groovy() {
  # We don't need the groovy "binary" (script) to start, because the gitops-playground.jar already contains groovy-all.

  # Write starter-conf (required by groovy)
  # See https://github.com/apache/groovy/blob/master/src/conf/groovy-starter.conf
  # No write permission on $PLAYGROUND_DIR, so use /tmp
  GROOVY_CONF=/tmp/groovy-starter.conf
  
cat << EOF > $GROOVY_CONF
    # load required libraries
    load !{groovy.home}/lib/*.jar

    # load user specific libraries
    load !{user.home}/.groovy/lib/*.jar

    # tools.jar for ant tasks
    load \${tools.jar}
EOF
  
  # Set params like startGroovy does (which is called by the "groovy" script)
  # See https://github.com/apache/groovy/blob/master/src/bin/startGroovy
  java \
    --add-modules=ALL-SYSTEM \
    -Dgroovy.jaxb=jaxb \
    -classpath /app/gitops-playground.jar \
    -Dscript.name="$0" \
    -Dprogram.name="$(basename "$0")" \
    -Dgroovy.starter.conf="$GROOVY_CONF" \
    -Dgroovy.home=$PLAYGROUND_DIR \
    org.codehaus.groovy.tools.GroovyStarter \
          --main groovy.ui.GroovyMain \
          --conf "$GROOVY_CONF" \
          --classpath . \
           "$@" 
}

main "$@"