package com.cloudogu.gitops.graal.jgit;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.util.Arrays;
import java.util.List;

public class JGitReflectionFeature implements Feature {

    // Found here https://github.com/quarkiverse/quarkus-jgit/blob/3.0.0/deployment/src/main/java/io/quarkus/jgit/deployment/JGitProcessor.java
    private static List<String> jgitReflectionClasses = Arrays.asList(
            "org.eclipse.jgit.api.MergeCommand$FastForwardMode",
            "org.eclipse.jgit.api.MergeCommand$FastForwardMode$Merge",
            "org.eclipse.jgit.internal.JGitText",
            "org.eclipse.jgit.lib.CoreConfig$AutoCRLF",
            "org.eclipse.jgit.lib.CoreConfig$CheckStat",
            "org.eclipse.jgit.lib.CoreConfig$EOL",
            "org.eclipse.jgit.lib.CoreConfig$EolStreamType",
            "org.eclipse.jgit.lib.CoreConfig$HideDotFiles",
            "org.eclipse.jgit.lib.CoreConfig$SymLinks",
            "org.eclipse.jgit.lib.CoreConfig$LogRefUpdates",
            "org.eclipse.jgit.lib.CoreConfig$TrustPackedRefsStat"
    );

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess beforeAnalysisAccess) {

        for (String item : jgitReflectionClasses) {
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
