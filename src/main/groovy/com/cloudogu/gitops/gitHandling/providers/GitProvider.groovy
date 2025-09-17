package com.cloudogu.gitops.gitHandling.providers

interface GitProvider {
    /**
     * @param repoTarget "namespace/name"
     * @return true when repo new added, false when repo already exists
     */
    boolean createRepository(String repoTarget, String description, boolean initialize)

    void setRepositoryPermission(String repoTarget, String principal, Permission.Role role, boolean groupPermission)

    String computePushUrl(String repoTarget)

    //TODO put this into config package
    GitPushAuth pushAuth(boolean isCentralRepo)

    //TODO add deleteRepository , delete User (src/main/groovy/com/cloudogu/gitops/destroy/ScmmDestructionHandler.groovy)
}