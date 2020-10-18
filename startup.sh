#!/usr/bin/env bash
#set -o errexit -o nounset -o pipefail
#set -x

[ "$UID" -eq 0 ] || exec sudo bash "$0" "$@"

CUR_DIR=$(pwd)

cd /var/lib/rancher/k3s/server/manifests/
cp -as "${CUR_DIR}/flux/"*.yaml  .
cp -as "${CUR_DIR}/jenkins/"*.yaml  .
cp -as "${CUR_DIR}/scm-manager/"*.yaml  .

cd /var/lib/rancher/k3s/server/static/charts/
cp -as "${CUR_DIR}/scm-manager/scm-manager-2.7.1.tgz"  .