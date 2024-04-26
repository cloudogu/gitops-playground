package com.cloudogu.gitops.utils


import org.junit.jupiter.api.Test
import static org.assertj.core.api.Assertions.assertThat

class HelmClientTest {
    @Test
    void 'assembles parameters for upgrade'() {
        def commandExecutor = new CommandExecutorForTest()
        new HelmClient(commandExecutor).upgrade("the-release", "path/to/chart", [
                version: 'the-version',
                namespace: 'the-namespace',
                values: 'values.yaml',
        ])
        new HelmClient(commandExecutor).upgrade("the-release", "path/to/chart", [:])
        new HelmClient(commandExecutor).upgrade("the-release", "path/to/chart", [namespace: 'the-namespace'])

        assertThat(commandExecutor.actualCommands[0]).isEqualTo('helm upgrade -i the-release path/to/chart --version the-version --values values.yaml --namespace the-namespace --create-namespace ')
        assertThat(commandExecutor.actualCommands[1]).isEqualTo('helm upgrade -i the-release path/to/chart --create-namespace ')
        assertThat(commandExecutor.actualCommands[2]).isEqualTo('helm upgrade -i the-release path/to/chart --namespace the-namespace --create-namespace ')
    }

    @Test
    void 'assembles parameters for uninstall'() {
        def commandExecutor = new CommandExecutorForTest()
        new HelmClient(commandExecutor).uninstall("the-release", 'the-namespace')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo('helm uninstall the-release --namespace the-namespace')
    }
}
