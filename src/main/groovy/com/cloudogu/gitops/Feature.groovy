package com.cloudogu.gitops

import groovy.util.logging.Slf4j

@Slf4j
abstract class Feature {

    boolean install() {
        if (isEnabled()) {
            log.info("Installing Feature ${getClass().getSimpleName()}")
            enable()
            return true
        } else {
            log.info("Feature ${getClass().getSimpleName()} is disabled")
            disable()
            return false
        }
    }
    
    abstract boolean isEnabled()
    abstract void enable()
    void disable() {}
}