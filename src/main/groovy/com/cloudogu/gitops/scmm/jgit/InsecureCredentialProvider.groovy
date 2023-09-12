package com.cloudogu.gitops.scmm.jgit

import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

/**
 * JGit, a project used within eclipse, is developed with an interactive UI in mind.
 * The documentation for the CredentialsProvider says
 * > CredentialItems are usually presented in bulk, allowing implementors to combine them into a single UI widget and streamline the authentication process for an end-user.
 * This highlights the focus on the UI for an end-user.
 *
 * As a result, checking for SSL verification is a little clunky as we need to check for messages intended for end-users.
 *
 * Other options would have included overwriting the HttpConnection or saving the git configuration on disk.
 *
 * @link https://archive.eclipse.org/jgit/site/4.10.0.201712302008-r/apidocs/org/eclipse/jgit/transport/CredentialsProvider.html
 */
class InsecureCredentialProvider extends CredentialsProvider {
    @Override
    boolean isInteractive() {
        return false
    }

    @Override
    boolean supports(CredentialItem... items) {
        def message = items.find { it instanceof CredentialItem.InformationalMessage }
        if (message == null) {
            return false
        }

        return message.promptText =~ /^A secure connection to .* could not be established/
    }

    @Override
    boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        items.findAll { it instanceof CredentialItem.YesNoType }.each {
            if (it.promptText == "Skip SSL verification for this single git operation" ||
                it.promptText =~ /^Skip SSL verification for git operations for repository/) {
                (it as CredentialItem.YesNoType).setValue(true)
            } else if (it.promptText == "Always skip SSL verification for this server from now on") {
                // otherwise we would persistently overwrite our $HOME/.gitconfig
                (it as CredentialItem.YesNoType).setValue(false)
            }
        }

        return true
    }
}
