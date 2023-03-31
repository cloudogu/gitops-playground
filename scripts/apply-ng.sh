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
  
  # Set params like startGroovy does (which is called by the "groovy" script)
  # See https://github.com/apache/groovy/blob/master/src/bin/startGroovy
  java \
    -classpath /app/gitops-playground.jar \
    org.codehaus.groovy.tools.GroovyStarter \
          --main groovy.ui.GroovyMain \
           "$@" 
}

main "$@"