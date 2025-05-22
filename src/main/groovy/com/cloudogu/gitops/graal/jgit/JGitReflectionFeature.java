package com.cloudogu.gitops.graal.jgit;

import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.HttpConfig;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.util.Arrays;
import java.util.List;

public class JGitReflectionFeature implements Feature {

    // Found here https://github.com/quarkiverse/quarkus-jgit/blob/3.5.1/deployment/src/main/java/io/quarkus/jgit/deployment/JGitProcessor.java
    private static final List<Class<?>> jgitReflectionClasses = Arrays.asList(
            BranchConfig.BranchRebaseMode.class,
            CommitConfig.CleanupMode.class,
            CoreConfig.AutoCRLF.class,
            CoreConfig.CheckStat.class,
            CoreConfig.EOL.class,
            CoreConfig.EolStreamType.class,
            CoreConfig.HideDotFiles.class,
            CoreConfig.LogRefUpdates.class,
            CoreConfig.SymLinks.class,
            CoreConfig.TrustStat.class,
            DiffAlgorithm.SupportedAlgorithm.class,
            DirCache.DirCacheVersion.class,
            GpgConfig.GpgFormat.class,
            IndexDiff.StageState.class,
            JGitText.class,
            ObjectChecker.ErrorType.class,
            MergeCommand.ConflictStyle.class,
            MergeCommand.FastForwardMode.Merge.class,
            MergeCommand.FastForwardMode.class,
            Ref.Storage.class,
            RefUpdate.Result.class,
            SignatureVerifier.TrustLevel.class,
            SubmoduleConfig.FetchRecurseSubmodulesMode.class,
            HttpConfig.HttpRedirectMode.class
    );

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess beforeAnalysisAccess) {

        for (Class<?> someClass : jgitReflectionClasses) {
                RuntimeReflection.register(someClass);
                RuntimeReflection.register(someClass.getDeclaredConstructors());
                RuntimeReflection.register(someClass.getDeclaredMethods());
                RuntimeReflection.register(someClass.getDeclaredFields());
        }
    }
}
