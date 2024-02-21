#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

BASEDIR=$(dirname $0)
ABSOLUTE_BASEDIR="$(cd ${BASEDIR} && pwd)"
# shellcheck disable=SC2034
# Used in utils.sh
PLAYGROUND_DIR="$(cd ${BASEDIR} && cd .. && pwd)"

source "${ABSOLUTE_BASEDIR}/utils.sh"

runGroovy "$@"
