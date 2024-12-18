package com.cloudogu.gitops.integration

import org.junit.jupiter.api.Test

class E2ETestIT {


    @Test
    void runE2EScriptedIT(){

        def k3dAddress = System.getenv("K3D_ADDRESS") ?: "jenkins.localhost"
        E2EIT.main([
                "--url", "http://jenkins.localhost",
                "--user", "admin",
                "--password", "admin",
                "--writeFailedLog",
                "--fail",
                "--retry", "2"
        ] as String[])

    }

    @Test
    void getLocalClusterIPTest(){
        getLocalClusterIP()
    }

    void getLocalClusterIP()
    {
        def clusterName = 'gitops-playground' // Replace this with your actual cluster name

        def command = "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' k3d-${clusterName}-server-0"
        def process = command.execute()
        process.waitFor()// Execute the command
        def output = process.text.trim() // Get the output and trim any surrounding whitespace

        println "IP Address: $output" // Print the IP address or handle it as needed

        def command1 = "docker container ps"
        def process1 = command1.execute() // Execute the command
        def output1 = process1.text.trim() // Get the output and trim any surrounding whitespace
        println "IP Address: $output1" // Print the IP address or handle it as needed

    }
}
