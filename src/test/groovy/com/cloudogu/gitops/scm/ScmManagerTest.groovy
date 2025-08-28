package com.cloudogu.gitops.scm

import org.junit.jupiter.api.Test

class ScmManagerTest {


    @Test
    void 'test'() {
        new ScmManager().setup()
        new ScmManager().installScmmPlugins()

    }
}