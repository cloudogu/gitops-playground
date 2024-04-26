package com.cloudogu.gitops.graal.jgit;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.TargetClass;

import java.util.Random;

/**
 * Source: <a href="https://github.com/quarkiverse/quarkus-jgit/tree/3.0.0">...</a>
 */
@TargetClass(className = "org.eclipse.jgit.util.FileUtils")
public final class FileUtilsSubstitution {
    @Alias
    @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.Reset)
    private static Random RNG;
}
