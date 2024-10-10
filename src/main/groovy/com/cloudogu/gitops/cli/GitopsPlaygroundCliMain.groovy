package com.cloudogu.gitops.cli


import groovy.util.logging.Slf4j

@Slf4j
class GitopsPlaygroundCliMain {

    static void main(String[] args) throws Exception {
        new GitopsPlaygroundCliMain().exec(args, GitopsPlaygroundCli.class)
    }

    @SuppressWarnings('GrMethodMayBeStatic')
    // Non-static for easier testing and reuse
    void exec(String[] args, Class<? extends GitopsPlaygroundCli> commandClass) {
        GitopsPlaygroundCli app = commandClass.getDeclaredConstructor().newInstance()
        
        try {
            System.exit(app.run(args).ordinal())
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.error('', e)
            } else {
                log.error(e.message)
            }
            System.exit(ReturnCode.GENERIC_ERROR.ordinal())
        }
    }

}
