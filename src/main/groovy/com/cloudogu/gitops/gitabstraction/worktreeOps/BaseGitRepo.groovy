package com.cloudogu.gitops.gitabstraction.worktreeOps

abstract class BaseGitRepo implements GitRepo {
    // Convenience: calls the full variant with default values
    @Override
    void commitAndPush(String message) {
        commitAndPush(message, null, 'HEAD:refs/heads/main')
    }

    // Convenience overloads (optional)
    void pushAll() {
        pushAll(false)
    }

    void pushRef(String ref) {
        pushRef(ref, ref, false)
    }

    void pushRef(String ref, boolean force) {
        pushRef(ref, ref, force)
    }
}
