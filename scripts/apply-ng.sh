#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

ABSOLUTE_BASEDIR="$(cd "$(dirname $0)" && pwd)"
PLAYGROUND_DIR="$(cd ${ABSOLUTE_BASEDIR} && cd .. && pwd)"

function apply-ng() {
  groovy --classpath "$PLAYGROUND_DIR"/src/main/groovy \
    "$PLAYGROUND_DIR"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMain.groovy "$@"
}

# Runs groovy files without needing groovy
function groovy() {
  # We don't need the groovy "binary" (script) to start, because the gitops-playground.jar already contains groovy-all.

  # Set params like startGroovy does (which is called by the "groovy" script)
  # See https://github.com/apache/groovy/blob/master/src/bin/startGroovy
  java \
    -classpath "$PLAYGROUND_DIR"/gitops-playground.jar \
    org.codehaus.groovy.tools.GroovyStarter \
    --main groovy.ui.GroovyMain \
    "$@"
}

apply-ng "$@"
