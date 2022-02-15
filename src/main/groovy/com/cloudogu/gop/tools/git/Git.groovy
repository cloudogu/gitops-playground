package com.cloudogu.gop.tools.git

import groovy.io.FileType

class Git {

    Git() {}

    static def initRepoWithSource(def source, def target, Closure evalInRepo = null) {
        def fg = 32
        def bg = 42
        def style = "${(char)27}[$fg;$bg"+"m"
        def dir = "############################################################################################   " + System.properties['user.dir'] + "   ############################################################################################"
        println(style+dir)
    }
}

//    echo "initiating repo $1 with source $2"
//    SOURCE_REPO="$1"
//    TARGET_REPO_SCMM="$2"
//    EVAL_IN_REPO="${3-}"
//
//    TMP_REPO=$(mktemp -d)
//
//    git clone "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" "${TMP_REPO}"
//    (
//    cd "${TMP_REPO}"
//    cp -r "${PLAYGROUND_DIR}/${SOURCE_REPO}"/* .
//    if [[ ${INTERNAL_SCMM} == false ]]; then
//      replaceAllScmmUrlsInFolder "${TMP_REPO}"
//    fi
//
//    if [[ -n "${EVAL_IN_REPO}" ]]; then
//      eval "${EVAL_IN_REPO}"
//    fi
//
//    git checkout main --quiet || git checkout -b main --quiet
//    git add .
//    git commit -m "Init ${TARGET_REPO_SCMM}" --quiet || true
//    waitForScmManager
//    git push -u "${SCMM_PROTOCOL}://${SCMM_USERNAME}:${SCMM_PASSWORD}@${SCMM_HOST}/repo/${TARGET_REPO_SCMM}" HEAD:main --force
//  )
//
//  rm -rf "${TMP_REPO}"
//
//  setDefaultBranch "${TARGET_REPO_SCMM}"
