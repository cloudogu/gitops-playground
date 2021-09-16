#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

# Downloads plugins declared in plugins.txt via official jenkins/docker's install-plugins.sh
# This makes sure to get deterministic version of plugins and all dependencies
  
# The plugins are downloaded to $PLUGIN_FOLDER/plugins
PLUGIN_FOLDER="$1"

ABSOLUTE_BASEDIR="$(cd "$(dirname $0)" && pwd)"

# shellcheck disable=SC2046 # We actually do want word splitting here so each plugin is passed as one argument
JENKINS_UC=https://updates.jenkins.io REF="${PLUGIN_FOLDER}" \
  "${ABSOLUTE_BASEDIR}/install-plugins.sh" $(cat ${ABSOLUTE_BASEDIR}/plugins.txt)