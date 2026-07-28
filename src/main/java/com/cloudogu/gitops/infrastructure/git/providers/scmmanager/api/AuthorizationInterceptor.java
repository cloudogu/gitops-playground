package com.cloudogu.gitops.infrastructure.git.providers.scmmanager.api;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

/** OkHttp interceptor that adds HTTP basic auth credentials to every SCM-Manager request. */
@RequiredArgsConstructor
public class AuthorizationInterceptor implements Interceptor {
private final String username;
private final String password;

@Override
public Response intercept(@NotNull Chain chain) throws IOException {
	Request newRequest =
		chain
			.request()
			.newBuilder()
			.header("Authorization", Credentials.basic(username, password))
			.build();

	return chain.proceed(newRequest);
}
}
