package com.cloudogu.gitops.utils;

import java.util.List;
import java.util.Map;

public class MapUtils {

  private MapUtils() {}

  public static Map<String, Object> deepMerge(Map<String, Object> src, Map<String, Object> target) {
    if (src == null) {
      return target;
    }
    src.forEach(
        (String key, Object value) -> {
          Object oldVal = target.containsKey(key) ? target.get(key) : null;
          if (oldVal instanceof Map && value instanceof Map) {
            target.put(key, deepMerge(asStringObjectMap(value), asStringObjectMap(oldVal)));
          } else {
            target.put(key, value);
          }
        });
    return target;
  }

  public static Map<String, Object> deepMergeDefaults(
      Map<String, Object> src, Map<String, Object> target) {
    if (src == null) {
      return target;
    }
    src.forEach((String key, Object value) -> mergeDefaultEntry(key, value, target));
    return target;
  }

  private static void mergeDefaultEntry(String key, Object value, Map<String, Object> target) {
    if (value == null && target.containsKey(key)) {
      return;
    }

    Object oldVal = target.containsKey(key) ? target.get(key) : null;
    if (oldVal instanceof Map && value instanceof Map) {
      target.put(key, deepMergeDefaults(asStringObjectMap(value), asStringObjectMap(oldVal)));
    } else {
      target.put(key, value);
    }
  }

  /**
   * Casts the result of parsing untyped YAML/JSON data (e.g. via Groovy's {@code YamlSlurper} or a
   * {@code Map<String, Object>} lookup) to {@code Map<String, Object>}.
   *
   * <p>By convention, every map produced by our YAML/JSON parsing has {@code String} keys, but
   * generic type erasure means the JVM can only verify at runtime that {@code value} is a raw
   * {@code Map}, not that it is parameterized with {@code String} keys. Callers are expected to
   * have already checked {@code value instanceof Map} (or know it from the surrounding YAML/JSON
   * schema) before calling this.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> asStringObjectMap(Object value) {
    return (Map<String, Object>) value;
  }

  /** Same rationale as {@link #asStringObjectMap(Object)}, but for a list of such maps. */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> asListOfStringObjectMaps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
