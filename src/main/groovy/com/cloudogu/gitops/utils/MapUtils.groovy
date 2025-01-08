package com.cloudogu.gitops.utils 

class MapUtils {

    static Map deepMerge(Map src, Map target) {
        src.each { key, value ->
            if (value == null) {
                // If the value in src is null, set the key in target to null
                target[key] = null
            } else {
                target.merge(key, value) { oldVal, newVal ->
                    // Case 1: If both values are Maps, perform a deep merge
                    if (oldVal instanceof Map && newVal instanceof Map) {
                        return deepMerge(newVal, oldVal)  // Recursively merge the maps
                    }
                    // Case 2: If newVal is null, set the key to null in target
                    if (newVal == null) {
                        return null
                    }
                    // Case 3: If oldVal is null, use newVal
                    if (oldVal == null) {
                        return newVal
                    }
                    // Case 4: Default, return the new value (newVal takes priority)
                    return newVal
                }
            }
        }
        return target
    }

}
