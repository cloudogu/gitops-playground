package com.cloudogu.gitops.utils;

import java.util.Map;

@SuppressWarnings({"rawtypes", "unchecked"})
public class MapUtils {

    public static Map deepMerge(Map src, Map target) {
        if (src == null) {
            return target;
        }
        src.forEach((key, value) -> {
            Object oldVal = target.containsKey(key) ? target.get(key) : null;
            if (oldVal instanceof Map && value instanceof Map) {
                target.put(key, deepMerge((Map) value, (Map) oldVal));
            } else {
                target.put(key, value);
            }
        });
        return target;
    }

    public static Map deepMergeDefaults(Map src, Map target) {
        if (src == null) {
            return target;
        }
        src.forEach((key, value) -> {
            if (value == null && target.containsKey(key)) {
                return;
            }

            Object oldVal = target.containsKey(key) ? target.get(key) : null;
            if (oldVal instanceof Map && value instanceof Map) {
                target.put(key, deepMergeDefaults((Map) value, (Map) oldVal));
            } else {
                target.put(key, value);
            }
        });
        return target;
    }
}
