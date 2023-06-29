#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

source "${ABSOLUTE_BASEDIR}/utils.sh"

runGroovy "$@"
