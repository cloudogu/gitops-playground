package com.cloudogu.gop.modules.metrics

import com.cloudogu.gop.GopConfig
import com.cloudogu.gop.modules.GopModule
import com.cloudogu.gop.tools.git.Git

class MetricsModule implements GopModule {

    @Override
    def run() {
        if (MetricsConfig.instance.metrics) {
//            def git = new Git()
            Git.initRepoWithSource("bla", "blub")
        }
    }
}
