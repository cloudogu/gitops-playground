package com.cloudogu.gitops.cli;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitopsPlaygroundCliMain {

    public static void main(String[] args) {
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
