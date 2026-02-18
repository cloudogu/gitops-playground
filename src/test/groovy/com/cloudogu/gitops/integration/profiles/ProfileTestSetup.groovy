package com.cloudogu.gitops.integration.profiles

import groovy.util.logging.Slf4j

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher

import com.cloudogu.gitops.integration.TestK8sHelper

/**
 * Common setup to dump K88s content after failing tests.
 */
@Slf4j
class ProfileTestSetup implements TestWatcher{

    private static boolean anyTestFailed = false
    @RegisterExtension
    final TestWatcher watcher = this

    @Override
    void testFailed(ExtensionContext context, Throwable cause) {
        anyTestFailed = true
    }

    @AfterAll
    static void afterAllOnlyOnFailure() {
        // if one test fails, logging is necessary
        if (anyTestFailed) {
            log.info "##############  K8s dump ##############"
            TestK8sHelper.dumpNamespacesAndPods()
        }
    }

}
