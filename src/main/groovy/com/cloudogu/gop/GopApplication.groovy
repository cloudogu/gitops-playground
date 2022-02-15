package com.cloudogu.gop

import com.cloudogu.gop.cli.GopCli
import com.cloudogu.gop.modules.ModuleRepository

class GopApplication {

    static void main(String[] args) {
        def gopCli = new GopCli();
        gopCli.parse(args)

        def moduleRepository = new ModuleRepository();
        moduleRepository.execute()
    }
}
