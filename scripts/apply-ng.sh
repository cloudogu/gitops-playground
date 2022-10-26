#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

# Entry point for the new generation of our apply script, written in groovy  

if [[ -f "$PLAYGROUND_DIR/apply-ng" ]]; then
    "$PLAYGROUND_DIR"/apply-ng "$@"
else 
    echo "apply-ng binary not found, calling groovy scripts"
    # Classpath seems not to load jars, so for this to work, 
    # we need to make sure to put gitops-playground-cli-*.jar into ~/.groovy/lib
    # https://stackoverflow.com/questions/10585808/groovy-script-classpath 
    groovy --classpath "$PLAYGROUND_DIR"/src/main/groovy \
      "$PLAYGROUND_DIR"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy "$@" 
fi