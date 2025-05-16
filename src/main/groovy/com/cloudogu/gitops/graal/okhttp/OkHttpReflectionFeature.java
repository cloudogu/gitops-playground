package com.cloudogu.gitops.graal.okhttp;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.util.Arrays;
import java.util.List;

public class OkHttpReflectionFeature implements Feature {

    private static List<String> okhttpReflectionClasses = Arrays.asList(
            "okhttp3.internal.http.RetryAndFollowUpInterceptor$Companion",
            "okhttp3.OkHttpClient",
            "okhttp3.Request"
    );

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess beforeAnalysisAccess) {

        for (String item : okhttpReflectionClasses) {
            try {
                Class<?> someClass = Class.forName(item);
                RuntimeReflection.register(someClass);
                RuntimeReflection.register(someClass.getDeclaredConstructors());
                RuntimeReflection.register(someClass.getDeclaredMethods());
                RuntimeReflection.register(someClass.getDeclaredFields());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
