package com.cloudogu.gitops.utils

import groovy.json.JsonSlurper

import static groovy.json.JsonOutput.toJson

class MapUtils {
    
    static Map deepCopy(Map input) {
        // Lazy mans deep map copy 😬
        String json = toJson(input)
        return (Map) new JsonSlurper().parseText(json)
    }

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

    static Map makeDeeplyImmutable(Map map) {
        map.forEach((key, value) -> {
            if (value instanceof Map) {
                map[key] = Collections.unmodifiableMap(value)
            }
        })
        return Collections.unmodifiableMap(map)
    }
}
