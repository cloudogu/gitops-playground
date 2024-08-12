#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

TRACE=${TRACE:-}
[[ $TRACE == true ]] && set -x;

ABSOLUTE_BASEDIR="$(cd "$(dirname $0)" && pwd)"
PLAYGROUND_DIR="$(cd ${ABSOLUTE_BASEDIR} && cd .. && pwd)"
# Allow for overriding the folder to jar via env var
export CLASSPATH="${CLASSPATH:-${PLAYGROUND_DIR}/gitops-playground.jar}"

function apply-ng() {
  groovy --classpath "$PLAYGROUND_DIR"/src/main/groovy \
    "$PLAYGROUND_DIR"/src/main/groovy/com/cloudogu/gitops/cli/GitopsPlaygroundCliMainScripted.groovy "$@"
}

# Runs groovy files without needing groovy
function groovy() {
  # We don't need the groovy "binary" (script) to start, because the gitops-playground.jar already contains groovy-all.
  # Note that gitops-playground.jar is passed via env var CLASSPATH

  # Set params like startGroovy does (which is called by the "groovy" script)
  # See https://github.com/apache/groovy/blob/master/src/bin/startGroovy
  java \
    org.codehaus.groovy.tools.GroovyStarter \
    --main groovy.ui.GroovyMain \
    "$@"
}

apply-ng "$@"
