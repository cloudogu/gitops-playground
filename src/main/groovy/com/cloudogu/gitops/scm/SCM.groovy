package com.cloudogu.gitops.scm

abstract class SCM {
    abstract createRepo()

    abstract checkoutRepo()

    abstract cloneRepo()

    abstract push()

    abstract commit()
}