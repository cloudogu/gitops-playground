package com.cloudogu.gitops.utils

import com.cloudogu.gitops.Application
import groovy.util.logging.Slf4j
import com.cloudogu.gitops.Feature

@Slf4j
class FeatureUtils {
    static void runHook(Application app, String methodName, def config) {
        app.features.each { feature ->
            // Executing only the method if the derived feature class has implemented the passed specific hook method
            def mm = feature.metaClass.getMetaMethod(methodName, config)
            if (mm && mm.declaringClass.theClass != Feature) {
                log.debug("Executing ${methodName} hook on feature ${feature.class.name}")
                mm.invoke(feature, config)
            }
        }
    }
}
