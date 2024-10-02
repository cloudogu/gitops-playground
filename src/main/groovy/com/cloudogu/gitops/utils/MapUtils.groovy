package com.cloudogu.gitops.utils 

class MapUtils {
    
    static Map deepMerge(Map src, Map target) {
        src.forEach(
                (key, value) -> { if (value != null) target.merge(key, value, (oldVal, newVal) -> {
                    if (oldVal instanceof Map) {
                        if (!newVal instanceof Map) {
                            throw new RuntimeException("Can't merge config, different types, map vs other: Map ${oldVal}; Other ${newVal}")
                        }
                        return deepMerge(newVal as Map, oldVal)
                    } else {
                        return newVal
                    }
                })
                })
        return target
    }
}
