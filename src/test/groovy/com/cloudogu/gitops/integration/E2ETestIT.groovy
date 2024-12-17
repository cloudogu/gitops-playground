package com.cloudogu.gitops.integration

import org.junit.jupiter.api.Test

class E2ETestIT {


    @Test
    void runE2EScriptedIT(){

        def k3dAddress = System.getenv("K3D_ADDRESS") ?: "localhost"
        E2EIT.main([
                "--url", "http://${k3dAddress}:9090",
                "--user", "admin",
                "--password", "admin",
                "--writeFailedLog",
                "--fail",
                "--retry", "2"
        ] as String[])

    }
}
