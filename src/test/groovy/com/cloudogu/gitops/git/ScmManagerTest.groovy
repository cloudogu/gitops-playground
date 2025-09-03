package com.cloudogu.gitops.git

import com.cloudogu.gitops.git.scmm.ScmManager
import org.junit.jupiter.api.Test

class ScmManagerTest {


    @Test
    void 'test'() {
        new ScmManager().setup()
        new ScmManager().installScmmPlugins()

    }
}