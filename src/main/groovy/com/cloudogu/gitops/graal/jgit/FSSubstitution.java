package com.cloudogu.gitops.graal.jgit;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.io.File;

/**
 * Source: <a href="https://github.com/quarkiverse/quarkus-jgit/tree/3.0.0">...</a>
 */
@TargetClass(className = "org.eclipse.jgit.util.FS")
public final class FSSubstitution {

    /**
     * The original method caches the user.home property during build time.
     *
     * TODO: Find a way to call userHomeImpl() instead and cache the result
     */
    @Substitute
    public File userHome() {
        return new File(System.getProperty("user.home")).getAbsoluteFile();
    }
}
