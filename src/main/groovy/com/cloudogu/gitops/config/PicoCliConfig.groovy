package com.cloudogu.gitops.config

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import groovy.transform.MapConstructor
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Mixin
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import static com.cloudogu.gitops.config.ConfigConstants.*

@Singleton
@MapConstructor(noArg = true, includeSuperProperties = true, includeFields = true)
@Command(name = BINARY_NAME, description = APP_DESCRIPTION)
class PicoCliConfig {

    @JsonPropertyDescription(APPLICATION_DESCRIPTION)
    @Mixin
    PicoCliConfigApplicationSchema application = new PicoCliConfigApplicationSchema()

    class PicoCliConfigApplicationSchema {

        @Option(names = ['--output-config-file'], description = OUTPUT_CONFIG_FILE_DESCRIPTION, help = true)
        Boolean outputConfigFile
        @Option(names = ["-v", "--version"], help = true, description = "Display version and license info")
        Boolean versionInfoRequested
        @Option(names = ["-h", "--help"], usageHelp = true, description = "Display this help message")
        Boolean usageHelpRequested
        @Option(names = ['--insecure'], description = INSECURE_DESCRIPTION)
        Boolean insecure
        @Option(names = ['--openshift'], description = OPENSHIFT_DESCRIPTION)
        Boolean openshift
        @Option(names = ['--username'], description = USERNAME_DESCRIPTION)
        String username
        @Option(names = ['--password'], description = PASSWORD_DESCRIPTION)
        String password
        @Option(names = ['-y', '--yes'], description = PIPE_YES_DESCRIPTION)
        Boolean yes
        @Option(names = ['--name-prefix'], description = NAME_PREFIX_DESCRIPTION)
        String namePrefix
        @Option(names = ['--destroy'], description = DESTROY_DESCRIPTION)
        Boolean destroy
        @Option(names = ['--pod-resources'], description = POD_RESOURCES_DESCRIPTION)
        Boolean podResources
        @Option(names = ['--git-name'], description = GIT_NAME_DESCRIPTION)
        String gitName
        @Option(names = ['--git-email'], description = GIT_EMAIL_DESCRIPTION)
        String gitEmail
        @Option(names = ['--base-url'], description = BASE_URL_DESCRIPTION)
        String baseUrl
        @Option(names = ['--url-separator-hyphen'], description = URL_SEPARATOR_HYPHEN_DESCRIPTION)
        Boolean urlSeparatorHyphen
        @Option(names = ['--mirror-repos'], description = MIRROR_REPOS_DESCRIPTION)
        Boolean mirrorRepos
        @Option(names = ['--skip-crds'], description = SKIP_CRDS_DESCRIPTION)
        Boolean skipCrds
        @Option(names = ['--namespace-isolation'], description = NAMESPACE_ISOLATION_DESCRIPTION)
        Boolean namespaceIsolation
        @Option(names = ['--netpols'], description = NETPOLS_DESCRIPTION)
        Boolean netpols
        @Option(names = ['--cluster-admin'], description = CLUSTER_ADMIN_DESCRIPTION)
        Boolean clusterAdmin
        @Option(names = ["-p", "--profile"], description = APPLICATION_PROFIL)
        String profile
    }

}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface PicoMapping {
    String value()
}