package com.cloudogu.gitops.git.utils

class StringUtils {
    //Removes leading and trailing slashes (prevents absolute paths when using resolve).
    static String trimBoth(String str) {
        return (str ?: "").replaceAll('^/+', '').replaceAll('/+$', '')
    }

    static URI withSlash(URI uri) {
        def urlString = uri.toString()
        return urlString.endsWith('/') ? uri : URI.create(urlString + '/')
    }
}
