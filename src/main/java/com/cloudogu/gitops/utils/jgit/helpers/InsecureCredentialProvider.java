package com.cloudogu.gitops.utils.jgit.helpers;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import java.util.Arrays;
import java.util.regex.Pattern;

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
public class InsecureCredentialProvider extends CredentialsProvider {
    private static final Pattern INSECURE_CONNECTION_PATTERN = Pattern.compile("^A secure connection to .* could not be established");
    private static final Pattern SKIP_SSL_PATTERN = Pattern.compile("^Skip SSL verification for git operations for repository");

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean supports(CredentialItem... items) {
        if (items == null) {
            return false;
        }
        return Arrays.stream(items)
                .filter(it -> it instanceof CredentialItem.InformationalMessage)
                .map(it -> (CredentialItem.InformationalMessage) it)
                .anyMatch(message -> INSECURE_CONNECTION_PATTERN.matcher(message.getPromptText()).find());
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        if (items == null) {
            return true;
        }
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.YesNoType yesNo) {
                String prompt = yesNo.getPromptText();
                if ("Skip SSL verification for this single git operation".equals(prompt) || SKIP_SSL_PATTERN.matcher(prompt).find()) {
                    yesNo.setValue(true);
                } else if ("Always skip SSL verification for this server from now on".equals(prompt)) {
                    // otherwise we would persistently overwrite our $HOME/.gitconfig
                    yesNo.setValue(false);
                }
            }
        }
        return true;
    }
}
