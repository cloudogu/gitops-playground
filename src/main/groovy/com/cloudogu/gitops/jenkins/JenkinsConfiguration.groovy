package com.cloudogu.gitops.jenkins

class JenkinsConfiguration {
    private final String url
    private final String username
    private final String password
    private final boolean insecure

    JenkinsConfiguration(String url, String username, String password, boolean insecure) {
        this.url = url
        this.username = username
        this.password = password
        this.insecure = insecure
    }

    String getUrl() {
        return url
    }

    String getUsername() {
        return username
    }

    String getPassword() {
        return password
    }

    boolean getInsecure() {
        return insecure
    }
}
