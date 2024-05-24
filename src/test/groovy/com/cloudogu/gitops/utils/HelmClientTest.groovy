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

        assertThat(commandExecutor.actualCommands[0]).startsWith('helm upgrade -i the-release path/to/chart --create-namespace')
        assertThat(commandExecutor.actualCommands[0]).contains(' --version the-version')
        assertThat(commandExecutor.actualCommands[0]).contains(' --values values.yaml')
        assertThat(commandExecutor.actualCommands[0]).contains(' --namespace the-namespace')

        assertThat(commandExecutor.actualCommands[1]).isEqualTo('helm upgrade -i the-release path/to/chart --create-namespace')
        assertThat(commandExecutor.actualCommands[2]).isEqualTo('helm upgrade -i the-release path/to/chart --create-namespace --namespace the-namespace')
    }
    
    @Test
    void 'runs helm template'() {
        def commandExecutor = new CommandExecutorForTest()
        new HelmClient(commandExecutor).template("the-release", "path/to/chart", [
                version: 'the-version',
                namespace: 'the-namespace',
                values: 'values.yaml',
        ])
        new HelmClient(commandExecutor).template("the-release", "path/to/chart", [:])
        new HelmClient(commandExecutor).template("the-release", "path/to/chart", [namespace: 'the-namespace'])

        assertThat(commandExecutor.actualCommands[0]).startsWith('helm template the-release path/to/chart ')
        assertThat(commandExecutor.actualCommands[0]).contains(' --version the-version')
        assertThat(commandExecutor.actualCommands[0]).contains(' --values values.yaml')
        assertThat(commandExecutor.actualCommands[0]).contains(' --namespace the-namespace')
        
        assertThat(commandExecutor.actualCommands[1]).isEqualTo('helm template the-release path/to/chart')
        assertThat(commandExecutor.actualCommands[2]).isEqualTo('helm template the-release path/to/chart --namespace the-namespace')
    }

    @Test
    void 'assembles parameters for uninstall'() {
        def commandExecutor = new CommandExecutorForTest()
        new HelmClient(commandExecutor).uninstall("the-release", 'the-namespace')

        assertThat(commandExecutor.actualCommands[0]).isEqualTo('helm uninstall the-release --namespace the-namespace')
    }
}
