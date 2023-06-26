package com.cloudogu.gitops.utils

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.jetbrains.annotations.NotNull

import java.util.function.Predicate

class InMemoryCookieJar implements CookieJar {
    private List<Cookie> cookies = []

    @Override
    void saveFromResponse(@NotNull HttpUrl httpUrl, @NotNull List<Cookie> list) {
        cookies.addAll(list)
    }

    @Override
    List<Cookie> loadForRequest(@NotNull HttpUrl httpUrl) {
        cookies.removeIf(new Predicate<Cookie>(){
            @Override
            boolean test(Cookie cookie) {
                return cookie.expiresAt() < System.currentTimeMillis()
            }
        })

        return cookies
    }
}
