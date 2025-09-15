package com.cloudogu.gitops.gitabstraction.worktreeOps

/** Worktree-Ops:
 * Worktree operations are local Git actions (JGit) on a checked-out repository:
 * Clone, add/commit, tag, push/pull, clear working directory, push refs/mirrors.
 * Use the URL from Server-Ops (computePushUrl) and a credentials provider.
 * Do not call provider REST APIs.*/
interface GitRepo {
    String getRepoTarget()

    String getAbsoluteLocalRepoTmpDir()

    void cloneRepo()

    void commitAndPush(String message)

    void commitAndPush(String message, String tag, String refSpec)

    void pushAll(boolean force)

    void pushRef(String ref, String targetRef, boolean force)

    void clearRepo()

}