package com.cloudogu.gitops.git

import com.cloudogu.gitops.config.Config
import org.junit.jupiter.api.Test

class ScmConfigTests {


    Config testConfig = Config.fromMap([
            application: [
                    prefix: 'testprefix'
            ]
    ])


    @Test
    void ''(){

    }
}