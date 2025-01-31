package com.cloudogu.gitops.scmm.api

class GitLabMember {
    private int userId        // The ID of the user to add
    private int accessLevel

    GitLabMember(int userId, int accessLevel) {
        this.userId = userId
        this.accessLevel = accessLevel
    }

    static class AccessLevel {
        static final int GUEST = 10;
        static final int REPORTER = 20;
        static final int DEVELOPER = 30;
        static final int MAINTAINER = 40;
    }

    int getUserId() {
        return userId
    }

    void setUserId(int userId) {
        this.userId = userId
    }

    int getAccessLevel() {
        return accessLevel
    }

    void setAccessLevel(int accessLevel) {
        this.accessLevel = accessLevel
    }

    @Override
    String toString() {
        return "GitLabMember{" +
                "userId=" + userId +
                ", accessLevel=" + accessLevel +
                '}';
    }
}
