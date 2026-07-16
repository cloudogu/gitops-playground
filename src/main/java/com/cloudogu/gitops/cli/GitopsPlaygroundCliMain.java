package com.cloudogu.gitops.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitopsPlaygroundCliMain {

    private static final Logger log = LoggerFactory.getLogger(GitopsPlaygroundCliMain.class);

    public static void main(String[] args) throws Exception {
        new GitopsPlaygroundCliMain().exec(args, GitopsPlaygroundCli.class);
    }

    public void exec(String[] args, Class<? extends GitopsPlaygroundCli> commandClass) {
        try {
            GitopsPlaygroundCli app = commandClass.getDeclaredConstructor().newInstance();
            System.exit(app.run(args).ordinal());
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.error("", e);
            } else {
                log.error(e.getMessage());
            }
            System.exit(ReturnCode.GENERIC_ERROR.ordinal());
        } catch (Exception e) {
            log.error("Fatal error starting CLI", e);
            System.exit(ReturnCode.GENERIC_ERROR.ordinal());
        }
    }
}
