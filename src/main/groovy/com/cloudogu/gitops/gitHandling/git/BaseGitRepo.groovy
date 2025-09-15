package com.cloudogu.gitops.gitHandling.git

abstract class BaseGitRepo implements GitRepo {
    // Convenience: calls the full variant with default values
    @Override
    void commitAndPush(String message) {
        commitAndPush(message, null, 'HEAD:refs/heads/main')
    }

    @Override
    void commitAndPush(String message, String tag) {
        commitAndPush(message, tag, 'HEAD:refs/heads/main')
    }

    @Override
    void pushRef(String ref, boolean force) {
        pushRef(ref, ref, force)
    }

    @Override
    void copyDirectoryContents(String srcDir) {
        copyDirectoryContents(srcDir, (FileFilter) null)
    }
}
