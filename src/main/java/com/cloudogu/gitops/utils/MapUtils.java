package com.cloudogu.gitops.utils;

import java.util.Map;

public class MapUtils {

  @SuppressWarnings("unchecked")
  public static Map<String, Object> deepMerge(Map<String, Object> src, Map<String, Object> target) {
    if (src == null) {
      return target;
    }
    src.forEach(
        (key, value) -> {
          Object oldVal = target.containsKey(key) ? target.get(key) : null;
          if (oldVal instanceof Map && value instanceof Map) {
            target.put(key, deepMerge((Map<String, Object>) value, (Map<String, Object>) oldVal));
          } else {
            target.put(key, value);
          }
        });
    return target;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> deepMergeDefaults(
      Map<String, Object> src, Map<String, Object> target) {
    if (src == null) {
      return target;
    }
    src.forEach(
        (key, value) -> {
          if (value == null && target.containsKey(key)) {
            return;
          }

          Object oldVal = target.containsKey(key) ? target.get(key) : null;
          if (oldVal instanceof Map && value instanceof Map) {
            target.put(
                key, deepMergeDefaults((Map<String, Object>) value, (Map<String, Object>) oldVal));
          } else {
            target.put(key, value);
          }
        });
    return target;
  }
}
