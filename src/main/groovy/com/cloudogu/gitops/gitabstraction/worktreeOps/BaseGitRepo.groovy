package com.cloudogu.gitops.gitabstraction.worktreeOps

abstract class BaseGitRepo implements GitRepo {
    // Convenience: calls the full variant with default values
    @Override
    void commitAndPush(String message) {
        commitAndPush(message, null, 'HEAD:refs/heads/main')
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
