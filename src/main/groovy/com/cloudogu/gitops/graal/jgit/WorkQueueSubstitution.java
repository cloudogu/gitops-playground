package com.cloudogu.gitops.graal.jgit;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Source: <a href="https://github.com/quarkiverse/quarkus-jgit/tree/3.0.0">...</a>
 */
@TargetClass(className = "org.eclipse.jgit.lib.internal.WorkQueue")
@Substitute
public final class WorkQueueSubstitution {

    private static final ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) Executors
            .newScheduledThreadPool(1);

    @Substitute
    public static ScheduledThreadPoolExecutor getExecutor() {
        return executor;
    }
}
