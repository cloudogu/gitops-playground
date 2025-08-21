package com.cloudogu.gitops.scm

import com.cloudogu.gitops.config.Credentials

interface ISCM {

    Credentials credentials
    createRepo()
    void init()


}